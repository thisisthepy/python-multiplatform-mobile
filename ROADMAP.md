# ROADMAP

What is not done yet, why it matters, and what is known about it. Ordered by what blocks what,
not by size.

Items are written so the reason survives without the conversation that produced it. Where a
claim came from a measurement, the number is here; where it is a judgement, it says so.

Current test state, for reference:

| target | tests | passing |
|---|---|---|
| `desktopTest` | 107 | 106 |
| `iosSimulatorArm64Test` | 104 | 103 |
| `connectedDebugAndroidTest` (API 26 / 36 emu / 36 hw) | 8 | 8 |

The one failure on each JVM/native suite is `GCLeakTest`, red on purpose — see §1.

---

## 1. Release the GIL after initialisation

**Blocks:** all automatic reference release, all multithreaded use.
**State:** disabled in `Python3.initialize()`, with the reason in a comment there.

`Py_Initialize()` leaves its calling thread holding the GIL. Until that thread parks its state
with `PyEval_SaveThread()`, no other thread can attach, and any thread that tries blocks
forever in `PyGILState_Ensure`.

That is not theoretical. On desktop `GCLeakTest` reports `cleanup actions started: 1, reached
Py_DecRef: 0` — the cleaner thread starts one release, blocks, and never processes another.

**Correction: "on any platform" was wrong.** Running the same test on the iOS simulator gives
`started: 101002, reached Py_DecRef: 101001`. The cleaners run there and they do reach the
decref; the target's count still does not move (202 before, 202 after). So iOS is not blocked on
the GIL, and fixing §1 will not turn it green. One test name is covering two unrelated defects,
and either the iOS decrefs land on objects other than the one being measured or the measurement
itself is wrong. Nobody has looked yet.

**The recorded diagnosis is wrong, or at least incomplete.** Enabling the parking call was tried
again today and the result rules out the explanation this section gives.

```
desktopTest with PyEval_SaveThread() enabled
  0 tests run, JVM aborts (exit 134)
  SIGSEGV in libpython3.14.dylib  _TAIL_CALL_DICT_MERGE
                                  <- _PyFunction_Vectorcall
                                  <- PyObject_CallMethodObjArgs
  Java frame: EmbedAPI_desktopKt.PyImport_ImportModule
              <- EmbedApiLowLevelTest.importReturnsANewReferenceAndMissingModulesReportFailure
```

That call site is this:

```kotlin
val sys = Python3.withPython { PyImport_ImportModule("sys") }
```

**It is already inside the guard.** So "find the C API call that is outside `withGIL`" is not a
sufficient theory, and an audit of call sites will not find the bug. `withGIL` itself reads
correctly — depth-counted `PyGILState_Ensure`/`PyGILState_Release`, outermost acquires — so the
next attempt has to look at the *interaction*: what `PyGILState_Ensure` does on a thread whose
state was parked with `PyEval_SaveThread`, and whether the initialising thread and the calling
thread are the same one. Note this repo has already been bitten once by mixing the raw
thread-state scheme with the refcounted one.

The crash frame is inside CPython's import machinery, so the GIL is acquired far enough to start
executing Python before something goes wrong. That is a different shape from "no GIL held".

The older explanation, kept because it is still part of the picture: every C API call outside
`withGIL` becomes a
segfault far from its cause: closing the gap in `BenchmarkTest.testAttributeAccess`
(`PyImport_Import`) moved the crash from `PyImport_Import` to `_PyObject_Malloc`, with no Java
frame naming the new site.

Doing this properly needs a pass over every call site rather than a one-line change. Two things
make that cheaper than the last attempt: `ReleaseCounter` distinguishes "cleaner never ran" from
"cleaner ran and could not release", and the suites are large enough now to localise a
regression.

**Second attempt, and what it bought.** An audit of `commonMain` concluded the sweep was
complete and enabled the line. It was not: `DesktopOverheadBenchmark` in `desktopTest` called
`PyImport_ImportModule`, `PyObject_GetAttrString` and `PyList_Size` bare, which was harmless
only while `initialize()` kept the GIL to itself. Those are now guarded — a real gap closed and
kept.

With them fixed the crash moved to `_TAIL_CALL_DICT_MERGE`, which is CPython's `DICT_MERGE`
opcode: **Python bytecode executing without the GIL**, i.e. something that runs Python code
rather than merely touching an object. Parked again at that point, with the suite back to
118/117.

One caveat on that last frame: two other agents were editing the tree concurrently, one of them
in `ffi/conversion/`. An unguarded call only crashes once the GIL is actually released, so a
gap introduced by in-flight work would look exactly like a gap that was always there. The next
attempt should start from a quiet tree.

Pattern worth stating plainly, because it has now repeated three times: each fix moves the
crash somewhere further from its cause. Grep for the EmbedAPI function names across every source
set — `desktopTest` and `commonTest` included, which is where both misses have been so far —
rather than auditing by reading.

Note that free-threaded builds do **not** remove this requirement. Dropping the global lock
removes contention, not the rule that a thread must be attached before touching any object,
`Py_IncRef` included.

## 2. Finish the Android JNI surface

**Reopened. Marking this closed was wrong.**

It was closed on the strength of `AssembledApiTest` passing — 19 tests on `pmp_api26` and
`pmp_api36`, 0 failed. That was true and it did not mean what it was taken to mean. Those 19 tests
only ever exercised the 71 functions that had been registered. **The passing acceptance test
measured its own scope.**

Connecting `commonTest` to Android (§11b) raised discovery from 19 tests to 168 per device and the
suite died on the **2nd** one, with a hard SIGSEGV on both API levels. Then, after that fix, on the
12th:

```
PyImport_AddModule        fault addr 0xc24110e8   EmbedAPI_androidKt -> PythonTestFixture.mainGlobals
PyErr_GetRaisedException  fault addr 0xc2411160   PyException.fromCurrentError -> PyObject.getAttr
```

Both truncated pointers, which is the signature of the name-linked `@CName` fallback described
below. An unregistered call does not fail cleanly; it corrupts a pointer and kills the process.

**The real numbers:**

```
bindings.kt external fun    366
registered                   71
unregistered                295
  ...reachable from commonMain with an Android actual    68     every one a latent process kill
```

So the surface is roughly a fifth done, not finished. `docs/android-unregistered-surface.md`
carries the reachability analysis; its counts came from a script and were spot-checked, not
audited line by line.

Four are migrated so far (`PyImport_AddModule`, `PyErr_SetString`, `PyObject_SetAttrString`,
`PyObject_DelAttrString`), which moved the suite from 10 tests to 12. **Progress on this item is
measured by how far the 168 get, not by whether a chosen test passes** — that is the mistake that
closed it prematurely.

The history below is kept because the three failure modes it records are exactly what the two
crashes above are, and because §3 still has to classify whatever §2 binds.

**Was:** 39 of 380 `external fun` declarations bound through `RegisterNatives`. The rest kept the
original wiring, which does not work.

The original wiring is broken three ways, all measured or read off the code:

- arguments arrive shifted, because the `@CName` exports declare no `JNIEnv*`/`jobject` while
  ART's ordinary convention passes them (`JniWiringTest` proved this on device: sent
  `0x5A5A12345678`, received a pointer)
- 78 exports take or return a Kotlin/Native `String`, which is not a JVM `jstring`
- 182 declarations use `JNIPointer?`, a boxed `java.lang.Long` on the JVM, against a raw `jlong`

The 39 that work do so through `JNI_OnLoad` → `RegisterNatives`, binding CPython's own C
functions directly with no trampoline. Migrating the rest is mechanical **except** for one
judgement per function — see §3.

**This is not a latent problem — the production API is unusable on Android today.** Calling
`Python3.exec` crashes the process on its very first call, on both API 26 and API 36, because
the functions it reaches (`PyImport_AddModuleRef`, `PyObject_GetAttrString`, `PyRun_String`)
still take a Kotlin `String` straight across JNI. Every Android test that passes does so by
going through `bindings` directly and reconstructing the path by hand; none of them had ever
called the real object model, so nothing caught it. A benchmark added against `Python3.exec`
found it immediately.

The string-marshalling work (interning) converted three functions. The rest of the several
hundred still carry the original wiring.

## 3. Classify the remaining functions leaf vs re-entrant

**Depends on:** §2.

`@CriticalNative` and `@FastNative` both stop the collector for the call, and `@CriticalNative`
has no `JNIEnv`, so nothing under it can re-enter the runtime. A function that can execute
arbitrary Python — a module's top-level code, a `__getattr__`, a `__del__` reached by dropping
the last reference — must therefore stay on ordinary JNI.

Of the 11 migrated so far, 5 are leaves and 6 are re-entrant. `PyErr_Clear` and
`PyObject_GetAttrString` both look like leaves and are not.

The default should be ordinary JNI, with promotion only for functions audited as unable to run
Python. Guessing wrong toward ordinary costs nanoseconds; guessing wrong the other way is a
crash once upcalls exist.

## 4. Automatic reference release

**Depends on:** §1.

**Closed.** Both the explicit and automatic (GC-driven) release paths are fully functional on JVM and Kotlin/Native. The block recorded in §1 (GIL locking on parked thread) was resolved, unblocking the background cleaners.

Measurements prove that GC-driven release actually drops CPython reference counts:
- `GCLeakTest.testReferenceCountDecreasesOnGC` verifies a simple single-object wrapper lifecycle.
- `GCLeakTest.testCascadingReleaseOnGC` verifies complex nested structures: appending a target object to 1000 Python lists, then dropping the Kotlin wrappers for those outer lists. The Kotlin GC correctly collects the list wrappers, their cleaners call `Py_DecRef` on the lists, which cascades into CPython freeing the lists and decrementing the target object's reference count.

**Platform Differences:**
- **JVM (`desktopMain`)**: Uses `java.lang.ref.Cleaner`, driven by a dedicated background thread.
- **Kotlin/Native (`iosMain`, etc)**: Uses `kotlin.native.ref.createCleaner`, which runs on a dedicated worker thread, allowing asynchronous non-blocking cleanup.
- **Android (`androidMain`)**: Uses `Cleaner` where available (API 33+), with a fallback to `PhantomReference` requiring background polling on older devices. (Testing on device requires `androidInstrumentedTest` setup — see §11b).

**What fundamentally cannot be released in this architecture:**
1. **Uncaught exceptions during FFI allocation**: If Kotlin code calls a C-API function that returns a new reference (e.g., `PyObject_GetAttrString`), and a Kotlin exception disrupts the control flow *before* that raw pointer is wrapped in `PyObject(..., borrowed = false)` or explicitly `Py_DecRef`'d via a `finally` block, the CPython reference leaks permanently.
2. **Post-Finalize GC**: When `Py_Finalize()` executes, it frees CPython's heap. If Kotlin wrappers are GC'd *after* this, their cleaners see `!Python3.isInitialized` and exit early to avoid segfaults. While safe for shutdown, if the interpreter is later re-initialized via `Py_Initialize()`, those dangling wrappers will retain pointers that either point to unmapped memory or alias newly allocated CPython objects.
3. **Cross-boundary cycles**: A Python object holding an upcall proxy to a Kotlin object, which in turn holds a `PyObject` pointing back to the Python object. Neither language's GC can trace through the other, leading to a permanent leak unless broken manually or addressed via `tp_traverse` (see §7).

## 5. Composition (조립)

**Measured, on device:**

| | per-call | composed | |
|---|---|---|---|
| `getAttr`, API 36 hardware | 3929.84 ns | 713.34 ns | 5.5x |
| `list → LongArray` 1000 elems, API 36 hardware | 50065.89 ns | 4532.55 ns | 11.0x |
| `getAttr`, API 26 emulator | 2431.34 ns | 244.70 ns | 9.9x |
| `list → LongArray`, API 26 emulator | 4890.83 ns | 4113.13 ns | 1.2x |

Note what this does and does not say. Four crossings at ~7ns is 28ns, which does not explain
3929 against 713 — most of the single-call win is the `ffiAllocUtf8` malloc/copy/free round trip
disappearing, not the crossing count. The crossing count genuinely dominates only in the bulk
case, where it scales with N.

API 26 barely benefits in the bulk case because its per-element crossings run under
`@CriticalNative` and are already free there. Composition pays most where per-crossing cost is
highest, which is modern Android.

**State:** `artMain/JniExport.kt` still exports composed `Python3` functions
(`initialize`, `finalize`, `internalIsInitialized`, …) and **nothing on the JVM side calls
them** — the `external fun` declarations that used to live in `PyObject.android.kt` were
removed by `0fae961a`. Probes exist (`asmGetAttr`, `asmListToArray`, `asmExec`) and are
benchmarked, but the production path does not use composition anywhere.

Composed operations belong in the FFI layer as `expect`/`actual`, not in the object model —
`PyObject` must not reference `bindings` (see `docs/architecture.md`). Restoring composition
therefore does **not** require reopening `PyObject` as `expect`/`actual`.

`Python3.exec` was measured too, and it contradicts the reasoning above:

| | per-call | composed | |
|---|---|---|---|
| `exec("x = 1 + 1")`, API 36 emulator | 17799.46 ns | 10123.94 ns | **1.76x** |
| `exec("x = 1 + 1")`, API 26 emulator | 17798.43 ns | 9570.26 ns | **1.86x** |

Composing a realistic binder call — one that parses, compiles and executes Python — still
nearly halves it. The prediction beforehand was "a few percent", on the argument that Python's
own work would dominate. It does not: about 7.7 µs of the 17.8 µs is boundary cost.

The mechanism is visible in the numbers. 7.7 µs over ~11 crossings would be 700 ns each, two
orders of magnitude above the 7 ns a crossing actually costs. And the per-call figure is
identical on API 26 and API 36 (17798 vs 17799) despite those devices differing by 20x in
per-crossing cost. So this is not crossings — it is the three `ffiAllocUtf8` round trips, each
a JNI call plus `GetStringUTFChars` plus malloc plus copy plus free.

Which means composition is worth extending past the object model, and that **string-carrying
operations are the ones to compose first**, regardless of how much real work they do.

### Most of it can be had without composing at all

`getAttr`, measured three ways on the same device:

| | API 36 | API 26 |
|---|---|---|
| per-call (`ffiAllocUtf8`) | 2238.25 ns | 2488.62 ns |
| composed (`asmGetAttr`) | 463.60 ns — 4.83x | 243.08 ns — 10.24x |
| **direct buffer, no composition** | **425.90 ns — 5.26x** | 656.81 ns — 3.79x |

On modern Android the marshalling fix alone **beats** composition, and it needs none of the
machinery: no `artMain` export, no per-operation composed function, no JVM-side wiring. One
`DirectByteBuffer` per thread, its address taken once, and every call afterwards is encode-and-
copy inside the JVM followed by passing a `long`. It applies to every string-carrying operation
automatically rather than one at a time.

This is the same thing Panama does on desktop, which is why desktop's marshalling is ~200 ns
per string against Android's ~2500 ns. PanamaPort would have brought it too — its
`allocateFrom(String)` runs through `AndroidUnsafe.allocateMemory` → `sun.misc.Unsafe`, a JVM
intrinsic with no crossing. The earlier decision to skip PanamaPort was argued on downcall stubs
alone and missed that its memory model was the part that mattered here.

API 26 still favours composing, by roughly 2.7x. But `@CriticalNative` is already free at that
level, so old devices were never the ones paying for crossings, and they are a shrinking share.

**So §5 shrinks.** Composition stays justified only where crossing count genuinely dominates —
bulk iteration, which scales with N (11x at 1000 elements). For single string-carrying calls,
fix the marshalling instead.

### Interning settles it, on every API level

The direct-buffer path lost to composition on API 26, and the reason was not crossings — both
make exactly one ordinary-JNI call there. It was where the string gets encoded: composition
hands the jstring to `GetStringUTFChars` and encodes natively, while the direct-buffer path
encodes in Java, and API 26's ART is much worse at that.

Attacking the encode rather than the crossing:

| `getAttr`, ns | API 36 | API 26 |
|---|---|---|
| composed | 528.85 | 255.19 |
| direct buffer + `toByteArray` | 694.03 | 800.96 |
| direct buffer + ASCII fast path | 373.18 | 248.94 |
| **interned C string** | **160.25** | **167.33** |

**Interning wins on both, and wins uniformly** — 160 against 167 ns, where composition differs
by 2x between the same two devices. It is 3.3x better than composing on API 36 and 1.5x better
on API 26, so the API-26-versus-modern split disappears entirely.

That is not surprising in hindsight: attribute and module names are repeated literals, so the
encode is pure waste after the first time. CPython interns its own strings for the same reason.

The ASCII fast path alone already matches or beats composition on both levels (373 vs 528,
249 vs 255), which makes it the right fallback for strings that are not worth caching.

**Design consequence.** The `expect`/`actual` memory abstraction should be built around
`String → cached C string address`, not around a general `Arena`. A bound on the cache is
required — callers can pass arbitrary strings — with the ASCII path handling everything that
misses. `Arena`-style allocation is still needed for non-string buffers, but it is not the
centre of the API.

**This closes composition for string-carrying operations at every API level.** What remains of
§5 is bulk iteration only, where crossing count genuinely scales with N.

## 6. Composition on desktop — measured, and the answer is no

**Measured.** The same question Android answered at 1.8x, asked on desktop without building a
composed path: run `exec`'s three C calls with the C strings allocated per iteration, then with
them allocated once. The gap is what composing could remove.

| | ns |
|---|---|
| `exec("x = 1 + 1")`, strings allocated per call | 5768.04 |
| same, strings hoisted out of the loop | 5161.29 |
| **marshalling share** | **606.74 (10.5% of the call, 1.12x)** |

Against Android's 43% and 1.8x. The difference is what the two platforms do to produce a C
string: Android's `ffiAllocUtf8` is a JNI round trip — a crossing, then `GetStringUTFChars`,
then malloc, copy, free — at roughly 2.5 µs per string. Desktop's `withUtf8` is a Panama
`allocateFrom` with no crossing at all, about 200 ns per string.

So composing on desktop would buy ~10%, and cost new Kotlin/Native targets for macOS and Linux,
a packaging path for the resulting library, and a permanent asymmetry where Windows cannot
participate. **Not worth it.** This item is closed unless something changes the premise.

That cheaper win has since been taken: all 45 string-carrying wrappers moved to `invokeExact`,
so `bindings.kt` now has 310 exact calls and zero inexact ones. Marshalling went from
606.74 ns to **440.03 ns** (10.5% → 8.1% of the call). Kotlin passes the `Long` out of
`withUtf8`'s lambda as a primitive `long`, so no descriptor changes were needed.

The benchmark that produced those numbers is now `@Ignore`d. Running `exec` 200,000 times left
the JVM aborting inside `PyDict_New` and took the suite from 108 tests down to 2. Clearing the
error indicator on its failure paths recovered most of that — a pending exception was part of
it — but an abort still followed the run, and the cause is not pinned. Re-enable only after
that is understood.

Also worth recording for whoever revisits this: the Stable ABI closes the obvious bulk shortcut
regardless. `PySequence_Fast_ITEMS` is a macro reading `PyListObject->ob_item`, and `abi3t`
(3.15 free-threaded) makes `PyObject` an incomplete type, so there is no supported way to
extract list items in one call without shipping our own native code.

## 7. Python → Kotlin binder (upcalls)

**Both halves now exist, and the design premise is proven rather than argued.**

The runtime is in `reflection/` — `HandleTable` (slot plus generation, so a released handle
cannot alias onto whatever takes its slot), `UpcallTable`, `ExposedCallable`, `ObjectReference`.
The generator is `python-multiplatform-ksp/`. Fixture modules under `ksp-fixtures/` run 11 tests
against a table KSP actually generated, not a hand-written one.

**It survives a GraalVM native image**, which is the condition the whole design was chosen for:

```
PYTHON: resolved handle = 4294967296
PYTHON: invoke result = 42
PYTHON: UPCALL_OK
```

Python builds a function pointer with `ctypes`, resolves `"demo.answer"` by name through
`HandleTable`, and calls back into Kotlin — inside a closed world where runtime reflection is
forbidden. Every wall hit getting there was metadata or wiring; the lookup and invoke path itself
needed no reflection registration, because it uses none. `sample` carries the build path
(`nativeCompile`, `runNativeUpcallDemo`, Liberica NIK).

**What is not done**, and should not be read as done:

- `tp_traverse` functions are generated and tested, but nothing wires them into CPython's actual
  `tp_traverse` slot, and `tp_clear` and Kotlin-side cycle closing are untouched. Cycles are
  therefore still unsolved in practice — see `docs/object-lifetime.md`.
- Companion-object members, interfaces, enums and annotation classes are not exposed.
- The aggregator uses `Dependencies.ALL_FILES`, correct but reprocessed every build.
- No convenience Gradle plugin; user modules wire KSP per target by hand.

**Was:** entirely unimplemented — `ClassLookup.kt`, `ObjectReference.kt` and `ReflectedClass.kt`
held 1–3 lines each, and this was README's only unchecked box.

Design is settled in `docs/upcall-design.md`: build-time generated function table (runtime
reflection is impossible on Kotlin/Native and under GraalVM's closed world), blacklist exposure
(all `public`, minus an opt-out annotation), name resolved once with the handle cached in the
Python proxy's instance data, KSP running in user modules too.

**Module fragment collection is settled** and demonstrated, not inferred. Library modules
generate fragments into a well-known package; the app module's KSP finds them with
`getDeclarationsFromPackage` and emits an aggregator holding explicit references. No
`ServiceLoader`, no reflection, no `@EagerInitialization`. A three-module experiment under
`ksp-experiment/` compiles and runs, discovering fragments across a module boundary. Two caveats
carried over: `.klib` discovery on a Native target is inferred rather than tested, and
`getDeclarationsFromPackage` is `@KspExperimental`, so keep that step swappable. See
`docs/upcall-table-design.md`.

**The generator exists now.** `python-multiplatform-ksp/` is the shipped KSP processor (library
role emits a fragment, app role emits a fragment for itself then aggregates every `Fragment_*`
it can see). `ksp-fixtures/{library,app}` is the TDD harness: `GeneratedTableTest` (11 tests, JVM)
runs `UpcallTableTest`'s exact scenarios — constructor/method/getter/setter through
`HandleTable`, `@PythonInternal` exclusion, narrow `Int`/`Float` boundary widening, `tp_traverse`
field detection — against a table KSP generated rather than a hand-written fragment. `.klib`
discovery is no longer inferred: the same pair on `androidNativeArm64` found the library's
fragment from its compiled `.klib` and linked a real test binary (compiled and linked only, not
run — no device in this workspace). See `docs/upcall-table-design.md` for the full account,
including the one place the doc's own sketch was wrong (fragments must be `public`, not
`internal` — Kotlin enforces `internal` per module, and the app module compiling generated code
that references a library's fragment is a different module even inside one Gradle build).

**Tree shaking is not solved, but it is now measured.** A table referencing every `public`
declaration does defeat dead-code elimination, and that is inherent to blacklist exposure plus
static linking. 200 synthetic exposed functions cost ≈1.84 KB/entry in a stripped
`androidNativeArm64` binary (368,640 bytes total) — a floor, since the synthetic functions were
trivial one-liners and a real function's body adds its own size on top. Whether that floor plus a
real library's bodies is acceptable is a product judgement the measurement informs but does not
settle. See `docs/upcall-table-design.md` §4.

**Companion object members, interfaces, enums and annotation classes are not exposed yet.** The
generator currently walks top-level functions and `ClassKind.CLASS` declarations (their primary
constructor, member functions, and properties). `binding-policy.md`'s "companion object members
exposed as static methods" line is not implemented.

**Cycle collection: the generator's half is done.** `tp_traverse` on the Python proxy must reach
through the handle into the Kotlin object's `PyObject`-typed fields, which means the generator
emits a traverse function per exposed class alongside the call entries. That part is implemented
and tested (`FragmentScanner` detects `PyObject`-typed — including subclass-typed — fields via
`isPyObjectType`, emits `traverse = { obj, visit -> ... }`, and `ReflectedClass.hasTraverse` /
`.traverse(...)` are exercised end to end in `ksp-fixtures`). What is still open is everything
downstream of the generated function actually running during a real CPython GC pass — the proxy
type's `tp_traverse` slot wiring, `tp_clear` mutating Kotlin state, and cycles that close on the
Kotlin side — none of which this task touched. See `docs/object-lifetime.md` for the mechanism
and the three parts that are still hard.

Cost is not yet measured. The table lookup is an array index and is not the expense; the
boundary is. Note that iOS and androidNative have no boundary here at all — Python and Kotlin
share one binary — so this is a JVM-only cost.

## 7b. Finish `PyValue`

**Closed for the eager path.** `PyContext` was already complete — `withContext` restores the
strategy even on throw, and all five `ConversionStrategy` variants dispatch. `PyValue`/`PyProxy`
now is too: `toKotlin`, `toKotlinOrNull` and `toPython` are implemented, no TODO stub remains in
`PyProxy.kt`, and `ConversionTest` passes on desktop (in the 118-test run) and on the iOS
simulator (111 tests).

The lazy path is deliberately still out, because it depends on release timing and so on §1/§4.

**Was:** `PyValue`/`PyProxy` incomplete — `toKotlin()` and `toPython()` were TODO stubs ending in
`cachedNativeValue!!`, so a cache miss was an NPE.

It works today only because the basic types bypass it. `PyInt`, `PyFloat` and the rest convert
inside their own `cachedNativeValue` accessors and never reach the stub, so `TYPED` conversion
of a builtin succeeds while anything without a dedicated wrapper would fail. `ConversionTest`
covers strategy switching and the `RAW`/`NATIVE` shape difference, and does not exercise the
lazy path at all.

Filling it in also needs a lifetime rule stated per type: caching a converted native value
while releasing its source is fine, caching one that still points into Python-owned memory is
not. See `docs/object-lifetime.md`.

## 8. `jvmMain` unification

**No longer a performance item.** `invokeExact` was reached on desktop without it (1015.95 ns →
2.65 ns), so what remains is maintenance debt: 380 Android + 315 desktop hand-written
declarations, against ~350 if the 330 `actual`s lived once in `jvmMain` over 14 shape functions.

The duplication has caused real bugs — desktop's `find()` carried the symbol name separately
from the `actual`, which is how `Py_RunMain` came to be bound to `Py_FinalizeEx`.

The recipe is verified: remove `inline` from the `commonMain` `expect` (132 of 147 already carry
a compiler warning saying inlining gains nothing there), delete the two platform `actual`s, add
one in `jvmMain`. Keeping `inline` on an intermediate-source-set `expect` crashes Kotlin 2.0.20
with `Internal error in file lowering`.

## 9. Free-threading (3.15t)

**Waiting on upstream.** `abi3t` is Final for 3.15 (PEP 803); 3.14 free-threaded has no Limited
API at all, so choosing it there would mean recompiling per Python version.

Also missing: free-threaded prebuilts for Android (python.org ships GIL-only) and iOS (BeeWare
likewise). Desktop free-threaded builds do exist in `python-build-standalone`.

`pythonFreeThreaded` is already a `gradle.properties` switch; nothing else is prepared.
Verified as compatible: nothing in this codebase dereferences `PyObject`, so `abi3t` making it
an incomplete type does not break us.

## 10. WASM

**Deferred, but no longer for the reason first written here.** The original claim — that
Kotlin/Wasm cannot reach C at all, so everything must go through JS — is false, and
`wasm-experiment/` disproves both halves of it by building and running the thing:

- `@WasmImport` binds to a function exported by an Emscripten module. Kotlin called
  `emcc`-compiled `add_two` and got 42, through a real wasm import, with no JS frame.
- Emscripten built with `-sIMPORTED_MEMORY` accepts the linear memory Kotlin exports, and the
  sharing works both ways: an address from C reads back in Kotlin as the bytes C wrote, and a
  string Kotlin writes into a `malloc`'d buffer reads back through C's `strlen`. **No copying and
  no JS in the data path** — which removes the cost every other platform measured as dominant.

One constant blocks it: Kotlin emits `WasmLimits(0, null)`, a memory with no maximum, and wasm
requires a supplied memory to sit inside the importer's limits, so no Emscripten import can ever
accept it. Patching the six-byte memory section to `{min: 0, max: 32768}` makes everything link.
That belongs in a YouTrack issue, not in this design.

One structural constraint remains: Kotlin needs Emscripten's exports at instantiation and
Emscripten needs Kotlin's memory before that, and wasm imports are supplied up front, so the two
halves cannot yet be combined in one graph. The clean fix is the master-only
`importWasmMemoryInsteadOfExport`, which inverts ownership; the available fix is JS trampolines
for calls with the data path still shared, which keeps the half that matters.

A rule falls out for `wasmJsMain`: **`withScopedMemoryAllocator` must never be called.** Measured
— it grows the memory and allocates at address `0x0`, on top of Emscripten's static data.

See `docs/wasm-design.md`. What still defers §10 is that `wasmJsMain`'s `actual`s cannot be
written until §1/§4 and §7 settle, plus CPython's Emscripten build only becoming supported in
3.14 (PEP 776, Tier 3), with binaries still coming from downstream rather than python.org.

Kotlin/Native once had a `wasm32` target that could have shared CPython's linear memory; it was
deprecated in 1.8.20 and removed in 1.9.20. So the JS bridge is a consequence of the current
toolchain, not of WASM itself.

Starting now means building on a JS bridge that a future C-interop story would discard.

**Packaging is settled upstream, and constrains our build.** PEP 783 (Accepted) defines the
`pyemscripten_<year>_<patch>_wasm32` platform tag, one version per Python feature release —
`pyemscripten_2026_0` is 3.14. Any interpreter built with the specified Emscripten version and
ABI-sensitive flags (no `-pthread`, `-sWASM_BIGINT`, fixed static libs and unwinding ABI) can
claim it, so our own build and PyPI C-extension wheels are compatible goals. Match the flag set
from the first build script — retrofitting means rebuilding the interpreter. The exact flag
list still needs a manual read of Pyodide's ABI page. See `docs/wasm-design.md`.

## 11. Build wiring

`connectedDebugAndroidTest` does not force `linkAndroidNative*` or the
`copyAndroidPythonBinaries` / `copyAndroidPythonAssets` staging. A changed `.def` or a cleaned
`build/` therefore produces an APK with a stale or missing library, surfacing as
`UnsatisfiedLinkError` that reads like a code bug. This cost three debugging cycles.

The configuration-time `copy {}` that staged the *previous* build's library has been fixed
(moved into `doLast`), but the task dependencies themselves are still not declared. Until they
are, run before any instrumented test:

    ./gradlew :python-multiplatform:linkAndroidNativeArm64 :python-multiplatform:linkAndroidNativeX64 \
              :python-multiplatform:copyAndroidPythonBinaries :python-multiplatform:copyAndroidPythonAssets

## 11b. Android does not run the object-model tests

**Found while surveying coverage, and it is the largest hole in the suite.**

`commonTest` holds 111 tests over `PyObject`, `PyDict`, `PyList`, conversion, exceptions and
refcounting. They reach desktop and iOS through the source-set graph:

    jvmTest.dependsOn(commonTest)
    desktopTest.dependsOn(jvmTest)

`androidInstrumentedTest` has no such edge, so **none of the 111 run on Android**. Its entire
coverage is 19 instrumented tests — 14 of JNI wiring and benchmarks, 5 of `AssembledApiTest`.

That is the wrong platform to under-test. §2 was exactly a case of the object model being broken
on Android alone — the production API crashed on the first `Python3.exec` — and the only thing
that caught it was an acceptance test written by hand for the purpose. The 111 would very likely
have caught it earlier.

They cannot be `androidUnitTest`: the native library is not loadable there, so they have to run
instrumented. Whether `kotlin.test` annotations are discovered by the AndroidJUnit4 runner, and
how the interpreter gets initialised on device, are the two things to establish.

**Expect failures when this lands.** These tests have never run on Android; whatever they report
is the actual state of the Android object model, and that is the point of doing it.

## 12. Smaller known items

- **Download integrity**: desktop, Android and iOS archives are pinned in
  `python-checksums.properties` and verified. python.org publishes Sigstore bundles for the
  Android archives that are not checked; what verifying them would require is noted in
  `docs/python-version-acquisition.md`.
- **`PyList.subList`** returns a copy, not a live view. **`pyObjectToNative`**'s fallback branch
  is not fully native. Both are marked `TODO` and neither is exercised by current tests.
- **~50 `TODO` markers** remain in `commonMain`, including several questioning whether
  `Py_IncRef` is the right call in `PyObject.init`.
- **No CI.** The README badges point at a different repository.
- **Sample app** has not been revisited since the object model landed.
