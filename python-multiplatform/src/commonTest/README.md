# Which tests run where, and why

Test placement in this module determines coverage: a test in `commonTest` runs on every target,
a test in a platform source set runs only there. That placement has drifted from "what is actually
platform-independent" before -- `PythonProxySource.install()` was exercised only in `desktopTest`
until the generated proxies were driven from the sample and found not to install on iOS/androidNative
at all (`9040a8ce`), and `GCLeakTest` used to be duplicated across five source sets because
`src/nativeTest` existed on disk but was never wired into the source-set hierarchy. This file
records the audit that followed: what runs on which of the six execution paths, why each
platform-only file actually needs to be there, and one promotion that was tried and reverted.

## The six execution paths and how they're wired

| Execution path | Gradle task | Source-set chain |
|---|---|---|
| desktop | `:python-multiplatform:desktopTest` | `desktopTest -> jvmTest -> commonTest` |
| desktop, free-threaded | same task, `-PpythonFreeThreaded=true` | same chain, linked against `libpython3.14t` instead of `libpython3.14` |
| iOS simulator | `:python-multiplatform:iosSimulatorArm64Test` | `iosSimulatorArm64Test -> nativeTest -> commonTest` |
| androidNative | `:python-multiplatform:androidNative{Arm64,X64}Test` (custom `adb`-driving task; `compileTestKotlinAndroidNativeArm64` for a device-free check) | `androidNative*Test -> artTest -> nativeTest -> commonTest` |
| Android (ART) | `connectedAndroidTest` (instrumented, needs a device/emulator) | `androidInstrumentedTest -> jvmTest -> commonTest` |
| Android (host JVM) | `testDebugUnitTest` / `testReleaseUnitTest` | `androidUnitTest -> commonTest` (default KMP wiring; does **not** go through `jvmTest`) |

Two things fall out of this that are easy to get backwards:

- **`jvmTest` has no files of its own.** It exists only to give `desktopTest` and
  `androidInstrumentedTest` a shared ancestor below `commonTest`. Anything placed there would run
  on both, but nothing currently is (see "not done" below for why `StringMarshallingTest` isn't).
- **`androidUnitTest` and `androidInstrumentedTest` are two separate compilations of `commonTest`**,
  not one target with two names. `androidUnitTest` runs on the host JVM, which cannot load the
  arm64/x86_64 `.so`, so the interpreter itself is unreachable there -- it exists to satisfy the
  `expect`/`actual` surface (`forceGC()` has an `actual` in both), not to run the object-model
  suite. `androidInstrumentedTest` is the one that reaches a real (or emulated) CPython, needs a
  connected device, and is out of scope for this pass per the task constraints.
- **`nativeTest` was not wired into this hierarchy until `9040a8ce`.** Everything below that used
  to have `iosSimulatorArm64Test`, `androidNativeX64Test` and `androidNativeArm64Test` sourced
  independently, each with its own copy of anything that needed an `actual`.

## Survey: what's in each source set

`commonTest` (runs on all six paths) holds the entire object-model suite: `Python3Test`,
`PyObjectTest`, `PyTypeTest`, every `types/*` test, `GCLeakTest`, `RefCountTest`,
`EvalCheckpointTest`, `OwnershipLeakTest`, the upcall/reflection suites
(`UpcallTrampolineTest`, `PendingCallTest`, `PythonProxySourceTest`, `HandleTableTest`,
`UpcallTableTest`), `GilParkingTest`, `EmbedApiLowLevelTest`, `UpcallBoundaryCostTest`,
`UpcallEntryTest`, the `overhead/Benchmark*` measurement harness, `PlatformIdentityTest`,
`PyValueLazyConversionTest`, `SmokeTest`, `VersionsTest`. This is the baseline the task's
341/303/316 numbers are built from.

Platform-only source sets, and why each file actually has to be there (not "happens to be"):

### `desktopTest` (16 files) -- audited file by file, none are commonTest candidates

| File | Why it cannot move |
|---|---|
| `AsyncCompletionProbeTest` | Its own KDoc: "Desktop only... wasm has no threads at all and this test could not be written for it in any form." Uses `java.lang.Thread`, `System.nanoTime()`. |
| `AsyncUpcallCancellationTest`, `AsyncUpcallDeliveryTest`, `AsyncUpcallEarlyCancellationTest` | `java.util.concurrent.{LinkedBlockingQueue,TimeUnit}` + `java.lang.Thread`. Each has a `nativeTest` counterpart (`AsyncUpcallNative*Test`) built on `NativeThread.kt` instead -- an intentional per-platform mirror, not an oversight. The native mirrors currently cover fewer cases (1 test each vs. 3 on desktop); that gap is real but is new test-writing, not a placement fix. |
| `PythonProxyInstallTest` | Same JVM concurrency primitives as the async tests above (`LinkedBlockingQueue`, `Thread`). No native/wasm mirror exists yet -- this is the test that would have caught `9040a8ce`'s bug had it existed sooner. Writing one needs `NativeThread`-based equivalents of `installLoopHarness`/`runOnLoopThread`; out of scope here (new tests, not a move). |
| `DesktopOnlyLeakTest.desktop.kt` | `java.lang.ref.Cleaner` -- JVM-only API, name says so. |
| `GCLeakTest.desktop.kt` | The `actual` half of `commonTest`'s `expect fun forceGC()` for this target. Platform glue, not a test. |
| `FreeThreadedGCGateTest`, `GCSchedulingMeasurementTest` | Pin CPython's GC-scheduling gates against **JVM process memory** specifically (`gc_should_collect_mem_usage` reads `task_info(TASK_VM_INFO)`, which tracks the JVM's footprint in this embedder). `GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint` additionally calls `python.native.ffi.UpcallStub`, which only exists in `desktopMain`. |
| `CycleCollectionTest` | `sun.misc.Unsafe` via reflection (`Class.forName("sun.misc.Unsafe")`), `Class.forName("python.native.ffi.EmbedAPI_desktopKt")`. Has its own native mirror in `iosSimulatorArm64Test` (see below) and an ART mirror in `androidInstrumentedTest`. |
| `DesktopOverheadBenchmark` | Explicitly "the desktop half of the JNI/Panama overhead comparison," mirrored against Android's `JniOverheadBenchmark`. |
| `DesktopPythonTest` | Not platform-coupled by API, but redundant: `Python3.withPython { Py_GetVersion() }` is exactly `EmbedApiLowLevelTest.versionRoundTripsThroughTheCApi` (`commonTest`), and the version-prefix check duplicates `Python3Test.versionReportsTheConfiguredRelease` (`commonTest`). Left alone -- deleting a duplicate is a different kind of change than moving a misplaced one, and mixing the two was exactly what this pass was told not to do. |
| `ReachabilityMetadataTest` | `java.lang.invoke.MethodHandle`/`MethodType` reflection over the Panama bindings. |
| `SidecarLibraryLookupTest` | `System.getenv`, `System.mapLibraryName`, desktop's native-image sidecar-library resolution. |
| `StringMarshallingTest` | Calls `internedUtf8`/`encodeScratchUtf8`/`ffiReadUtf8`, which are `internal expect` in `jvmMain` (`ShapeDowncalls.kt`) with `actual`s in `desktopMain` and `androidMain` -- not visible outside the `jvmMain` subtree at all, so `commonTest` is not an option. It **could** move to the (currently empty) `jvmTest` to also cover Android, and there is already an near-identical hand-duplicated copy in `androidInstrumentedTest` (different test framework: `kotlin.test` here, raw `org.junit` + `@RunWith(AndroidJUnit4::class)` there, and missing this file's `testMultiSlotScratchReuseLimit` case). That consolidation needs a connected Android device to verify and was out of scope for this pass ("기기는 쓰지 마라"); noted here for the next one. |
| `UpcallEntryBridge.desktop.kt` | The `actual fun bindUpcallOrNull` for this target, built on `ctypes.CFUNCTYPE` over `UpcallStub` -- platform glue, not a test. |

**Conclusion for `desktopTest`: zero promotion candidates.** Every file either touches a JVM-only
API directly, reads a JVM-specific measurement (process memory, `MethodHandle`, `Unsafe`), or is
the `actual` half of an `expect` declared elsewhere. Nothing was moved.

### `iosSimulatorArm64Test` (2 files) and a promotion that was tried and reverted

- `AsyncioAvailabilityProbeTest` -- deliberately scoped to this one target by its own KDoc: it
  measures whether the iOS stdlib archive actually ships `_asyncio.so` (it does, despite the
  xcframework itself shipping no `lib-dynload`), and is kept out of `nativeTest` on purpose so a
  trap here (wasm's `import asyncio` kills the process rather than raising, per
  `AsyncUpcallPortabilityTest`) stays isolated to one file.
- `CycleCollectionTest` -- **this one was promoted to `nativeTest` and reverted.** Its own KDoc used
  to say it couldn't move because `nativeTest` wasn't wired into the hierarchy and every native
  target carried its own `forceGC()` actual, which would collide. That reasoning is stale as of
  `9040a8ce`: `nativeTest` is now wired (`iosX64Test`, `iosArm64Test`, `iosSimulatorArm64Test`,
  `androidNativeX64Test`, `androidNativeArm64Test` all `dependsOn(nativeTest)`), and
  `GCLeakTest.native.kt` already supplies a single shared `forceGC()` actual there. Everything else
  the file touches (`ProxyTypeFactory`, `HandleTable`, `ClassLookup`,
  `python.native.ffi.bindings.*`) is `nativeMain`-level and already shared between iOS and
  androidNative by other `nativeTest` files (`AsyncUpcallNative*Test`, `UpcallRawEntryPointTest`).

  Moved it, ran `compileTestKotlinAndroidNativeArm64`: **failed.**

  ```
  e: .../nativeTest/kotlin/python/multiplatform/ref/CycleCollectionTest.kt:79:48 Candidate 'val CPointer<*>?.rawValue: NativePtr' is inapplicable because of a receiver type mismatch.
  e: .../nativeTest/kotlin/python/multiplatform/ref/CycleCollectionTest.kt:474:55 ...
  e: .../nativeTest/kotlin/python/multiplatform/ref/CycleCollectionTest.kt:548:55 ...
  ```

  All three sites call `platform.posix.pthread_self()?.rawValue?.toLong()`. On Darwin,
  `pthread_self()` returns `CPointer<pthread_t>?` (`pthread_t` is `struct _opaque_pthread_t *`), so
  `.rawValue` resolves. On Linux/Bionic (androidNative's libc), `pthread_t` is a plain unsigned
  integral typedef, not a pointer, so the same cinterop declaration has no `.rawValue` to call. This
  is a genuine POSIX ABI difference surfaced through Kotlin/Native's `platform.posix` bindings, not
  a copy-paste mistake -- desktop and iOS never had to confront it before because neither had an
  androidNative sibling sharing this file. iOS and desktop/wasmJs still built and passed (341/303/316,
  0 failures, matching baseline) with the file in `nativeTest`, which is why only androidNative
  caught it.

  **Reverted**: the file is back in `iosSimulatorArm64Test`, byte-for-byte except its header KDoc,
  which now records this attempt and what it would take to retry (a `currentThreadId(): Long`
  `expect`/`actual` seam in `nativeMain` hiding the pointer-vs-integer difference behind one
  signature, used in place of the three raw `pthread_self()?.rawValue` calls). Fixing that is a
  distinct piece of work from test placement and was left for later, per the instruction to stop
  and report rather than repair.

- `androidInstrumentedTest` (ART) separately carries its own `CycleCollectionTest.kt` and
  `StringMarshallingTest.kt` -- a third platform-specific mirror of the same two concepts, using
  JNI-reachable APIs instead of Panama or cinterop. Three independent implementations of "does the
  cycle collector reach across the FFI boundary" is the established pattern in this codebase for
  concepts whose *mechanism* differs materially per platform (Unsafe+MethodHandle reflection on
  desktop, `platform.posix`/cinterop on native, JNI on ART) even though the *behaviour* under test is
  the same. That is different from "the same code, copy-pasted because a source set was reachable
  and nobody noticed" -- which is what `GCLeakTest`'s pre-`9040a8ce` duplication was, and what this
  pass looked for and did not otherwise find.

## Reverse direction: commonTest tests that could be meaningless on some platform

The established gate for this is `commonTest`'s `expect val` / `expect fun` pattern --
`GCLeakTest.kt` gates on `cleanerReleasesAutomatically` and an `expect class CollectorTestResult`
so wasm's promise-based finalisation and every other target's synchronous one can share one test
body. `WasmFinalizationTest` (`wasmJsTest`) is the standing control that proves the wasm adapter
actually awaits the returned `Promise`, so a regression can't silently stop running those
assertions.

No new case of this kind was found. The baseline run (341/303/316, all green) covers every
`commonTest` file across desktop, iOS simulator and wasmJs already, including the ones that touch
threading (`GilParkingTest`, `PendingCallTest`, `AsyncUpcallPortabilityTest`,
`PythonProxySourceTest`, `overhead/BenchmarkTest`, `RefCountTest`, `EvalCheckpointTest`) -- if one of
them were meaningless on a given target it would show up as a failure or a hang there, not as a
silent pass, and none did. `AsyncUpcallPortabilityTest` is itself the existing record of the one
trap this category has produced before: `import asyncio` killing the wasm instance outright rather
than raising, which is why the async upcall tests stay off `commonTest` and in per-platform files
instead.

## What this pass did not touch

- The `NativeThread`-based async upcall mirrors in `nativeTest` cover fewer cases than their
  `desktopTest` originals (1 test vs. 3, and `PythonProxyInstallTest` has no native mirror at all).
  Real coverage gap, but writing new tests is not a placement fix.
- `StringMarshallingTest`: `desktopTest` and `androidInstrumentedTest` hand-duplicate the same
  four cases, and the Android copy is missing `testMultiSlotScratchReuseLimit`. A `jvmTest`
  consolidation is plausible (the underlying `internal expect` already spans both) but needs a
  connected device to verify and was out of scope here.
- `DesktopPythonTest` duplicates two `commonTest` assertions already in `EmbedApiLowLevelTest` and
  `Python3Test`. Left in place; removing a duplicate is a different change from moving a misplaced
  one.

## Validation

```
rm -rf build/test-results/{desktopTest,iosSimulatorArm64Test,wasmJsNodeTest}
./gradlew :python-multiplatform:desktopTest :python-multiplatform:iosSimulatorArm64Test :python-multiplatform:wasmJsNodeTest --console=plain
./gradlew :python-multiplatform:compileTestKotlinAndroidNativeArm64 --console=plain
```

Result after the `CycleCollectionTest` revert: desktop 341/0 failed/1 skipped, iOS simulator
303/0, wasmJs 316/0 -- identical to the pre-existing baseline, since nothing was ultimately moved.
`compileTestKotlinAndroidNativeArm64` succeeds.
