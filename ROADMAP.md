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

That is not theoretical. `GCLeakTest` reports `cleanup actions started: 1, reached Py_DecRef: 0`
— the cleaner thread starts one release, blocks, and never processes another. **Reference
counting is structurally unable to release on any platform until this is enabled.**

Enabling it was attempted and reverted. Every C API call still outside `withGIL` becomes a
segfault far from its cause: closing the gap in `BenchmarkTest.testAttributeAccess`
(`PyImport_Import`) moved the crash from `PyImport_Import` to `_PyObject_Malloc`, with no Java
frame naming the new site.

Doing this properly needs a pass over every call site rather than a one-line change. Two things
make that cheaper than the last attempt: `ReleaseCounter` distinguishes "cleaner never ran" from
"cleaner ran and could not release", and the suites are large enough now to localise a
regression.

Note that free-threaded builds do **not** remove this requirement. Dropping the global lock
removes contention, not the rule that a thread must be attached before touching any object,
`Py_IncRef` included.

## 2. Finish the Android JNI surface

**State:** 39 of 380 `external fun` declarations are bound through `RegisterNatives`. The rest
keep the original wiring, which does not work.

The original wiring is broken three ways, all measured or read off the code:

- arguments arrive shifted, because the `@CName` exports declare no `JNIEnv*`/`jobject` while
  ART's ordinary convention passes them (`JniWiringTest` proved this on device: sent
  `0x5A5A12345678`, received a pointer)
- 78 exports take or return a Kotlin/Native `String`, which is not a JVM `jstring`
- 182 declarations use `JNIPointer?`, a boxed `java.lang.Long` on the JVM, against a raw `jlong`

The 39 that work do so through `JNI_OnLoad` → `RegisterNatives`, binding CPython's own C
functions directly with no trampoline. Migrating the rest is mechanical **except** for one
judgement per function — see §3.

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

The cleaner path itself is fixed: the cleanup action closes over the pointer value alone, so it
no longer keeps the wrapper reachable, and `createCleaner` is back on Kotlin/Native. `close()`
is idempotent and the accounting is verified by `RefCountTest` (6 tests, passing) — wrappers
take exactly the references they claim and give back exactly those.

What does not work is GC-driven release, for the reason in §1. `GCLeakTest` is left red and
states that in its assertion message.

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

**Entirely unimplemented.** `reflection/ClassLookup.kt`, `ObjectReference.kt` and
`ReflectedClass.kt` contain 1–3 lines each. README's only unchecked box.

Design is settled in `docs/upcall-design.md`: build-time generated function table (runtime
reflection is impossible on Kotlin/Native and under GraalVM's closed world), blacklist exposure
(all `public`, minus an opt-out annotation), name resolved once with the handle cached in the
Python proxy's instance data, KSP running in user modules too.

Two things are unresolved:

- **Module fragment collection on Kotlin/Native.** There is no `ServiceLoader`, and a top-level
  `object` nobody references is never initialised, so generated per-module tables would not
  register themselves. An aggregator generated in the final app module is the likely answer.
- **Tree shaking.** A table referencing every `public` declaration defeats dead-code
  elimination.

Cost is not yet measured. The table lookup is an array index and is not the expense; the
boundary is. Note that iOS and androidNative have no boundary here at all — Python and Kotlin
share one binary — so this is a JVM-only cost.

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

**Deferred deliberately.** Kotlin/Wasm cannot link C code — `wasmJs` offers only JS interop,
`wasmWasi` only WASI syscalls — and CPython's Emscripten build only becomes a supported
platform in 3.14 (PEP 776, Tier 3), with binaries still coming from downstream rather than
python.org.

Kotlin/Native once had a `wasm32` target that could have shared CPython's linear memory; it was
deprecated in 1.8.20 and removed in 1.9.20. So the JS bridge is a consequence of the current
toolchain, not of WASM itself.

Starting now means building on a JS bridge that a future C-interop story would discard.

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
