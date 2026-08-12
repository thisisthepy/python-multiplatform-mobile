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

Read the table in the direction that matters when adding a call site — how much a promotion is
worth, relative to leaving it ordinary:

| API | ordinary → `@FastNative` | ordinary → `@CriticalNative` |
|---|---|---|
| 26 | −6.64 | −43.47 |
| 30 | −41.33 | −73.33 |
| 31 | −21.68 | −45.99 |
| 33 | −5.79 | −7.86 |
| 34 | −5.84 | **+16.54** |
| 36 | −14.77 | **+25.73** |

`@FastNative` is a win on every level measured. `@CriticalNative` is the larger win below 34 and a
**loss** above it, so it is only ever correct behind the `preferFastNative` branch, never pinned.
That is the opposite of what a promotion audit written from ART's documentation will tell you.

## Choosing a convention for a new call site

Ordinary JNI is the default. A promotion has to survive all five steps.

**1. Can it drop a reference?** → ordinary. Stop.

Any refcount reaching zero deallocates and runs `__del__`, which is arbitrary Python. This catches
far more than the names suggest: every setter that overwrites (`PyTuple_SetItem`, `PyList_SetItem`,
`PyDict_SetItem*`, `PySys_SetObject`), every delete, `PyErr_Clear`, `Py_Finalize` — and
`PyGILState_Release`, because the release that drops the counter to zero deletes the thread state,
and clearing a thread state decrefs its dict and exception state. A function that looks pure and
touches a refcount is re-entrant.

**2. Can it allocate a GC-tracked container, or reach a Python-level hook?** → ordinary.

Allocating a tuple, list, dict, set or instance can cross the collection threshold, and the cyclic
collector runs `__del__` on what it reclaims. Hooks reached by ordinary-looking calls: `__getattr__`
and descriptor `__get__` on any attribute access, `__hash__`/`__eq__` on any dict or set operation
*including lookups*, `__iter__`/`__next__` inside `PySequence_Fast`, `__getitem__` inside
`PyUnicode_Translate`, module top-level code on any import, a Python codec on any encode/decode
that takes an encoding name, and `warnings.showwarning` inside `PyErr_WarnExplicit`.
`PyThreadState_GetDict` belongs here too — it allocates the thread dict lazily on first call per
thread, and is not the struct read its name implies.

**3. Can it block?** → ordinary. `PyGILState_Ensure`, `PyEval_RestoreThread`. A thread waiting for
a lock under a GC-blocking convention never reaches a safepoint, so the JVM collector stalls behind
whatever holds the GIL.

**4. Everything that is left can still raise.** → ordinary unless the win has been *measured* on
this call.

There is no C API function that cannot fail, and CPython reports failure by constructing an
exception instance, which is GC-tracked, which lands back in step 2. So step 4 never returns
"provably a leaf" — it returns "leaf on the success path, second-order re-entrant on the error
path". That is why the promoted set is short and enumerated rather than derived from a rule: it is
the set where a benchmark showed the convention *is* the call cost, not the set that passed an
argument.

**5. If promoted, register both twins and branch on `preferFastNative`.** Never pin one convention.
The C wrapper for the `@FastNative` twin is the same body with a leading `JNIEnv*, jclass`; the
`@CriticalNative` one must have neither. See `artMain/cinterop/jni_onload.def`.

### Why the default is ordinary

| guessed wrong toward | costs |
|---|---|
| ordinary | 5.8 ns (API 33/34) to 41 ns (API 30) per call — measurable, and bounded |
| a promotion | the collector stalls for as long as the Python runs; under `@CriticalNative` there is no `JNIEnv`, so any upcall that Python reaches has none either |

Only the first of those shows up in a test. The second needs a device, live upcalls, *and* the
cyclic collector to fire inside that particular call — so it is found in production, not in CI.

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
