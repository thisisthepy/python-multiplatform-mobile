# androidMain — rules

The JVM half of Android. Reaches CPython through JNI into the Kotlin/Native library built from
`artMain` + `nativeMain`.

## Bind through `RegisterNatives`, not symbol names

`JNI_OnLoad` (in `artMain`) registers CPython's own C functions directly against the
declarations here. Two reasons, both measured:

- Name-based linking of a `@CriticalNative` method **aborts the ART runtime** below Android 12
  (`zygote64: runtime.cc:492] Runtime aborting...`), and is merely slow above it — 76.42 ns
  against 46.55 ns for the registered form on API 36 hardware.
- Registration removes the dependence on JNI name mangling, which is what produced the original
  argument-shift bug in the first place.

## Pick the calling convention per API level *and* per function

Two independent axes. Correctness first.

**Axis 1 — what the function may do.** `@FastNative` and `@CriticalNative` both stop the
collector for the duration of the call, and `@CriticalNative` has no `JNIEnv`, so nothing under
it can re-enter the runtime. Anything that can execute arbitrary Python — a module's top-level
code during import, a `__getattr__`, a `__del__` reached by dropping the last reference —
stays on ordinary JNI at every API level. `PyErr_Clear` and `PyObject_GetAttrString` both look
like leaves and are not.

When in doubt, ordinary JNI. A wrong guess that way costs nanoseconds; the other way is a crash
once upcalls exist.

**Axis 2 — what the device fast-paths.** Measured across five API levels, net of the Kotlin
floor, ns/call:

| API | `@CriticalNative` | `@FastNative` | ordinary JNI |
|---|---|---|---|
| 26 | **1.86** | 38.69 | 45.33 |
| 30 | **-0.24** | 31.76 | 73.09 |
| 31 | **-0.02** | 24.29 | 45.97 |
| 33 | **-0.04** | 2.03 | 7.82 |
| 34 | 24.35 | **1.97** | 7.81 |
| 36 (hardware) | 44.05 | **3.55** | 18.32 |

Hence `bindings.preferFastNative = SDK_INT >= 34`. `dispatchPicksTheFasterConventionOnThisDevice`
re-measures both on whatever device runs it and fails if the flag disagrees, so a future release
moving the boundary shows up as a test failure rather than silent slowness.

## The boundary carries primitives only

`Long`, `Int`, `Double`, `Unit`. No `String`, no nullable `JNIPointer?` — a nullable `Long` is a
boxed `java.lang.Long` on the JVM against a raw `jlong` on the other side. Strings cross as
addresses; null pointers cross as `0L`. Conversion happens here, in `EmbedAPI.android.kt`.

## String marshalling is the expensive part, not crossings

Composing `Python3.exec` natively nearly halves it — 17.8 µs to 10.1 µs. That is **not** the
crossing count: 7.7 µs over ~11 crossings would be 700 ns each, against the ~7 ns a crossing
actually costs, and the per-call figure is identical on API 26 and API 36 despite a 20x
difference in per-crossing cost. It is the three `ffiAllocUtf8` round trips, each a JNI call
plus `GetStringUTFChars` plus malloc plus copy plus free.

So string-carrying operations are the ones worth composing, regardless of how much real work
they do.
