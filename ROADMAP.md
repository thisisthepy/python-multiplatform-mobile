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

**Closed.** `Python3.initialize()` parks its thread state with `PyEval_SaveThread()`, and both
suites are green with it enabled — desktop 164, iOS 157, zero failures. `GCLeakTest` passes for the
first time.

The cause was never the runtime. Several tests wrapped a pointer they had only been lent with
`borrowed = false`, so two wrappers owned one pointer and both decremented it. That double-free
corrupted CPython's free lists and surfaced later in an unrelated test. With parking off the
cleaner thread blocked forever, so the extra decrements queued and never ran — which is exactly
why turning parking on appeared to *cause* the crash. Two earlier attempts read it that way and
reverted.

**It unblocked §4, multithreaded use, and made three latent bugs visible** (a GIL-less
`PyType_FromSpec` call among them). Expect more of that shape: anything that only worked because
the main thread never let go.
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

**Closed, on the measure the section itself set.** Both emulators run 213 tests with zero
failures, and the registration surface is complete: 363 of 367 `external fun` are bound through
`RegisterNatives`, with the four exceptions deliberate — `ffiAllocUtf8`/`ffiFreeUtf8`/`ffiReadUtf8`
are hand-written with the correct `JNIEnv*`/`jclass` prologue and cannot move into C because the
buffers belong to Kotlin/Native, and `echoCriticalNamed` exists in order to be name-linked, so
registering it would delete the measurement.

Descriptor consistency is checked against the compiled bytecode rather than the Kotlin source —
`javap` on `bindings.class` joined to the table by name, zero mismatches — and there are no
duplicate wrappers or table names, which is a failure that broke cinterop once during this work.

**The history below is why this took three passes**, and it is worth keeping. The section was
closed once on `AssembledApiTest` passing, which measured its own scope: those 19 tests only
exercised the 71 functions that had been registered. Wiring `commonTest` into Android (§11b) took
discovery from 19 tests to 168 and the suite died on its 2nd, then its 12th. Two defects behind
that had been invisible because nothing on Android had ever run those tests — an unpackaged stdlib
and a `RegisterNatives` count hardcoded to 138 against a table of 145.

**Reopened once. Marking it closed the first time was wrong.**

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

**The real numbers**, recounted from the two files rather than carried forward (parse
`external fun` out of `androidMain/.../bindings.kt`, parse the `JNINativeMethod` table out of
`artMain/cinterop/jni_onload.def`, join on the method name and compare JNI signatures):

```
                          was      now
bindings.kt external fun  369      365
registered                145      187
unregistered              224      178
signature mismatches        0        0     (all 187 entries agree with their Kotlin declaration)
```

**The string half of it is finished.** Declarations that still carry a `jstring` across the
boundary went from **52 to 6**, and of those six none is a defect:

- `asmExec`, `asmGetAttr`, `testUpcallString` are registered *with* a jstring signature and their
  C wrappers use `GetStringUTFChars` — that is the composition-probe design, not the broken path
- `ffiAllocUtf8`, `ffiReadUtf8` are name-linked but hand-written in `artMain/JNIOnLoadExporter.kt`
  with the correct `JNIEnv*`/`jclass` prologue, so the convention matches
- `ffiSymbolRaw` is the one genuine leftover on the name-linked `@CName` path. It has no caller
  anywhere in `src/` — `jvmMain`'s `ffiSymbol` that wraps it is itself unreferenced — so it is a
  landmine rather than a live crash. Fixing it means a `dlsym` wrapper taking a jlong, and it
  belongs with whatever revives the shape vocabulary on Android.
  *(Done — see below. It is `ffiSymbolRawN(name: Long)` now, and the shape vocabulary went with it.)*

The 42 functions migrated in this pass are listed with their per-argument intern/scratch
judgement in `docs/marshalling-design.md`. Every symbol they bind was checked to exist in the
shipped `libpython3.14.so` before registering (`llvm-nm -D --defined-only`), and the linked
`libmultiplatform_python3.14.so` has no unresolved `Py*` symbol.

### The unregistered surface is now empty

The 178 that were left are registered. Recounted the same way, against the compiled class this
time rather than against the Kotlin source — `javap -p -s` on `build/tmp/kotlin-classes/debug/
python/native/ffi/bindings.class`, joined to the `JNINativeMethod` table on the method name:

```
                             before    after
bindings.kt external fun       369      367     (two dead declarations removed)
registered                     191      363
unregistered                   178        4
descriptor mismatches            0        0     (all 363 agree with the compiled bytecode)
non-static registrations         0        0
```

The four that remain are deliberate, and none of them is on the broken path:

- `ffiAllocUtf8`, `ffiFreeUtf8`, `ffiReadUtf8` — name-linked, but hand-written in
  `artMain/.../JNIOnLoadExporter.kt` with the correct `JNIEnv*`/`jclass` prologue and a real
  `jstring`. They cannot move into `jni_onload.def`: the buffers they allocate and read belong to
  Kotlin/Native.
- `echoCriticalNamed` — the probe that exists *to* be name-linked, so that a benchmark can compare
  name-based linking against `RegisterNatives` at the same calling convention. Registering it would
  delete the measurement.

Two declarations were removed rather than registered, both provably uncalled and both already
having a registered equivalent: `PyObject_Call` (superseded by `PyObject_CallN`) and
`PyList_GetItem` (superseded by the `PyList_GetItemRaw` / `PyList_GetItemRawF` pair).

**All 157 CPython functions in this pass were unreachable from `commonMain`/`commonTest`.** That
is not a reason it was safe to leave them — it is the finding that the earlier passes had already
covered the whole reachable surface, so what remained was landmines only. The check: take the 312
`expect fun` in `EmbedAPI.kt`, keep the 109 named anywhere in `commonMain`, `commonTest`,
`androidInstrumentedTest` or `jvmMain`, map each through its `EmbedAPI.android.kt` actual to the
`bindings.*` it calls, and intersect. The intersection with this pass is empty; the same procedure
returns `true` for `PyDict_Clear`, `PyObject_Type` and `PyList_Append`, which were registered in
the previous pass, so the procedure does find reachable functions when they exist.

`docs/android-unregistered-surface.md` is kept for the call-graph traces, but its counts and its
"71 reachable" framing are historical.

The shape vocabulary went with it. The 14 `downcall_*` trampolines and `ffiSymbolRaw` used to reach
`nativeMain`'s `@CName` exports by name, which is the same broken path and worse — a shape
function's first argument is the *target function pointer*, so a shifted argument register is a
wild call rather than a bad `PyObject*`. The indirect call is done in `jni_onload.def` now, which
removes the fallback and the hop through Kotlin/Native at once. `ffiSymbolRaw` became
`ffiSymbolRawN(name: Long)` over a `dlopen(NULL, RTLD_NOW)` handle, which is the jlong-taking
`dlsym` wrapper this section asked for above.

Verification actually run: `compileKotlinAndroidNativeArm64`, `compileDebugKotlinAndroid` and
`linkMultiplatform_python3.14DebugSharedAndroidNativeArm64` all clean; the linked
`libmultiplatform_python3.14.so` has 317 undefined `Py*` symbols and every one of them is defined
in the shipped `libpython3.13.so` (`objdump -T`); desktop 171 tests, 0 failed.

**Not yet run: the device suite.** §2's own measure is how far the 168 `commonTest` cases get on
`pmp_api26`/`pmp_api36`, and that has not been re-run since this change — another agent held the
emulators. The registration surface is complete and internally consistent; whether the suite
advances past the 12th case is unmeasured. **Do not close this item on the strength of the
consistency check alone** — that is the same mistake as closing it on `AssembledApiTest`.

Earlier in this section: four were migrated first (`PyImport_AddModule`, `PyErr_SetString`,
`PyObject_SetAttrString`, `PyObject_DelAttrString`), which moved the suite from 10 tests to 12.
**Progress on this item is measured by how far the 168 get, not by whether a chosen test passes** —
that is the mistake that closed it prematurely.

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

**Closed.** The classification is in `docs/jni-call-convention-audit.md`; the decision procedure a
new call site has to pass is in `androidMain/README.md`.

`@CriticalNative` and `@FastNative` both stop the collector for the call, and `@CriticalNative`
has no `JNIEnv`, so nothing under it can re-enter the runtime. A function that can execute
arbitrary Python — a module's top-level code, a `__getattr__`, a `__del__` reached by dropping
the last reference — must therefore stay on ordinary JNI.

**No function was promoted, and that is the finding.** The audit proposed eight promotions to
`@CriticalNative` on the argument that it is expensive below API 34 and cheap above. Measured here
it is the reverse: promoting costs **+16.54 ns on API 34 and +25.73 ns on 36**, against savings of
43-73 ns on 26-31. Three of the eight are not leaves either — `PyGILState_Release` deletes the
thread state on the last release and runs `__del__` through it, `PyThreadState_GetDict` allocates
the thread dict lazily, and `PyEval_InitThreads` is a no-op kept for the stable ABI.

The 116 registrations added since the audit (71 → 187) are **all ordinary, with no `@CriticalNative`
or `@FastNative` twin registered for any of them**, so the crash this section warns about cannot be
reached through the new surface.

One change came out of it, in the opposite direction to the audit's: `PyList_GetItem` was pinned to
`@CriticalNative` with no `preferFastNative` branch — the only binding ignoring the device axis, and
the per-element call of bulk iteration, where §5 measured 50 ns per element on API 36 against a
`@CriticalNative` net cost of 44.05 ns there. It now has a `@FastNative` twin and branches like
every other pair. Compile-verified; the device assertion
(`JniWiringTest.bothListGetItemConventionsAgree`) is written but has not been run.

The default is ordinary JNI, and the promoted set stays enumerated rather than derived: every C API
function can fail, and CPython reports failure by allocating a GC-tracked exception, so "provably
cannot run Python" is not a property any of them has. Guessing wrong toward ordinary costs 6-41 ns;
guessing wrong the other way is a crash once upcalls exist.

**Was:** depends on §2. Of the 11 migrated at that point, 5 were leaves and 6 re-entrant;
`PyErr_Clear` and `PyObject_GetAttrString` both look like leaves and are not.

## 4. Automatic reference release

**Closed.** A thousand Python lists holding a target object are dropped with no explicit
`close()`, Kotlin's collector takes the wrappers, and the target's count falls as the lists are
destroyed. Verified on both collectors — desktop through `java.lang.ref.Cleaner`, iOS through
Kotlin/Native's `createCleaner`. Android below API 33 uses the `PhantomReference` path and is not
covered yet.

**Two things still cannot be freed**: wrappers outliving `Py_Finalize()` are skipped
deliberately, leaving stale pointers if the interpreter is restarted; and cross-boundary cycles
need §7's `tp_traverse` wiring, which now exists on desktop.

**The third — the exception window — was not structural, and is closed.** "A raw pointer leaks
if an exception lands between the C call returning it and the wrapper taking ownership"
described real bugs, not a property of the architecture. `OwnershipLeakTest` forces two of them
and measures the count; both were red before the fix by exactly the number of attempts — **+50
references over 50 attempts in each case**, one lost per attempt, and no collector can reach
them because no Kotlin object ever owned them.

- `PyDict.fromMap` allocated the dict and populated it afterwards. One unhashable key makes
  `PyDict_SetItem` fail partway, and the half-built dict — by then holding a reference to every
  key and value already stored — was still a bare pointer. Measured on both the stored value and
  the already-stored key: +50 each.
- `PyType.getInstance` is handed a new reference on every call and, on a **cache hit**, dropped
  it. The comment argued this was harmless because types are immortal — true of `int` and
  `list`, false of every user-defined class, which leaked one reference per `PyObject.Type`
  read. Measured against a class defined in Python: +50 over 50 reads.

The fix is `adoptingNewReference` in `PyObject.kt` — release the reference if, and only if, the
block that was going to adopt it throws — plus `try/finally` at the sites that hold a new
reference across a second C call: `repr`, `toString`, `richCompare`, `PyType.name`,
`snapshotElements`, `PyDict.snapshotEntries`, the four `fromList`/`fromSet` scratch tuples,
`deriveTypeAndRelease` (which abandoned its scratch instance outright when `PyObject_Type`
failed), `PyException.messageOf` and `isNoneObject`. `PyException.fromExceptionInstance` now
wraps the exception instance **first**, so a failure while building the message or walking
`__context__`/`__cause__` can no longer strand the exception itself — in the one path that
exists to report failures.

One rule came out of it, and it is written on the helper: **it must never wrap a constructor
that can throw.** `PyAutoCloseable` registers the cleaner in its own constructor, before any
subclass initialiser runs, so a wrapper that throws from `init` has *already* queued a release;
releasing again would be the double free of §1. `PyType.getInstance` therefore validates before
it constructs rather than from `init`.

Only the two above are proven by a red-then-green test; the rest are windows that no current
call site can force (they need `PyUnicode_AsUTF8` or `PyTuple_GetItem` to fail on a live
object). They are fixed as structure, not as measured leaks, and are marked as such here.

**Was:** depends on §1.

**Closed.** Both the explicit and automatic (GC-driven) release paths are fully functional on JVM and Kotlin/Native. The block recorded in §1 (GIL locking on parked thread) was resolved, unblocking the background cleaners.

Measurements prove that GC-driven release actually drops CPython reference counts:
- `GCLeakTest.testReferenceCountDecreasesOnGC` verifies a simple single-object wrapper lifecycle.
- `GCLeakTest.testCascadingReleaseOnGC` verifies complex nested structures: appending a target object to 1000 Python lists, then dropping the Kotlin wrappers for those outer lists. The Kotlin GC correctly collects the list wrappers, their cleaners call `Py_DecRef` on the lists, which cascades into CPython freeing the lists and decrementing the target object's reference count.

**Platform Differences:**
- **JVM (`desktopMain`)**: Uses `java.lang.ref.Cleaner`, driven by a dedicated background thread.
- **Kotlin/Native (`iosMain`, etc)**: Uses `kotlin.native.ref.createCleaner`, which runs on a dedicated worker thread, allowing asynchronous non-blocking cleanup.
- **Android (`androidMain`)**: Uses `Cleaner` where available (API 33+), with a fallback to `PhantomReference` requiring background polling on older devices. (Testing on device requires `androidInstrumentedTest` setup — see §11b).

**What fundamentally cannot be released in this architecture:**
1. ~~**Uncaught exceptions during FFI allocation**: If Kotlin code calls a C-API function that returns a new reference (e.g., `PyObject_GetAttrString`), and a Kotlin exception disrupts the control flow *before* that raw pointer is wrapped in `PyObject(..., borrowed = false)` or explicitly `Py_DecRef`'d via a `finally` block, the CPython reference leaks permanently.~~ **Closed — see above.** This was a list of missing `finally` blocks, not a limit of the architecture.
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
The generator is `python-multiplatform-ksp/`, wired into a user module by
`python-multiplatform-gradle-plugin/` (one `id(...)`, no per-target `add("ksp<Target>", ...)`).
Fixture modules under `ksp-fixtures/` run 35 tests against a table KSP actually generated, not a
hand-written one — 29 on desktop, and 6 more from `ksp-fixtures/android`, the fixture that carries
an Android plugin (§13).

**It survives a GraalVM native image**, which is the condition the whole design was chosen for:

```
KOTLIN: table = 19 entries, 3 classes, from io_github_thisisthepy_sample
PYTHON: resolved handle = 4294967314
PYTHON: invoke result = 7
PYTHON: @PythonInternal entry resolves to -1
PYTHON: UPCALL_OK
```

Python builds a function pointer with `ctypes`, resolves a Kotlin declaration by name through
`HandleTable`, and calls back into Kotlin — inside a closed world where runtime reflection is
forbidden. Every wall hit getting there was metadata or wiring; the lookup and invoke path itself
needed no reflection registration, because it uses none. `sample` carries the build path
(`nativeCompile`, `runNativeUpcallDemo`, Liberica NIK).

**The table it resolves against is generated now, not hand-written.** `NativeImageMain.kt` used to
carry its own `FunctionTableFragment` returning a constant `42`, with a comment saying the
generator did not exist yet — and a hand-written fragment passes whether or not KSP ever runs, so
the check measured less than it appeared to. It installs `python.multiplatform.generated.FunctionTable`
— generated from `:sample`'s own sources — instead, and the number Python reads back (`7`) is one the process
produced by calling the Kotlin object seven times, so a stub returning a constant cannot satisfy
it. The `-1` line is the same check applied to `@PythonInternal`: an opted-out declaration must
not resolve.

**The metadata is generated, not maintained.** `reachability-metadata.json` was a checked-in file,
and a checked-in file rots without saying so: a native image links a `FunctionDescriptor` the
metadata does not declare, builds clean, and dies on the first call with
`MissingForeignRegistrationError`. It had already rotted — it declared one upcall descriptor while
`Panama` builds three, so `ProxyTypeFactory` (the `tp_traverse` wiring below) would have failed in
any image that reached it. `generateDesktopReachabilityMetadata` now derives the `foreign` section
from `bindings.kt`, `ShapeDowncalls.desktop.kt` and `Panama.kt`, and fails the build on a
declaration form it cannot read rather than dropping the descriptor. `ReachabilityMetadataTest`
re-checks the result against the `MethodHandle.type()`s the loaded classes actually produce, and
`runNativeUpcallDemo` now asserts on the `UPCALL_OK` marker instead of on the exit status. The
failure mode was confirmed by deliberately mis-declaring one descriptor and watching the binary
die at runtime, not argued from the documentation.

**The binary is 16.7 MB, down from 35.4 MB.** The largest single item in the image was CPython
itself: a registered resource is baked into the image heap as a `byte[]`, so the binary carried
19.4 MB of `libpython` only so that `manager.kt` could write it back out to a temporary file at
startup. It has to be on disk anyway — `PYTHONHOME` must point at a prefix with a matching stdlib
for `Py_Initialize` to get past `encodings` — so `manager.kt` loads it from that prefix when the
classpath copy is absent (`PYTHON_MULTIPLATFORM_LIBPYTHON` overrides), and the resource
registration is gone.

**What is not done**, and should not be read as done:

- `tp_traverse` functions are generated and tested, but nothing wires them into CPython's actual
  `tp_traverse` slot, and `tp_clear` and Kotlin-side cycle closing are untouched. Cycles are
  therefore still unsolved in practice — see `docs/object-lifetime.md`.
- ~~Companion-object members, interfaces, enums and annotation classes are not exposed.~~
  **Closed**, except annotation classes, which are now deliberately excluded — see below.
- ~~The aggregator uses `Dependencies.ALL_FILES`, correct but reprocessed every build.~~
  **Measured, and the aggregator turned out not to be the cause** — see below.
- ~~No convenience Gradle plugin; user modules wire KSP per target by hand.~~
  **Closed:** `python-multiplatform-gradle-plugin/`, applied by id — **except on Android**, where
  applying it is a configuration-time crash until AGP moves to 8.10. See §13; it is a version pin,
  not a plugin defect, and it affects every Android consumer rather than only the sample.

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
`ksp-fixtures/` compiles and runs, discovering fragments across a module boundary. Two caveats
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

**The declaration surface is now the whole one, and what is left out is left out on purpose.**
Companion members and `object` members become receiver-less entries under the *owner's* name
(`Owner.member`, two new `CallableKind`s: `STATIC_GETTER`/`STATIC_SETTER`); interfaces get entries
and no constructor, so a Kotlin object reaching Python through a handle is callable through the
interface even when its concrete class does not redeclare the member; an `enum class` gets one
`STATIC_GETTER` per entry plus `name`, `ordinal` and `valueOf`, and its `ReflectedClass` carries
`enumEntryNames` so the Python side can build an `enum.Enum` mirror without the boundary having to
marshal a collection. Nested declarations and top-level properties came along with it.
**Annotation classes are exposed nowhere**: applying one is a compile-time act and reading one
back needs runtime reflection, which is the single thing this design cannot have, so an instance
Python could construct would have nothing to attach to. `values()`/`entries` are out for the same
kind of reason — they return collections the boundary cannot carry.

Three latent generator bugs surfaced while widening the surface, each of which produced a
*generated file that did not compile* rather than anything the processor could detect: a cast to a
generic type without its arguments (`args[0] as kotlin.collections.List`), a constructor entry for
an abstract class, and a declaration over a type parameter (`args[0] as T`). Parameter types now
render their arguments; abstract, sealed and `inner` classes get no constructor entry; generic
declarations are not exposed at all. `data class` `copy`/`componentN` are dropped too — KSP does
report them (it does not report `equals`/`hashCode`/`toString`), and `binding-policy.md` already
said compiler-generated members stay out.

**Incremental aggregation: measured, and the aggregator was never the problem.** KSP's own dirty
set (`ksp.incremental.log=true`, `build/kspCaches/.../kspDirtySet.log`) reports **100% dirty on a
one-file change in a two-file module both before and after** narrowing the aggregator's
`Dependencies` from `ALL_FILES` to the fragment files it actually reads. The cause is one level
down: a module's own `Fragment_<module>` is legitimately an aggregating output over *every* source
file — blacklist exposure means any file can add an entry — so any change regenerates it, and KSP
then marks every file that maps to it dirty. `ALL_FILES` on the aggregator stays, because the
narrower form buys nothing measurable and its safety under a classpath-only change was never
established. The only route to real incrementality is per-file fragments (one isolating output per
source file), which changes fragment naming, `UpcallTable`'s per-module idempotency and duplicate
detection — a design change, not a tweak. Recorded in `docs/upcall-table-design.md` §11.5.

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

## 9. Free-threading

**Nothing is being waited on. It builds, it runs, and what it breaks is now known.**
`./gradlew :python-multiplatform:desktopTest -PpythonFreeThreaded=true` runs the whole desktop
suite against `cpython-3.14.7+20260807-<target>-freethreaded-install_only`:

| build | tests | failing | skipped |
|---|---|---|---|
| default (GIL) 3.14.7 | 236 | 0 | 1 |
| free-threaded 3.14.7 | 236 | 0 | 1 |

This section used to say "3.15t" and "waiting on upstream"; neither was true. 3.14 free-threaded
is what was measured here, and those artefacts have been on the python-build-standalone release
all along.

**It is now green on both.** It used to report two failures. Deferred deallocation was the first
and is fixed by the checkpoint below. `CycleCollectionTest` was the second, and it was written off
twice — first as a premise the runtime does not honour, then as a queued-decrement problem a
checkpoint would flush. Both were wrong, and it is fixed; see below and
`docs/gc-scheduling-investigation.md`.

### The flag did nothing at all until three silent defects were fixed

None of these produced an error message; all three made the build quietly do something other
than what was asked.

- **Extraction was not keyed by what it extracted.** Every unpack is guarded by "is the
  destination directory empty?", and the destination was a flat `extracted/<platform>`. Passing
  `-PpythonFreeThreaded=true` therefore downloaded the free-threaded archive, verified its
  checksum, and then *discarded* it, because the GIL build had already filled the directory. The
  build then ran the GIL interpreter while every log line named the free-threaded tarball. The
  identical hazard applied to `-PpythonVersion`. Extraction is now
  `extracted/<version>/<platform>[-freethreaded]` (`extractedDir`, `desktopFlavourSuffix`).
- **A free-threaded install renames everything looked up by name.** It ships
  `libpython3.14t.dylib`, `lib/python3.14t/` and `bin/python3.14t`, and does *not* ship the
  un-suffixed names. `manager.loadLibPython` asked for `libpython3.14.dylib`, which such an
  install does not contain. `Versions.abiFlags` / `taggedVersionString` now carry the `t`, fed by
  a new `BuildConfig.pythonFreeThreaded`.
- **`updatePythonChecksums` would have destroyed the lockfile.** It wrote `"\\n"` — a literal
  backslash and an `n`, not a newline — so the whole file came out as one physical line beginning
  with `#`, which `Properties` reads as a single comment. One run would have deleted every
  checksum and left the next build failing "Missing checksum" for all of them. The committed file
  was intact only because nobody had run the task since it was written.

### Free-threading defers deallocation to the owning thread, and a pure embedder never gets there

`GCLeakTest.testCascadingReleaseOnGC` used to fail. This is the important finding in this section,
and it is the free-threading counterpart of §1: turning the feature on exposed an assumption, not a
typo. **It now passes, unmodified**, because the library reaches the checkpoint the runtime is
waiting for. The diagnosis below stands; the last subsection records the fix.

The test builds 1000 Python lists each holding one target object, drops the Kotlin wrappers, and
waits for the cleaner to release them. Measured on the free-threaded build (the cleaner is
confirmed to have run — `ReleaseCounter.released` rose by 2003):

| after | `sys.getrefcount(target)` |
|---|---|
| 1000 lists built, each holding `target` | 1002 |
| 50 forced JVM GCs; cleaner called `Py_DecRef` 2003 times | 1002 |
| 500 further C API round trips on the owning thread | 1002 |
| 2 s of wall clock | 1002 |
| `Python3.exec("pass")` — one trivial bytecode frame | **2** |

So the decrements happened, and the objects were not freed. Not time, and not C API traffic:
entering the eval loop *once*, for a body that does nothing, released all thousand at once. That
is the signature of the free-threaded build's biased reference counting — a decref from a thread
that does not own the object cannot run `tp_dealloc` there, so the object is queued to its owner,
and the owner drains that queue at an eval-loop checkpoint.

Under the GIL there is no such queue and the cleaner's decref frees immediately. The consequence
for this library is specific and unpleasant: **an embedder that drives CPython entirely through
the C API never reaches a checkpoint, so memory released by the cleaner is never actually
reclaimed.** It is not a leak in the sense of a lost pointer — the refcount is correct and one
line of Python flushes it — but a Kotlin process that only ever calls `PyObject_Call` will grow
without bound.

### The checkpoint is the fix, and the Stable ABI does not offer a way to reach one without bytecode

The open question above was *what to call to force a drain, and from where*. Both halves have
answers now, and the first one is a "no" that had to be established before the second made sense.

**CPython exposes no non-bytecode way to merge the queue on the current thread.** The merge lives
behind `_PY_EVAL_EXPLICIT_MERGE_BIT`, and `Python/ceval_gil.c` clears that bit in exactly one
function:

```c
int _Py_HandlePending(PyThreadState *tstate) {
    ...
#ifdef Py_GIL_DISABLED
    if ((breaker & _PY_EVAL_EXPLICIT_MERGE_BIT) != 0) {
        _Py_unset_eval_breaker_bit(tstate, _PY_EVAL_EXPLICIT_MERGE_BIT);
        _Py_brc_merge_refcounts(tstate);
    }
    if (_Py_qsbr_should_process(((_PyThreadStateImpl *)tstate)->qsbr)) {
        _PyMem_ProcessDelayed(tstate);
    }
#endif
    if ((breaker & _PY_GC_SCHEDULED_BIT) != 0) { ... _Py_RunGC(tstate); }
    ...
}
```

`_Py_HandlePending` has one caller family: the `_CHECK_PERIODIC` / `_CHECK_PERIODIC_IF_NOT_YIELD_FROM`
uops in `Python/bytecodes.c`, which open every Python-level frame (`RESUME`) and close every call
instruction. There is no public entry point.

`Py_MakePendingCalls` is the obvious Stable ABI candidate and it **does not work**. Read the
source and it cannot: it forwards to `_PyEval_MakePendingCalls`, which handles `handle_signals`
and `make_pending_calls` and returns — the merge bit is not among them. Measured, on the
free-threaded build with 1000 releases queued, it returns 0 and leaves `sys.getrefcount` at
1002. `EvalCheckpointTest` asserts exactly that, so the day CPython changes its mind, the test
says so.

`PyGC_Collect` **does** work, and is the only Stable ABI function that does, because
`gc_collect_internal` stops the world and walks every thread state:

```c
_Py_FOR_EACH_TSTATE_BEGIN(interp, p) {
    _PyObject_MergePerThreadRefcounts((_PyThreadStateImpl *)p);
    merge_queued_objects((_PyThreadStateImpl *)p, state);   //  <-- the drain
}
```

That is the one option that reclaims on behalf of a thread *other* than the caller. It is also
the most expensive thing here by three orders of magnitude, because it costs a heap walk rather
than a queue pop. Both functions are now bound (`Py_MakePendingCalls`, `PyGC_Collect`); both are
in `Misc/stable_abi.toml`.

So the drain has to be an eval-loop entry, and the only question left is how cheap one can be
made. `exec("pass")` recompiles a module every time. A *cached* zero-argument Python function
whose body is `pass`, called through `PyObject_CallNoArgs`, does not — and it still reaches the
checkpoint after specialisation, because `RESUME_CHECK` deopts back to `RESUME` whenever
`eval_breaker != version`, and the merge bit makes them differ. Measured on the free-threaded
build (`EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`, macOS arm64):

| | ns/op | |
|---|---:|---|
| `withGIL { }` — attach and detach, nothing else | 154.6 | the floor |
| `Python3.drainPendingReleases()` | 283.0 | **the checkpoint, ~128 ns over the floor** |
| `withGIL { Py_MakePendingCalls() }` | 183.1 | cheap, and does not merge |
| `Python3.exec("pass")` | 6 458.9 | 23× — it recompiles |
| `withGIL { PyGC_Collect() }` | 256 447.1 | 906× — heap walk, but drains every thread |

**Where it is called from.** Not the cleaner — that was ruled out before the mechanism was known
and the measurement confirms the instinct was right for a second reason: the queue belongs to the
thread that *owns* the object, and the cleaner owns nothing, so a checkpoint taken there would run
Python on a cleaner thread (§1's deadlock) and drain an empty queue for it.
`EvalCheckpointTest.testCleanerActivityAloneTakesNoCheckpoint` pins that suppression.

It rides on the next ordinary call instead. `withGIL` takes a checkpoint at its outermost entry,
behind two gates: at most one per `Python3.autoDrainInterval` outermost scopes (32), and skipped
entirely unless `ReleaseCounter.released` has moved since the last one, so a workload that drops
no wrappers pays a field compare. Amortised that is ~4 ns on a 155 ns scope. `drainPendingReleases()`
is public for anyone who wants to force one, and `autoDrainInterval = 0` turns the automatic path
off.

The automatic path defaults **on for free-threaded builds and off otherwise**, which is why the
default suite's numbers are unchanged. One guard is worth naming: the checkpoint declines to run
when `PyErr_Occurred()` is non-null. Several call sites read the error indicator *after* their
`withPython { }` scope closes — `PyObject.getAttr` is one — and entering the eval loop can replace
it, since a pending signal handler or a finaliser raising is enough.

**This is not only a free-threading problem.** `_PY_GC_SCHEDULED_BIT` is cleared in the same
function, and `_Py_ScheduleGC` is how allocation triggers a collection on *both* builds since 3.12.
An embedder that never reaches a checkpoint therefore never runs the cyclic collector either, on
either build. That is not what §9 is about and nothing here depends on it, but it is the same root
cause, and `drainPendingReleases()` covers it for anyone who turns the automatic path on.

### Heap types are deferred-reference-counted, and a collection is what makes the count readable

This was the last free-threaded failure, and it is fixed.
`CycleCollectionTest.testHandleReleasedWhenProxyDiesWithoutCycle` had two independent defects, and
its own guard assertion caught both — the one documented as "not decoration: it is what proves the
probe reads a real refcount". It refused to proceed twice, exactly as designed.

- **The probe read eight bytes at offset 0 of `PyObject`.** Measured on 3.14.7: on the default
  build that word is `ob_refcnt` and steps with the count; free-threaded it is `ob_tid` and never
  moves, while the count is `ob_ref_local` (a `uint32` at +12) plus `ob_ref_shared >> 2` (at +16).
  The failure reported `6171668704` for both "before" and "alive" — a thread id, read as a
  refcount. The probe now branches on `BuildConfig.pythonFreeThreaded`.
- **Fixing the offset was necessary and not sufficient.** With the right fields, "before" and
  "alive" still read identically — `1152921504606846978` — because a free-threaded heap type
  carries `_PyGC_BITS_DEFERRED` and `PyType_GenericAlloc`'s `_Py_INCREF_TYPE` is a no-op for a
  deferred type on its owning thread. Measured on the proxy type: `ob_gc_bits == 0x41`
  (`TRACKED | DEFERRED`) and `ob_ref_shared == 0x3ffffffffffffffd`, a shared count of
  `PY_SSIZE_T_MAX / 8` — the deferred sentinel. Creating 100 instances left all six header words
  bit-identical.

**The conclusion this section used to draw from that is what was wrong.** It said the invariant
"each live instance of a heap type holds one reference to that type" *does not exist* on a
free-threaded build, and that no change to this library could repair the test. It does exist. It is
simply not **observable** until a collection materialises the deferred references — measured, after
one `PyGC_Collect()` the same 100 instances show up as exactly `100 << 2` on `ob_ref_shared`, and
`tp_dealloc` then gives back exactly 100, returning the total to its pre-instantiation value to the
unit. The test now takes a collection on either side of its instantiation loop, free-threaded only.
**The assertions are unchanged** — the same "rises by exactly 100" and "returns to exactly where it
started" hold on both builds.

**A checkpoint is not the fix here, and this is worth keeping straight.** The obvious guess, given
the rest of this section, is that the missing decrements were queued by biased reference counting
and that `drainPendingReleases()` would flush them. Measured: it does not. The header is
bit-identical before and after a checkpoint, because there is nothing queued to merge — the
increments were never made. Deferred reference counting and BRC queueing are different mechanisms
with different remedies, and only the second is what §9's checkpoint is for.

This also revisits what this section previously claimed about `abi3t`. The library proper is clean —
`ProxyTypeFactory` writes only into memory it obtained from `PyObject_GetTypeData` — but that test
does read inside a `PyObject`, and it is the one place that would have to change. That is a
property of using a direct probe, not of the invariant; counting through `sys.getrefcount` would
also work, at the price of the argument's own temporary reference.

The full trace, with the header dumps, is in `docs/gc-scheduling-investigation.md`. That document
also records the half of this that is **not** free-threading-specific: `_Py_ScheduleGC` only sets
`_PY_GC_SCHEDULED_BIT`, so a pure C API embedder never runs the cyclic collector on *either* build.

### What each platform can actually get

Checked against the live release listings, 2026-08-12:

| target | free-threaded prebuilt | source |
|---|---|---|
| desktop (macOS/Linux/Windows) | **yes** | `python-build-standalone` `20260807`, `…-freethreaded-install_only`, for 3.14.7 and 3.15.0rc1 alike |
| Android | no | python.org's `python-<ver>-<arch>-linux-android.tar.gz` contains `libpython3.14.so` / `libpython3.15.so` only — checked in both 3.14.7 and 3.15.0rc1 |
| iOS | no | neither BeeWare's Python-Apple-support nor python.org's XCframework defines `Py_GIL_DISABLED` |

So free-threading is a desktop-only capability for as long as that holds, and the flag should stay
off by default.

### `abi3t` is not a blocker, because nothing here asks for the Limited API

`abi3t` is Final for 3.15 (PEP 803), and 3.14 free-threaded has no Limited API at all. That was
recorded as the reason to wait. It is not one: `Py_LIMITED_API` is never defined anywhere in this
build — desktop binds symbols by name at runtime through Panama, and the native targets cinterop
against the full headers. "abi3" in this codebase means a self-imposed rule about *which*
functions to call, not a compilation mode. The rule is what keeps one binding working across
versions, and it is unaffected.

### 3.15.0rc1: the three functions are migrated, on every target

`-PpythonVersion=3.15.0rc1` gives **208 tests, 0 failures, 1 skipped** on desktop — the same as
the default. The first attempt failed 4, all of them assertions hardcoding `"3.14"` against the
reported interpreter version (`EmbedApiLowLevelTest`, `InterpreterAvailabilityTest`,
`Python3Test`, `DesktopPythonTest`). They now compare against
`Versions.currentVersion.compactVersionString`, which is strictly stronger: it checks that the
interpreter loaded is the one the build configured, instead of pinning a release line that has to
be hand-edited on every bump. (Two other `"3.14"` literals nearby are `math.pi`, left alone.)

`compileKotlinAndroidNativeArm64` and `compileKotlinIosSimulatorArm64` both fail on 3.15, with the
*same* four errors, from three functions 3.15 removed:

| removed in 3.15 | replacement |
|---|---|
| `PySys_ResetWarnOptions` | none — the `PyConfig` API covers it |
| `PyImport_ImportModuleNoBlock` | `PyImport_ImportModule` (an alias since 3.3) |
| `PyWeakref_GetObject` | `PyWeakref_GetRef` — **present in 3.14 too**, so the migration can be made without dropping 3.14 |

Why desktop does not notice: the symbols are **still exported from the shared library** — checked
with `nm` on `libpython3.15.dylib`, all three are there — and were removed only from the headers.
Panama resolves by symbol name at run time, so nothing breaks; cinterop and the hand-written C in
`jni_onload.def` compile against headers, so they do.

That made the porting cost concrete rather than open-ended: three functions, each appearing in
the `commonMain` `expect`, four platform `actual`s, `jni_onload.def` (declaration, thunk and table
entry) and the wasmJs `@WasmImport` block. Roughly 20 sites, no behavioural change on 3.14.

**Done.** All three were still present when this was picked up, and all three are now migrated:

| was | is now |
|---|---|
| `PySys_ResetWarnOptions` | removed outright — there is no C replacement, and the documented migration (configure `sys.warnoptions` through `PyConfig`, or clear it from Python) is not a binding |
| `PyImport_ImportModuleNoBlock` | kept as a `@Deprecated` **plain `commonMain` function** forwarding to `PyImport_ImportModule`, with no C symbol behind it. It had been an exact alias since 3.3, so forwarding is behaviour-preserving rather than an approximation, and existing source still compiles through the bump |
| `PyWeakref_GetObject` | replaced by `PyWeakref_GetRef`, which 3.13 and 3.14 also export, so nothing was given up to gain it |

**The out-parameter is the part that was not free.** `PyWeakref_GetRef` is
`int f(PyObject *ref, PyObject **pobj)` — a status *and* a write-through slot, where the removed
function was a single return. Three of the four targets already had somewhere to put that slot and
needed no new machinery: `jni_onload.def`'s thunk uses a C local (so the JNI boundary still carries
only primitives, per `androidMain/README.md`), cinterop uses `memScoped`, and wasmJs takes four
bytes from CPython's own heap through the `malloc`/`free` it already imports. Desktop had nothing —
Panama here reaches native memory only through `allocateUtf8String`/`readUtf8String`, neither of
which can carry a pointer, since a pointer is not NUL-terminated. So `Panama` gained
`allocatePointerSlot`/`readPointerSlot`/`freePointerSlot`, built entirely from handles the modern
backend already had (`malloc` plus the same `MemorySegment.copy` that backs `allocateUtf8Freeable`,
run in the other direction). **No new reflective lookups**, so no new JDK-version surface. This will
be reused: CPython is converting more borrowed-reference getters to the same shape
(`PyDict_GetItemRef`, `PyObject_GetOptionalAttr`).

The JDK 16-18 incubator backend throws instead. Allocating there is easy; *reading* is not, and the
class that could (`MemoryAccess`) changed shape across 16, 17 and 18. A guessed read yields a wrong
`PyObject *`, i.e. a use-after-free landing somewhere unrelated — so it refuses at the allocation
step, before anything has been allocated. That branch is already documented as an unverified
best-effort mirror, and JDK 19+ resolves the modern one.

Signature at the Kotlin level is `PyWeakref_GetRef(ref): NativePointer?` — a new strong reference,
or null. C's `0` (dead) and `-1` (error) both arrive as null because an out-parameter does not
survive the JNI boundary, but **no information is lost**: the error case sets the error indicator
and the dead case does not, so `PyErr_Occurred` separates them.

Three tests in `EmbedApiLowLevelTest` (`commonTest`, so they run on desktop, iOS and Android):
`PyWeakref_GetRef` on a live referent returns the referent *and* raises its reference count by one,
which is what distinguishes the new function from the borrowed-reference one it replaces — an
implementation still wired to `PyWeakref_GetObject` would pass an identity check and then corrupt
the heap on the caller's matching `Py_DecRef`; a dead referent returns null with a *clear* error
indicator; and the `ImportModuleNoBlock` shim resolves to the same module object as the function it
aliases.

Writing them turned up a trap worth recording: the first version built its referent from
`types.SimpleNamespace`, which is **not weakly referenceable** (nor are `int`, `str`, `tuple`,
`list` or `dict`; `set` and `frozenset` are). `PyWeakref_NewRef` answered by returning null and
raising `TypeError` — and because every class in the binary shares one interpreter, the unhandled
indicator then took down two *unrelated* tests, which is what surfaced first. The assertions now
clear the indicator before failing, so a failure in this file can no longer be mistaken for a
failure elsewhere.

Verified: `compileKotlinAndroidNativeArm64`, `compileKotlinIosSimulatorArm64`,
`compileDebugKotlinAndroid` and `compileKotlinWasmJs` all compile; `desktopTest` **236 tests,
0 failures, 1 skipped** and `iosSimulatorArm64Test` **222 tests, 0 failures** (both counted from a
cleaned `build/test-results/`). The `jni_onload.def` invariants hold — no duplicate thunk or table
name, registration count still taken with `sizeof`, and every table entry still has a matching
`external fun` and vice versa.

**The default stays 3.14.7.** A release candidate is not a default, and this migration is
version-neutral: `PyWeakref_GetRef` exists on 3.14, so none of it waits for the bump.

### iOS: the source has to change with the version, and 3.15 is the switchover

python.org began publishing an official iOS `Python.xcframework` with 3.15 —
`ftp/python/3.15.0/python-3.15.0rc1-iOS-XCframework.tar.gz`, first appearing at 3.15.0b1.
BeeWare's Python-Apple-support, the only previous source, stops at `3.14-b10` and has no 3.15
release. The two do not overlap, so this is a hard switch on the version rather than a
preference: **3.14 and earlier can only come from BeeWare, 3.15 and later only from python.org.**
`iosFromPythonOrg` in the build now picks between them at ≥ 3.15, and both archives are pinned in
`python-checksums.properties`.

Swapping is otherwise free, because the trees are layout-compatible everywhere this build reaches
into them — `Python.xcframework/<abi>/Python.framework/Headers`, `-F …/<abi>`,
`Python.xcframework/lib/pythonX.Y` and `…/<abi>/lib-arm64/pythonX.Y` all exist in both. The
differences are in parts nothing reads: BeeWare adds `platform-config/` (cross-compilation
sysconfig data for building wheels) and a `VERSIONS` file. The header-set differences
(`module.modulemap`, `lock.h`, `monitoring.h`, `typeslots.h` on one side; `pyabi.h`, `slots.h`,
`slots_generated.h` on the other) are 3.14-vs-3.15, not packaging.

Neither source publishes checksums this build can use — BeeWare publishes none at all, python.org
publishes sigstore material that is not reasonable to verify in Gradle — so both stay pinned by
the local lockfile.

The swap was verified as far as it could be while 3.15 is not the default:
`cinteropPythonIosSimulatorArm64` **succeeds** against the python.org framework, so its headers and
`Python.framework` are consumed exactly like BeeWare's. The build then failed in
`compileKotlinIosSimulatorArm64` — on the three removed functions above, with byte-identical errors
to `androidNativeArm64`, i.e. in shared `nativeMain` source and not in anything iOS-specific.
`compileKotlinIosSimulatorArm64` on the default 3.14 (BeeWare) still passes, so no regression was
introduced for the version actually in use. **That compile blocker is now gone** (see above), which
leaves the acquisition path as the thing to be sure of.

#### The acquisition path is ready; four smaller things are not

Re-read against the build on 2026-08-12. The switchover itself is wired and pinned:

* the predicate is `python-multiplatform/build.gradle.kts`, `iosFromPythonOrg` — it splits the
  configured version and compares major/minor only, so the `rc1` suffix on `3.15.0rc1` does not
  reach `toInt()` and the gate answers correctly;
* `pythonOrgReleaseDir` strips the pre-release suffix separately, because python.org publishes
  `3.15.0rc1` **under `ftp/python/3.15.0/`** while the archive keeps the full name;
* the extraction directory is keyed by version, so 3.14 and 3.15 trees cannot collide;
* `python-checksums.properties` already pins `ios-3.15.0rc1-pythonorg`, both Android ABIs, and all
  four desktop platforms at 3.15.0rc1. A plain `-PpythonVersion=3.15.0rc1` downloads nothing
  unpinned.

What a bump would still hit, none of it iOS-specific and none of it large:

1. **Free-threading has no 3.15 checksums.** There are `-freethreaded` entries for 3.14.7 only, so
   `-PpythonVersion=3.15.0rc1 -PpythonFreeThreaded=true` fails on a missing checksum. Consistent
   with the table above — 3.15 free-threading is desktop-only anyway — but it is a lockfile gap,
   not a deliberate refusal.
2. ~~**Three Android *instrumented* tests still hardcode `"3.14"`**, plus
   `PythonOnDevice.PYTHON_DIR`~~ — **fixed.** The earlier pass that moved four assertions onto
   `Versions.currentVersion.compactVersionString` covered the desktop and common tests and missed
   these, because nothing compiles them without an SDK configured. The three assertions now derive
   from `compactVersionString` and `PYTHON_DIR` from `taggedVersionString`, matching what
   `MainActivity` already did — and `taggedVersionString` additionally carries the `t` suffix for a
   free-threaded build, which the literal could not express.
   **Compile-verified only** (`compileDebugAndroidTestKotlinAndroid`); not run, because the
   emulators were reserved for other work.
3. **The two python.org paths disagree about pre-release layout.** iOS strips the suffix to build
   the ftp directory; the Android download uses the configured version verbatim
   (`ftp/python/3.15.0rc1/…`). A checksum is pinned for the Android archive, so that URL evidently
   resolves today — but only one of the two can be right in general.
4. **The iOS *app* packaging path has no producer.** `sample/build.gradle.kts` and the Xcode project
   both reference `sample/build/xcode-frameworks/Python.xcframework`, and no Gradle task anywhere
   creates it. Only the simulator *test* path is connected to the download pipeline. This predates
   3.15 and is not a version problem, but it is the reason "iOS works" should not be read as
   covering the device app.

Also stale, and cheap to correct when touched: `iosMain/README.md` still says the stdlib comes from
BeeWare unqualified, and `extractIosSimulatorStdlib`'s KDoc still says `lib/python3.13`. Neither
affects behaviour — the code is `$libVersion`-derived — and `extractIosSimulatorStdlib` does hardcode
the simulator slice name and `lib-arm64`, which is correct for the arm64 host but would need
`lib-x86_64` for an x86_64 simulator.

## 10. WASM

**There is no JS bridge, and the interpreter is running.** Every framing this section previously
carried — that Kotlin/Wasm cannot reach C, that the data path must be copied through JS, that
direct calls and shared memory cannot be had together — is disproved by running code.
`wasm-experiment/` reproduces all of it; `docs/wasm-design.md` has the detail.

**Test D — Kotlin/Wasm against CPython 3.14.2, built here for `wasm32-emscripten`:**

```
python.wasm exports  8191      Kotlin's intrinsics.memory  ==  CPython's memory
Kotlin: pyExec("answer = 6 * 7; greeting = ...")        -> 0        PASS
Kotlin: pyGlobalInt("answer")                           -> 42       PASS
Kotlin: pyGlobalString("greeting") -> "hello-from-cpython-42"       PASS
Kotlin: pyExec("bytearray(48 MiB)")   320 -> 934 pages, reads still correct   PASS
```

Kotlin wrote the Python source straight into CPython's heap and dereferenced
`PyUnicode_AsUTF8`'s `char*` out of it. Direct wasm-to-wasm calls, one linear memory, no copying
and no JS in either direction — and it survives the interpreter growing its memory by 600 pages.

What made it possible: Kotlin **2.4.20-Beta2 imports** its linear memory instead of exporting it.
That reverses the ownership behind the instantiation cycle, so Emscripten instantiates first and
Kotlin receives both the memory and the exports. The integration is one substitution in the
generated glue — `intrinsics.memory` from a placeholder `new WebAssembly.Memory({initial: 0})` to
`Module.wasmMemory`. `patch-memory-max.py` and the YouTrack issue it was going to justify are both
obsolete: the memory *import* declares no maximum, so any memory satisfies it.

**Composition is closed, the same way §6 closed it on desktop.** Reading a global from `__main__`,
measured against the real interpreter:

| | ns |
|---|---|
| naive | 259.5 |
| + interned C strings | 185.4 |
| + module/dict hoisted | **65.2** |
| one crossing (`PyErr_Occurred`, direct) | **2.9** |

75% of the naive cost comes off with pure Kotlin. What is left is CPython's own work; a composed
`pmp_getattr` could merge two calls into one and save 2.9 ns. Bulk ends the same way — 2.9 ns per
crossing against 0.7 ns per shared-memory read. **No C shim, and therefore no second build
pipeline.**

**Rules for `wasmJsMain`**, both measured:

- **`withScopedMemoryAllocator` must never be called.** It allocates from address `0x0`, on top of
  Emscripten's static data. CPython allocates; Kotlin only dereferences addresses it was handed.
- **`char*` → `String`: `CharArray` + `concatToString()` for ASCII, Emscripten's `UTF8ToString`
  otherwise. Never `ByteArray.decodeToString()` on a large buffer** — 13 ns/byte at 4 KB, ten times
  `concatToString`. The earlier claim that "string marshalling disappears" was wrong: Kotlin/Wasm
  strings *are* JS strings under the `js-string` builtins, so the JS route was never paying for the
  copy the design assumed it paid for.

**The CPython build works and does not yet claim the platform tag.** PEP 783's
`pyemscripten_2026_0` pins Emscripten **5.0.3** (Pyodide `Makefile.envs` at tag `314.0.4`), which
is what was used. The stock Tier 3 build (`Tools/wasm/emscripten`, PEP 776) already matches on
`-sWASM_BIGINT`, no `-pthread`, `-fPIC` and `MAIN_MODULE`/`SIDE_MODULE`. It diverges on the
unwinding ABI (`-fwasm-exceptions -sSUPPORT_LONGJMP=wasm` absent), on static libs (no lzma, zstd or
OpenSSL), and on `PYEMSCRIPTEN_PLATFORM_VERSION`, which does not exist anywhere in CPython 3.14.2 —
so a stock build cannot advertise the tag even where the flags line up. Also settled:
`-sMAIN_MODULE` is `LINKABLE` and exports all 8191 symbols, so **no custom `EXPORTED_FUNCTIONS`
list is needed** for the Stable ABI. Only `wasmExports` and `wasmMemory` have to be added to
`-sEXPORTED_RUNTIME_METHODS`, and neither is ABI-sensitive.

### The target is on, and `commonTest` runs on it

`wasmJs` is a real target now — a leaf directly under `commonMain`, beside `jvmMain` and
`nativeMain`. It is not a partial bring-up: **all 310 C symbols have `actual`s**, and the whole
shared object-model suite runs against a live interpreter.

```
:python-multiplatform:wasmJsNodeTest    238 tests, 0 failed, 0 skipped
                    desktopTest, unchanged    233 tests, 0 failed, 1 skipped
Embedded CPython version: 3.14.2 ... [Clang 23.0.0git]   on wasm32-emscripten
```

(190 when the target first came up, then 214 with 3 failing, then 234 with the same 3. The three
were always `GCLeakTest`'s; they pass now — see "Lifetimes" below — and the four added with them are
`WasmFinalizationTest`'s.)

Nothing skipped: `PythonTestFixture.available` was true, so `Python3.initialize()` brought CPython
up through an ordinary `@WasmImport` call to `Py_Initialize` — the library's own bring-up path,
rather than a JS-side `Py_InitializeEx` as in `wasm-experiment/`.

Three questions the experiment did not have to answer, and how they came out:

* **`Py_ssize_t` is 32-bit on wasm32**, and `EmbedAPI.kt` types it as `Long`. Fourteen functions are
  affected (`PyList_*`, `PyTuple_*`, `PyDict_Size`, `PySet_Size`, `PyObject_Size/Length`) and
  `EmbedAPI.wasmJs.kt` converts at the boundary. This is the **only** ABI divergence across the
  whole surface — and it is not a compile error, it is a `LinkError` at instantiation, so
  `bindings.kt`'s types are read off `python.wasm`'s own type section rather than transliterated
  from the `expect`s.
* **All 310 symbols really are exported**, checked against the ABI build's 8287 — which confirms the
  `-sMAIN_MODULE`/`LINKABLE` reading above on the surface this library actually needs.
* **The Gradle integration is the one substitution** the design predicted. The compiler emits
  `intrinsics: { memory: new WebAssembly.Memory({ initial: 0 }), … }` into `*.import-object.mjs`;
  a `doFirst` on the test task rewrites that to Emscripten's `wasmMemory` and stages
  `python.mjs`/`python.wasm` beside the bundle. No binary patching, no `-sIMPORTED_MEMORY`.
  *(There are two now. Upcalls need a second one, on the generated entry module — see below.)*

### Lifetimes: closed. The candidate route was measured, and it holds

The stdlib has no finalisation hook — that part was right, and re-checking
`kotlin-stdlib-wasm-js-2.4.20-Beta2.klib` still turns up no `FinalizationRegistry`, no `WeakRef` and
no `Cleaner`. **The conclusion drawn from it was wrong.** The *host* has all three, and a
`JsReference` reaches them. `registerCleaner` is built on one now, and `GCLeakTest`'s three cases
pass:

```
:python-multiplatform:wasmJsNodeTest    238 tests, 0 failed, 0 skipped
                    desktopTest          233 tests, 0 failed, 1 skipped
```

(was 234 / 3 failed — the three were `GCLeakTest`'s, and the four added are `WasmFinalizationTest`.)

**The question that decided it: is `JsReference` a strong reference?** If handing a Kotlin object to
JS pinned it, registering it with a `FinalizationRegistry` would keep it alive forever and there
would be no route at all. Measured, not reasoned:

| | measured |
|---|---|
| 200 Kotlin objects handed over with `toJsReference()`, held from JS only by a `WeakRef` and a registry entry | **`alive 0 / 200`** — not strong |
| `FinalizationRegistry` callbacks for those WasmGC objects | **200 / 200** |
| 200 real `PyObject` wrappers dropped without `close()`, judged by CPython's own refcount | **`2 → 202 → 2`** |

**The second finding cost more than the first: nothing on this platform can observe a collection
without yielding to the host.** Node 26 / V8 14.6, same object, `gc()` at each step:

```
same job, after gc()      WeakRef ALIVE     registry callbacks 0
one microtask             WeakRef ALIVE     registry callbacks 0
two microtasks            WeakRef ALIVE     registry callbacks 0
one macrotask             WeakRef CLEARED   registry callbacks 200
```

A `WeakRef` keeps its target alive for the job that created it, and the registry callback is
delivered as a task. `FinalizationRegistry.prototype.cleanupSome()` would have made it synchronous
and **has been removed from V8** — `--harmony-weak-refs-with-cleanup-some` is rejected as an
unrecognised flag, from the command line and from `v8.setFlagsFromString` alike. So the three
`GCLeakTest` cases could not have passed no matter what the mechanism was: a synchronous test body
is one job, and a job that has not ended sees nothing. That is why they read as "wasm has no
finalisation hook" — the hook was the first problem, and the harness was the second.

`commonTest` therefore drives them through `collectorTest`, which is a blocking loop on every target
whose finalisation runs on a thread and a chain of host turns on `wasmJs`. **No assertion changed.**
`kotlin-test`'s wasm adapter awaits a returned `Promise` (`TeamcityAdapterWithPromiseSupport`),
confirmed by a control that returned `Promise.reject` and duly failed; `WasmFinalizationTest` keeps a
standing `@AfterTest` control so a regression cannot silently turn those assertions into ones nobody
runs. `forceGC()` is `globalThis.gc()`, with `--expose-gc` added to the test task's `nodeJsArgs`.

Two consequences worth carrying forward:

- **`PyAutoCloseable.wasmJs.kt` is the only `js(…)` in `wasmJsMain`, and it has to be.** Every other
  declaration this target makes is a `@WasmImport`, which carries primitives only — and what must
  cross here is a *reference* to the object whose reachability is the question. The cost is confined
  to construction: `registerCleaner` + `close()` is **88.6 ns**, of which the two `toJsReference()`
  crossings are **44.1 ns** each; nothing on the C API call path touches it.
- **The callback cannot arrive inside a Python call.** JS tasks run only after the stack unwinds and
  every call into CPython here is synchronous, so the decref never re-enters the interpreter from
  within another call. That is §1's hazard, and this target does not have it.

`WasmCleanerStats` (`registered`, `released`, `finalized`, `outstanding`) is the observability half,
and stays useful on a host with no `FinalizationRegistry`, where `outstanding` is a leak count.

Kotlin/Native once had a `wasm32` target that could have shared CPython's linear memory; it was
deprecated in 1.8.20 and removed in 1.9.20. That history no longer costs anything — `@WasmImport`
plus an imported memory reaches the same place.

### Upcalls: closed. CPython calls Kotlin, and cycles are collected

`ProxyTypeFactory` builds a real `PyType_FromSpec` heap type with `tp_traverse`, `tp_clear` and
`tp_dealloc` filled by Kotlin `@WasmExport`s installed into CPython's own
`__indirect_function_table`. `wasmJsTest/.../WasmCycleCollectionTest` runs the same two cases
`desktopTest/CycleCollectionTest` does, and both pass: a Kotlin↔Python cycle is broken by
`gc.collect()`, and 100 proxies that die *without* a cycle release their `HandleTable` entries and
leave the heap type's own refcount exactly where it started.

The measurement this section already had — 3.1 ns through `call_indirect`, against 10.9 ns for the
`addFunction`-and-JS-closure route §5 specified — was the mechanism. What this pass added was the
wiring, and it turned up three constraints worth carrying forward:

- **`@WasmExport` is honoured only in the compilation that produces the `.wasm`.** Measured, not
  inferred: the identical annotation on the identical function exports from `wasmJsTest` and does
  not from `wasmJsMain`, whose klib is linked in — the test binary's export section came out with
  `startUnitTests` in it and nothing else. **A library cannot export its own trampolines.** The
  executable module must declare three delegating lines; `wasmJsTest/.../ProxyTypeExports.kt` is
  that file, kept in the suite precisely so the tests exercise the path an application takes.
  Generating it from `python-multiplatform-gradle-plugin` is the obvious next step and is not done.
- **Registration needs the Kotlin instance's raw exports, and only the generated entry module has
  them.** `cpython.mjs` cannot fetch them: it is imported *by* Kotlin's import object, so importing
  the entry module back would be an ES cycle across a top-level await. So `build.gradle.kts` appends
  `pmpSetKotlinExports(exports)` to the entry module. This target now has **two** generated-file
  substitutions, not the one this section used to advertise.
- **Kotlin/Wasm has no `call_indirect`,** so calling a C function *pointer* — `visitproc`, `tp_free`
  — goes through `WebAssembly.Table.get` in JS. Both are cold (a cyclic collection, a deallocation);
  neither the downcall path nor CPython's path *into* Kotlin touches JavaScript.

Also recorded because it cost time: `Module.UTF8ToString` is not available. This build exports
`wasmExports` and `wasmMemory` and nothing else from the Emscripten runtime, deliberately, so any
other helper fails as `undefined is not a function` inside a wasm import and arrives in Kotlin as an
empty `JsException`.

### Interning: taken, and the answer here is not Android's

All 59 string-argument positions across 49 `actual`s go through `Wasm.internedUtf8` or
`Wasm.scratchUtf8` now, with the per-argument judgement identical to `desktopMain`'s and
`androidMain`'s on all 59. Measured at 200 000 iterations:

| ns per argument | 7-char name | 54-char name |
|---|---|---|
| `malloc` + `encodeToByteArray` + copy + `free` (what it did) | **82.7** | |
| `malloc` + `free` alone | 13.0 | |
| `encodeToByteArray` alone | 59.2 | |
| `Wasm.scratchUtf8` | **30.6** | **157.0** |
| `Wasm.internedUtf8` (hit) | **22.2** | **26.4** |

`PyObject_GetAttrString` through the `actual`, both rows back to back in one run so that only the
marshalling differs: **258.0 → 229.2 ns**.

**The rule transferred; the reasoning did not, and neither did the size of the win.** Three
findings:

- The expensive part was the **intermediate WasmGC `ByteArray`**, not the encode. Writing UTF-8
  straight into linear memory is what takes 82.7 ns to 30.6. Shared memory is what makes that
  possible — the destination is CPython's own heap, so there is no staging buffer — which is the
  *opposite* of the original design note's claim that shared memory removes a string copy. It
  removes the allocation.
- **Interning's whole value is in the string length.** For a 7-character name it beats scratch by
  8 ns, nothing like Android's 15x (2238 → 148). For a 54-character one it beats it by **6x**
  (157.0 → 26.4), because a cache hit is flat in the length and an encode is linear. Reporting only
  the short case — which is what the first measurement here did — would have argued interning is
  barely worth having.
- Negative result worth keeping: `BenchmarkTest`'s shared `PyObject_GetAttrString` row **did not
  move** across this change (376.6 → 384.0 ns). That is two whole suite runs compared against a
  ~29 ns per-call difference; it is not a usable A/B at this size, in either direction. The in-run
  rows are the measurement.

### `Py_ssize_t`: two guards, because the two halves fail differently

The declaration half — `bindings.kt` against `python.wasm` — is derived now rather than remembered.
`./gradlew :python-multiplatform:verifyWasmAbiSignatures` parses the wasm binary's type, import,
function and export sections and compares all 316 `@WasmImport` declarations against it, plus the 3
glue functions against `cpython.mjs`. It runs before `wasmJsNodeTest`. Confirmed by deliberately
mis-declaring one and watching it fail with the function named:

```
PyList_Size: bindings.kt declares (i32) -> (i64), python.wasm has (i32) -> (i32)
```

Same shape as `generateDesktopReachabilityMetadata` (§7): read the artefact that decides, every
build, never a checked-in copy of the answer. The task also asserts that every `external fun` in the
file was parsed — the first version required an explicit return type and so silently skipped the
three `Unit`-returning declarations, which is §2's "green test measuring its own scope" again.

The conversion half links cleanly and is silently wrong, so it needed a different guard.
`EmbedAPI.wasmJs.kt` names the two directions instead of casting inline — `Int.pySsizeToLong()`
sign-extends, because -1 is the error return and the unsigned widening that is correct for pointers
here would turn it into 4294967295; `Long.toPySsize()` range-checks rather than truncating, so
`PyList_New(0x1_0000_0000)` fails instead of quietly becoming `PyList_New(0)`.
`WasmPySsizeTBoundaryTest` covers both directions and the whole `Int` range.

## 11. Build wiring

**Closed.** The dependencies are declared now, and the graph was read rather than assumed:
`./gradlew :python-multiplatform:connectedDebugAndroidTest --dry-run` plans 93 tasks, and every
staging step is in it, in the right order:

```
16, 20  linkMultiplatform_python3.14DebugSharedAndroidNativeArm64 / X64   (before preBuild at 23)
49      copyAndroidPythonAssets        (before generateDebugAndroidTestAssets 52, merge…Assets 58)
76, 81  linkAndroidNativeArm64 / X64
82      copyAndroidPythonBinaries      (before mergeDebugJniLibFolders 83, …AndroidTest… 86)
```

`:sample:assembleDebug --dry-run` pulls the same four in, so the app path is covered too, not
just the instrumented tests. The manual pre-step this section used to prescribe is no longer
needed.

Three edges do the work, all of them lazy-safe:

- `copyAndroidPythonBinaries` declares `dependsOn(linkAndroidNativeArm64, linkAndroidNativeX64,
  downloadAllPythonBuilds)`
- `tasks.configureEach` attaches it to every `merge*JniLibFolders` / `merge*NativeLibs`, and
  attaches `copyAndroidPythonAssets` to every task whose name ends in `Assets`. This is
  `configureEach`, not the `whenTaskAdded` that used to miss tasks registered later — which is
  how the stdlib went unpackaged and `Py_Initialize()` aborted.
- `preBuild.dependsOn(linkTaskProvider)` for each shared-library binary, so the `.so` is relinked
  and staged into `build/android/<type>/jniLibs/<abi>/` before AGP looks there.

The configuration-time `copy {}` that staged the *previous* build's library was fixed earlier by
moving it into `doLast`; that fix is still in place and is the only `copy {}` in the script that
is not already inside a task action (checked: lines 201, 245, 281 and 475 are all `doLast`).

**One hardcoded count of the same family was removed.** `jni_onload.def` still carried
`#define NUM_METHODS 127` after `RegisterNatives` had been switched to `sizeof(methods) /
sizeof(methods[0])`. It was dead, but it is exactly the constant that once drifted out of step
with the table and silently bound a prefix of it, so it is gone and a comment says why.

No other drifting constant or configuration-time side effect was found in
`python-multiplatform/build.gradle.kts` or `sample/build.gradle.kts`; there are no
`whenTaskAdded` uses left in the repository.

## 11b. Android does not run the object-model tests

**Closed.** `androidInstrumentedTest` depends on `commonTest` and both emulators run the full
suite: 176 tests each, where discovery used to be 19. Eight failures remain, tracked in §2.

Wiring it up immediately paid for itself — the suite died on its 2nd test, then its 12th, and the
two defects behind that (an unpackaged stdlib and a hardcoded `RegisterNatives` count) had been
invisible because nothing on Android had ever executed those tests.

**Was:** the largest hole in the suite.

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

- ~~**Download integrity**: python.org's Sigstore bundles are not checked.~~ **Done for every
  archive that publishes one.** `-PverifyPythonSignatures=true` verifies the sibling
  `<archive>.sigstore` in-process with `dev.sigstore:sigstore-java`, pinning the release manager's
  Fulcio SAN and OIDC issuer — both Android tarballs, and from 3.15 the iOS XCframework. The claim
  this entry rested on, that Sigstore verification needs an external CLI and is therefore
  unreasonable inside Gradle, was false; `docs/python-version-acquisition.md` §5 is corrected.
  Three things worth keeping:
  - **The identity is a version→signer map, not a constant.** 3.14/3.15 are `hugo@python.org` via
    GitHub; 3.12/3.13 are `thomas@python.org` via Google. A constant would keep passing on the
    series it was written for and invite loosening on any other. An unrecorded series fails hard
    rather than skipping.
  - **Two negative controls were run, not just the happy path.** Flipping one base64 character in
    the bundle's signature gave `Artifact signature was not valid`; pointing the map at the wrong
    release manager gave `No provided certificate identities matched values in certificate`. Both
    failed the build with a non-zero exit. Without the identity pin the second case would pass,
    which is the whole reason the pin is there.
  - **It is opt-in, and the SHA-256 lockfile is untouched.** `sigstore-java` drags in
    grpc-netty-shaded/protobuf/bouncycastle and needs the network for its TUF root, so a default
    build resolves none of it (measured: zero mentions, exit 0). The two gates prove different
    things — lockfile "the bytes we reviewed", Sigstore "the bytes the release manager signed" —
    and only the lockfile works offline or covers every source.

  Still uncovered, for reasons not fixable here: **python-build-standalone** publishes no sibling
  signature at all (853 assets, `SHA256SUMS` the only non-archive; provenance lives in GitHub's
  attestations API, keyed by digest rather than filename and rate-limited to 60/hour unauthenticated),
  and **BeeWare's Python-Apple-support** (iOS ≤ 3.14) publishes five tar.gz assets and nothing
  else — no checksums, no signatures, no attestations. Desktop is still checked against the
  release's own `SHA256SUMS`; for BeeWare the lockfile pin is the only instrument that exists.
  The lockfile stays keyed by version *and* flavour, so a `-freethreaded` or a 3.15 archive is a
  separate entry and cannot be silently accepted under an existing key.
- **Fixed in passing: the Android download URL 404'd on every pre-release.** It built
  `ftp/python/$version/`, but python.org publishes `3.15.0rc1` under `3.15.0/`. It stayed
  invisible because the archives were already in the download directory so the fetch was skipped;
  adding the `.sigstore` fetch surfaced it on the first run. `pythonOrgReleaseDir`, which the iOS
  task already used, is now declared above both tasks and used by both.
- ~~**`PyList.subList`** returns a copy, not a live view.~~ **Stale — it returns `PySubList`,
  which delegates `get`/`set`/`add`/`removeAt` to the backing list, i.e. it is a live view.**
  **`pyObjectToNative`**'s fallback branch is still not fully native; the open question is now
  written out at the branch itself (three candidate answers, and what to measure first).
- ~~**~50 `TODO` markers** remain in `commonMain`~~ **— triaged. 18 remained, not ~50; 15 are
  closed, 2 are sharpened open questions, and the work items are the four bullets below.** The
  `Py_IncRef`-in-`PyObject.init` question named here is answered in place: `borrowed = true` is a
  statement that the wrapper must obtain its own reference, and both directions of getting it
  wrong have now been paid for (§1's double free, §4's dropped reference, and the fixture leak
  below). Two bugs came out of the pass:
  - **`PyType.dict` handed a `mappingproxy` to `PyDict`.** `type.__dict__` is not a `dict`, and
    `PyDict_Size`/`PyDict_Items` reject a non-dict with `PyErr_BadInternalCall()` — returning
    `-1`/`NULL` *and leaving the error indicator set*. Measured: `int.__dict__` reported
    `size == -1`, and the next unrelated `Python3.eval` in the same suite died with
    `Objects/dictobject.c:4248: bad argument to internal function`. It now copies through
    `PyDict_New` + `PyDict_Update` into a real dict. `PyType_GetDict()`, which the code comment
    proposed instead, is **not** an option: it is declared in `cpython/object.h`, outside the
    Limited API, so it is not in the Stable ABI subset this binding restricts itself to.
  - **`PythonTestFixture.mainGlobals()` leaked one reference per call**, wrapping
    `PyObject_GetAttrString`'s new reference with `borrowed = true`. Measured at exactly +50 over
    50 calls — the inverse of §1's defect, in the fixture every functional test is built on.
    `OwnershipLeakTest.theTestFixtureDoesNotLeakMainGlobals` is the guard.
- **`Python3.runMain` is not usable as written**, and "add error handling" (the TODO it carried)
  understated it. `sys.argv[1] = ...` assigns to an existing index, but `Py_Initialize()` does not
  set `sys.argv`, so it raises `IndexError` — invisibly, because `PyRun_SimpleString` prints and
  clears the indicator and its return value is discarded. Worse, `Py_RunMain()` **always finalizes
  the interpreter**, so on return the runtime is gone while `Python3.isInitialized` is still
  `true`. Its `Int` exit status is also discarded. No caller in `src/` or `sample/`, so it is a
  landmine, not a live failure. Fixing it is a design decision: what should "run a module" mean
  for an embedded interpreter that has to survive the call?
- **`Python3.runApp` does nothing at all** — its only statement is commented out, as is the
  `Py_BytesMain` `expect` it would call. It returns `Unit` either way, so a caller cannot tell.
  Declaring `Py_BytesMain` is not a one-liner: it takes `(int argc, char **argv)`, so it needs an
  array-of-C-strings marshalling path, which each of the four platforms does differently.
- **`Python3.finalize` reports no error detail**, and cannot: `Py_Finalize()` returns void and
  there is no interpreter left to hold an error indicator afterwards. The one improvement
  available is `Py_FinalizeEx()`'s `int` (0, or -1 when flushing buffered data failed). Left
  undone because finalization is untested — its only caller is `artMain/JniExport.kt`, and a test
  that exercises it destroys the interpreter the rest of the suite shares.
- **`EmbedAPI.kt`'s section numbers are append order, not the C API docs' chapter order.**
  Sections 1–26 follow the docs; 27 (Type Objects), 28 (Tuple Objects) and 29 (Module Objects)
  were appended as needed. Documented target order: Type before Integer Objects (§16), Tuple
  before List Objects (§22), Module before Iterator Objects (§25). It is a ~370-line pure-comment
  move with no behavioural effect, so it should be done alone, on a quiet tree, or not at all.
- ~~**Several `commonTest` file headers still describe their subjects as `TODO` stubs "expected to
  fail with `NotImplementedError`"**~~ **— done.** The ten named here (`PyObjectTest`,
  `Python3Test`, `PyBasicTypesTest`, `PyModuleTest`, `PyDictTest`, `PyIteratorTest`, `PySetTest`,
  `PyTupleTest`, `PyListTest`, `ConversionTest`) now say what they actually cover and that a
  failure is real. A sweep of the *rest* of the test tree found five more of the same shape, which
  this entry had not counted:
  - `PyExceptionTest` claimed the exec/eval sites "do not yet call `PyException.fromCurrentError`"
    and that its `type`-asserting cases were expected to fail. They all route through
    `pyErrorOrGeneric`, and `Python3.exec` is built on `PyRun_String` specifically so the error
    indicator survives to be read — the opposite of what the header said.
  - `RefCountTest` said `GCLeakTest` was "blocked by the GIL still being held by the initialising
    thread". §1 is closed; `initialize()` parks with `PyEval_SaveThread()` and `GCLeakTest` passes.
  - `PythonTestFixture` named "CPython 3.13"; `pythonVersion` is 3.14.7 and the fixture is
    version-agnostic, so it no longer names one.
  - `DesktopOverheadBenchmark` (desktopTest) said desktop "currently reaches Panama through
    `MethodHandle.invoke` rather than `invokeExact`". That migration is done and this benchmark is
    what measured it — 1015.95 ns → 2.65 ns.
  - `ConversionTest`'s inline `// Once implemented: RAW should hand back a PyObject/pointer-ish
    value, NATIVE a Kotlin List` described the shipped behaviour as future work.

  Headers checked and left alone because they were already accurate: `HandleTableTest` and
  `UpcallTableTest` carry red-phase *notes* that explicitly distinguish "failed to compile before
  the implementation existed" from a regression, which is still the right thing to tell a reader.
  Comment-only throughout: desktop stayed at 236 tests, 0 failures, 1 skipped.
- **No CI.** The README badges point at a different repository.
- ~~**Sample app** has not been revisited since the object model landed.~~ **Done — see §13.**

## 13. The sample, and the AGP version that shapes it

The sample now shows four things, each on the real API: the embedded interpreter (`Python3.version`
against `currentPlatform`), a Kotlin-built `PyList` of `PyInt` published into `__main__` and
evaluated by Python, a Python-side `ctypes` call into a Kotlin declaration resolved by name, and
the contents of the generated table read back off `UpcallTable`. Measured on `:sample:run`:

```
runtime : 3.14.7  ·  sys.platform=darwin  ·  MacOS 26.5.1 (aarch64) / JVM 21.0.12
eval    : sum(kotlin_numbers) * 2 -> int: 56
table   : 19 entries, 3 classes, from io_github_thisisthepy_sample
upcall  : Python called Kotlin through handle 4294967314 and got 0
```

and, since the AGP bump below, the same four sections on Android — observed on `pmp_api36` and on
`pmp_api26`, which is `minSdk`:

```
1  3.14.7  ·  sys.platform=android  ·  Android 16 (SDK 36, aarch64) / ART VM 0.9
3  table hit: handle 4294967317 -> 3   (Kotlin-side call; the boundary shim is desktop-only today)
4  22 entries, 4 classes, from io_github_thisisthepy_sample
   @PythonInternal held: the annotated member is absent from the table
```

Twenty-two rather than desktop's nineteen because the Android compilation also scans
`MainActivity`; the module name is the same one, since it is one module now.

`:sample:run` did not work before this and it was not the sample's fault twice over: the task had
no `PYTHONHOME`, so `Py_Initialize` could not find `encodings`, and a project dependency resolves
to class directories rather than to `desktopJar`, so `manager.loadLibPython` found no bundled
`libpython` either. Both are wired in `sample/build.gradle.kts` now, the second by falling back to
`$PYTHONHOME/lib` — which is the fallback `manager.kt` already had, it just had nowhere to look.

**What was there before:** the Compose template screen (a button, an image, the platform name),
and a `desktopMain` `main()` doing raw `PyLong_FromLongLong`/`PyRun_SimpleString` with a pointer
round-tripped through a `Double`. It compiled. Nothing in it touched the object model, and
`PyRun_SimpleString` is the call `Python3.exec` exists to avoid (it calls `PyErr_Print`, which
clears the error indicator before anything can read it).

### The convenience plugin could not be applied to an Android module — closed

This was the finding, and it was a property of the repo rather than of the sample.

```
java.lang.NoSuchMethodError: 'void com.android.build.api.variant
    .AndroidComponentsExtension.addKspConfigurations(boolean)'
  at com.google.devtools.ksp.gradle.KspConfigurations$3$1.execute(KspConfigurations.kt:114)
```

KSP 2.3.11 declares `MINIMUM_SUPPORTED_AGP_VERSION = 8.10.0` (read off its `agpUtils` class), and
this build pinned AGP 8.5.2, whose `AndroidComponentsExtension` has no such method (`javap` on
`gradle-api-8.5.2.jar`). So `id("io.github.thisisthepy.python.multiplatform.bindings")` — which
applies `com.google.devtools.ksp` — died at configuration time in any module carrying an Android
plugin, which is **every Android consumer of the plugin, not just this sample.** `ksp-fixtures`
never hit it because neither fixture module applied an Android plugin: the fixtures were verifying
their own shape rather than the plugin's advertised one, the same failure mode §2's
`AssembledApiTest` had.

**Two versions moved, and nothing else had to.**

| | was | now | why |
|---|---|---|---|
| AGP | 8.5.2 | **8.10.1** | KSP's declared minimum is 8.10.0 |
| Gradle | 8.9 | **8.11.1** | AGP 8.10's own minimum |

Kotlin (2.4.20-Beta2), KSP (2.3.11) and the Compose Multiplatform plugin (1.6.11) are untouched —
the bump needed no chain beyond those two. `python-multiplatform`'s Android wiring is the part
that was expected to complain, since it hangs hand-written `Copy` tasks, a `tasks.configureEach`
name match on `merge*JniLibFolders`/`*NativeLibs`, and a `preBuild.dependsOn(linkTaskProvider)`
off AGP internals — none of it needed changing. `compileSdk` stays at 34.

`:sample` applies the bindings plugin directly now and `:sample-bindings` is gone; its sources moved
back under `sample/src/*/kotlin/.../demo/bindings/`. The Android `UpcallDemo` actual, which used to
answer "unavailable on Android" to every member, is an ordinary one-line delegation like the others.

Two things the fold-back exposed that the split had hidden:

- **`androidMain` can name the generated table; `iosMain` cannot.** `androidMain` *is* the Android
  target's source set, compiled together with each variant's KSP output, so its `actual` names
  `FunctionTable` directly. `iosMain` is an intermediate source set the generating leaves depend
  on, so it still needs one `actual` per leaf. Same processor, opposite answer, and the difference
  is which side of the compilation the source set sits on. (Both `actual`s are generated now —
  see "Two smaller things the sample found" below — but the asymmetry that made them necessary is
  unchanged.)
- **Exposure is a blacklist, so applying the plugin offers the whole module to Python — including
  Compose.** A `@Composable` may only be called from another composable and a generated entry is an
  ordinary lambda, so a scanned `@Composable` is a compile failure of *generated* code. `:sample`
  therefore sets `excludePackages` to keep `...demo.ui` out. A separate module made this invisible;
  any real app has UI in the same module as its bindings.

### `ksp-fixtures/android`, and the second defect it found immediately

`ksp-fixtures/android` is the fixture that carries `com.android.library` — the one thing neither
existing fixture does. It applies the bindings plugin, exposes the same declaration shapes the
desktop fixture uses, and runs 6 tests against the generated `FunctionTable` as a plain JVM unit
test (`testDebugUnitTest`, no device). If the AGP/KSP pair ever drifts apart again it stops
configuring, which is the failure worth having.

It earned itself on the first run, by failing for an unrelated reason:

```
6 tests completed, 5 failed
```

The plugin decided which KSP configurations to put the processor on with `name.endsWith("Test")`.
That holds for Kotlin target names (`kspDesktopTest`), but **AGP names its source sets with the
build type last**:

```
main          kspAndroid              kspAndroidDebug              kspAndroidRelease
unit test     kspAndroidTest          kspAndroidTestDebug          kspAndroidTestRelease
instrumented  kspAndroidAndroidTest   kspAndroidAndroidTestDebug   kspAndroidAndroidTestRelease
testFixtures  kspAndroidTestFixtures  kspAndroidTestFixturesDebug  kspAndroidTestFixturesRelease
```

so the suffix check caught three of those nine. The processor ran over the *test* sources on
`kspAndroidTestDebug` and emitted a second `Fragment_<module>` and a second `FunctionTable` into
the test compilation, where they shadowed the real ones from `main` — a compilation's own
generated sources win over its classpath. The table the test read held the test class's own
members and nothing the module exposes.

This is exactly the duplicate-fragment hazard the plugin's own doc comment describes, arriving
through a name shape that comment did not anticipate. `Test` is now matched as a camel-case *word*
(`(?:^|[a-z0-9])Test(?:[A-Z]|$)`), not as a suffix, so a flavour or target named `testing` still
keeps its processor. `WiringTest` pins the full observed Android configuration list.

**Nothing without an Android plugin could have found this**, which is the point of the fixture.

### ~~Two smaller things the sample found~~ — both closed

Neither existing fixture had the *shape* to catch either one, which is the part worth keeping.
`ksp-fixtures` grew that shape first, and both defects reproduced there before anything was fixed.

#### The generated table is reachable only from the source set of the target that generated it

KSP writes `FunctionTable` into `iosSimulatorArm64Main` and its siblings, so `iosMain` — which
those leaves depend on — cannot name it, exactly as `commonMain` cannot. The rule is about
*intermediate* source sets, not about "not being `commonMain`": `androidMain` is the Android
target's own source set and names `FunctionTable` directly, which the fold-back above made visible.

**The constraint has not moved and cannot.** A fragment scan has to see the target's own
declarations — Android's table is three entries larger than desktop's because that compilation also
scans `MainActivity` — so generating into `commonMain` through `kspCommonMainMetadata`, the usual
KMP answer, would shrink the exposed surface to whatever happens to be common. What moved is *who
writes the `actual`*:

```kotlin
// commonMain, and nowhere else in the repository
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()
```

`python-multiplatform-ksp` finds the annotated `expect` — a leaf compilation sees its whole source
set closure, the same fact that lets the fragment scanner pick up common declarations — and emits
one `actual` per leaf, carrying `@PythonInternal` so the generated function does not itself end up
in the table. `sample` lost its three identical `InstallTable.ios*.kt` files and gained nothing in
their place. Android's hand-written `actual` is gone too, not because Android needed one but
because a per-platform special case is precisely what shared code cannot be written against.

`expect`/`actual` rather than a common interface plus a runtime registry because the reference
chain has to stay static: §7 keeps every fragment reachable to the Kotlin/Native linker by naming
it explicitly, with no `ServiceLoader` and no `@EagerInitialization`, and a generated object that
nothing names is dead code the linker may drop. The generated `actual` is that name.

`ksp-fixtures/app` gained a second Native leaf (`androidNativeX64`) purely so the default hierarchy
would give it an intermediate `androidNativeMain` — `iosMain`'s shape, without Xcode — plus a
`commonMain` that installs the table. Written the obvious way first, it failed exactly as the
sample did:

```
e: ksp-fixtures/app/src/commonMain/kotlin/fixture/app/TableInstall.kt:4:39
    Unresolved reference 'FunctionTable'.
```

Neither existing fixture could have produced that: both reached the table from `desktopTest`, a
*leaf* compilation, where it resolves fine. `ksp-fixtures/android` carries the same seam, because
KSP runs once per AGP *variant* there and that is where a generated `actual` could go missing or
land twice.

#### A `var` with a `private set` generated a fragment that did not compile

`FragmentScanner` decided on `property.isMutable` alone and emitted a `STATIC_SETTER`/`SETTER`
assigning to it. The ROADMAP recorded this read off the scanner rather than observed; putting the
shape into `ksp-fixtures/library` produced it on the first run:

```
e: Fragment_..._library.kt:58  Cannot access 'topLevelPrivateSet': it is private in file.
e: Fragment_..._library.kt:442 Cannot access 'privateSet': it is private in 'RestrictedSetters'.
e: Fragment_..._library.kt:458 Cannot access 'protectedSet': it is protected in 'RestrictedSetters'.
```

Three errors from four properties, and **the fourth is the one that matters.** `internal set`
compiled: the fragment is generated into the same compilation as the sources it scans and
`internal` is enforced per Kotlin *module*, so the assignment is legal there — and only there.
Fixing what the build complained about would have left a table entry describing a write no
consuming module could perform. `BindingPolicy.isExposedSetter` therefore reads the *setter's*
modifiers and treats `private`, `protected` and `internal` alike. The read side is untouched: a
`var` with a restricted setter is a read-only property to Python, which is what it already is to
every Kotlin caller outside its module.

### ~~What the sample still cannot show~~ — the trampoline exists now

**What it was.** `UpcallStub` was desktop-only and both its stubs were `(long) -> long`, so the
entry the demo called took no arguments and returned a `Long`. The *table* had carried arity and
per-argument `TypeTag`s from the day it was written; nothing read them. Python could call Kotlin
and could not pass it anything.

**What it is.** `python.multiplatform.ffi.upcall.UpcallTrampoline` marshals both directions and is
`commonMain` — the argument handling was never platform-specific, only the address publishing was.
Desktop reaches it through one new Panama stub. Python now calls Kotlin with real arguments of
every marshalled tag, constructs a Kotlin object and calls a method on it, and gets a Python
exception when the Kotlin side raises: `python.native.ffi.UpcallArgumentsTest`, which drives the
whole path from inside the interpreter, and `UpcallTrampolineTest` (commonTest, so it compiles for
every target) which drives the marshaller directly. Desktop: 233 tests, 0 failed.

Three things the work settled, each recorded in `docs/upcall-design.md`:

- **One shape, not a family.** Argument passing needed exactly one new C shape,
  `(long, long) -> long`. Arity and types ride in the tuple and in the table entry, never in the C
  signature, so the stub count does not grow with the exposed surface. It is also, exactly,
  `PyCFunction` — so the generated proxy type will not need a new shape either. Every other CPython
  slot shape was already in `Panama`'s vocabulary.
- **`ctypes.CFUNCTYPE` releases the GIL.** The first version segfaulted in `_PyThreadState_GET`
  (`PyErr_Occurred+0x1c`) on a thread whose own `withGIL` depth counter said it held the GIL. That
  counter records scopes *Kotlin* opened, and C is free to have dropped the GIL inside one. An
  entry point reached from C must take its own `PyGILState_Ensure` unconditionally — which applies
  to every platform's entry point, not only this one.
- **`bytes` has no fast route in this ABI subset.** `PyBytes_AsString` is bound as a
  NUL-terminated UTF-8 *string* read, which destroys exactly the payloads `ByteArray` exists for,
  and `PyBytes_AsStringAndSize` is in no platform's `EmbedAPI`. The trampoline goes an item at a
  time — correct, and the slowest path across this boundary by a wide margin.

**iOS and androidNative have it too.** `python.native.ffi.UpcallEntry` (`nativeMain`, so both
targets get it at once) publishes the same trampoline, and `UpcallEntryTest` (`nativeTest`) drives
it from inside the interpreter: iOS simulator 230 tests, 0 failed, 8 of them new; androidNative
compiles main and test. Desktop is unchanged at 236, 0 failed, 1 skipped.

It is *not* the `@CName` + `ctypes.CDLL(None)` route this document and `docs/upcall-design.md`
both predicted, and the reason is two independent measurements rather than a preference:

- `@CName` symbols are exported from the androidNative `.so` (`T` in `nm -D`) and **are not
  present at all** in the iOS framework or in either target's test executable — not stripped at
  link time, never emitted.
- This project's iOS `Python.framework` carries **no `_ctypes`** (no `lib-dynload`, and its
  `PyInit_*` exports stop at `time`), so `import ctypes` raises there regardless.

What it uses instead is a real `PyMethodDef` whose `ml_meth` is a `staticCFunction` and whose
`self` carries the callable handle — i.e. the `PyCFunction` slot the shape was chosen to be, and
what the generated proxy type installs anyway. The desktop `ctypes` shim was standing in for
exactly this. The `@CName` functions are kept: they are the C-host entry point, they are what
`ctypes` reaches on Android, and `UpcallEntry.invokeAddress` hands out their addresses regardless
of what the linker did with the name.

`nativeTest` had to be wired into the source-set hierarchy for that test to exist — the directory
was there and nothing pointed at it, so its one file was dead source and had been copied
byte-for-byte into all five native target test source sets. Those copies are gone.

**What is still open.** Android (JVM/ART) — a `RegisterNatives` method behind a `PyCFunction`
shim — and wasm, a `@WasmExport` plus `Table.set` (3.1 ns, measured in §11). Per-platform detail
is in `docs/upcall-design.md`'s "What each platform still owes". The generated proxy type that
would let Python write `obj.method(x)` instead of going through `_pm_bind` is §7's remaining half.
