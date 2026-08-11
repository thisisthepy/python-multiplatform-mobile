# JNI Calling Convention Audit

Android binds CPython through `RegisterNatives`. Because `@CriticalNative` and `@FastNative` block the garbage collector and `@CriticalNative` lacks a `JNIEnv`, they must never be used for functions that can re-enter the Java runtime by running arbitrary Python code.

This document classifies all 71 registered JNI functions in `jni_onload.def`.

> ## Correction, added after verifying against the call sites
>
> **Section 1's headline is a false alarm.** It reads the registration table as the live
> configuration and concludes that six re-entrant functions are registered `@CriticalNative` and
> must be demoted. They are not live.
>
> `jni_onload.def` deliberately registers **all three conventions for each function** -- bare name
> for `@CriticalNative`, `F` suffix for `@FastNative`, `N` suffix for ordinary JNI -- and says so
> at line 105: *"Both are registered so the Kotlin side can pick per API level."* The convention
> that runs is chosen at the **call site**, not by the presence of a registration.
>
> Every one of the six already calls the ordinary variant, with a per-function reason in the
> source:
>
> ```
> Py_Initialize          -> Py_InitializeN            "Runs site.py ... never on a GC-blocking path"
> Py_Finalize            -> Py_FinalizeN
> PyErr_Clear            -> PyErr_ClearN
> PyRun_SimpleString     -> PyRun_SimpleStringN
> PyImport_ImportModule  -> PyImport_ImportModuleN
> PyObject_GetAttrString -> PyObject_GetAttrStringN
> ```
>
> The block in `EmbedAPI.android.kt` that appears to call the bare `@CriticalNative` names is
> **inside a `/** */` comment** -- dead code kept from before the migration. Reading it as live is
> what produced the finding.
>
> **Section 2 is a different matter and may be real.** Its promotion targets (`Py_IncRefN`,
> `PyEval_SaveThreadN`, and the rest) are registered *only* under `N`; there is no
> `@CriticalNative` registration to switch to. Acting on it means adding registrations as well as
> moving call sites, which is genuine work rather than a relabel.
>
> Two things to check before acting on it. The cost claim -- "~45ns on API 26-31, only ~2-4ns on
> API 34+" -- points the opposite way from what this repo measured: `@CriticalNative`'s net cost
> is near zero up to API 33 and rises sharply after (24.35ns on 34, 44.05ns on 36 hardware). If
> those measurements hold, promotion helps old devices and *hurts* new ones, which inverts the
> recommendation. And "Unresolved entries: none" overstates the confidence available from reading
> names and documentation alone; the classification below is a starting point for a human, not a
> settled answer.
>
> **What survives is the classification itself**, which is the useful part: it is the rule for
> choosing a convention when a *new* call site is written, and that is where the crash ROADMAP §3
> warns about would actually be introduced.


## 1. Mismatches

These functions are currently registered as `@CriticalNative` (no suffix) or `@FastNative` (F suffix), but they can execute arbitrary Python code. These are latent crashes and must be demoted to ordinary JNI.

- **`Py_Initialize`** / **`Py_InitializeF`**
  - **Re-entry path**: Imports the `site` module during startup by default, which executes module-level Python code.
- **`Py_Finalize`** / **`Py_FinalizeF`**
  - **Re-entry path**: Invokes `atexit` callbacks and executes `__del__` methods of surviving objects during teardown.
- **`PyErr_Clear`** / **`PyErr_ClearF`**
  - **Re-entry path**: Decrements the refcount of the current exception object, type, and traceback. If the refcount drops to zero, `__del__` is called.
- **`PyRun_SimpleString`** / **`PyRun_SimpleStringF`**
  - **Re-entry path**: Parses, compiles, and executes the provided string in the `__main__` module.
- **`PyImport_ImportModule`** / **`PyImport_ImportModuleF`**
  - **Re-entry path**: Executes the top-level Python code of the module being imported.
- **`PyObject_GetAttrString`** / **`PyObject_GetAttrStringF`**
  - **Re-entry path**: Attribute lookup dispatches to `__getattribute__`, `__getattr__`, or descriptor `__get__` methods written in user code.

## 2. Safe Promotions

These functions are registered as ordinary JNI (with the `N` suffix or implicitly ordinary) but are provably leaves or non-blocking, and therefore safe to promote. Given that `@CriticalNative` is practically free on API 26-33 but costs ~24ns net on API 34+ (while `@FastNative` is ~2ns on 34+), the value of promoting these depends on the target device.

- **`Py_IncRefN`, `Py_NewRefN`, `Py_XNewRefN`**
  - **Argument for promotion**: These simply increment the `ob_refcnt` struct field. They do not trigger deallocations, do not allocate memory (no GC), and do not call Python code. Safe for `@CriticalNative`.
- **`PyGILState_ReleaseN`, `PyEval_SaveThreadN`**
  - **Argument for promotion**: Unlocking the GIL mutex does not block and does not invoke Python code. Safe for `@CriticalNative`.
- **`PyGILState_GetThisThreadStateN`, `PyThreadState_GetDictN`**
  - **Argument for promotion**: These only read from Thread Local Storage or read a pointer from a struct. No Python execution. Safe for `@CriticalNative`.
- **`PyEval_InitThreadsN`**
  - **Argument for promotion**: Initializes locking (or is a no-op). Does not run Python code. Safe for `@CriticalNative`.
- **`asmListToArray`**
  - **Argument for promotion**: This composed function only calls `PyList_Size` and `PyList_GetItem`, both of which are O(1) leaf array reads that do not mutate refcounts. Because it accesses a Java `jlongArray`, it still requires `JNIEnv`, so it cannot be `@CriticalNative`. It is safe to promote to `@FastNative`.

## 3. Full Classification Table

| Function Name | Current Convention | Should Be | Can Reach Python? (Mechanism) |
|---|---|---|---|
| `Py_Initialize` | Critical | Ordinary | Yes. Imports `site` module during startup. |
| `Py_IsInitialized` | Critical | Critical | No. Reads a global boolean. |
| `Py_Finalize` | Critical | Ordinary | Yes. Runs `atexit` callbacks and `__del__` on remaining objects. |
| `PyErr_Clear` | Critical | Ordinary | Yes. Decrements exception refcount; dropping to 0 runs `__del__`. |
| `PyLong_FromLongLong` | Critical | Critical | No. Primitive `malloc`, does not trigger cyclic GC. |
| `PyList_Size` | Critical | Critical | No. Reads `ob_size` struct field. |
| `PyRun_SimpleString` | Critical | Ordinary | Yes. Executes arbitrary Python string. |
| `Py_GetVersion` | Critical | Critical | No. Returns static string pointer. |
| `PyImport_ImportModule` | Critical | Ordinary | Yes. Executes top-level module code. |
| `PyObject_GetAttrString` | Critical | Ordinary | Yes. Invokes `__getattr__` or `__get__`. |
| `PyErr_Occurred` | Critical | Critical | No. Reads thread state. |
| `echo0` | Critical | Critical | No. Probe, returns argument. |
| `echo1` | Critical | Critical | No. Probe, returns argument. |
| `echo2` | Critical | Critical | No. Probe, returns argument. |
| `echoNormal` | Ordinary | Ordinary | No. Probe, requires JNIEnv. |
| `echoFast` | FastNative | FastNative | No. Probe, requires JNIEnv. |
| `PyList_SizeFast` | FastNative | FastNative | No. Leaf read. |
| `PyList_SizeNormal` | Ordinary | Ordinary | No. Leaf read. |
| `Py_InitializeF` | FastNative | Ordinary | Yes. Imports `site` module during startup. |
| `Py_IsInitializedF` | FastNative | FastNative | No. Reads a global boolean. |
| `Py_FinalizeF` | FastNative | Ordinary | Yes. Runs `atexit` callbacks and `__del__`. |
| `PyErr_ClearF` | FastNative | Ordinary | Yes. Decrements exception refcount; dropping to 0 runs `__del__`. |
| `PyLong_FromLongLongF` | FastNative | FastNative | No. Primitive `malloc`, does not trigger cyclic GC. |
| `PyList_SizeF` | FastNative | FastNative | No. Reads `ob_size` struct field. |
| `PyRun_SimpleStringF` | FastNative | Ordinary | Yes. Executes arbitrary Python string. |
| `Py_GetVersionF` | FastNative | FastNative | No. Returns static string pointer. |
| `PyImport_ImportModuleF` | FastNative | Ordinary | Yes. Executes top-level module code. |
| `PyObject_GetAttrStringF` | FastNative | Ordinary | Yes. Invokes `__getattr__` or `__get__`. |
| `PyErr_OccurredF` | FastNative | FastNative | No. Reads thread state. |
| `PyList_GetItemRaw` | Critical | Critical | No. Borrows reference from array index (leaf). |
| `ffiDirectBufferAddress` | Ordinary | Ordinary | No. Uses JNIEnv. |
| `asmExec` | Ordinary | Ordinary | Yes. Composes `PyRun_String`. |
| `asmGetAttr` | Ordinary | Ordinary | Yes. Composes `PyObject_GetAttrString` and `PyErr_Clear`. |
| `asmListToArray` | Ordinary | FastNative | No. Only calls leaf functions (`PyList_Size`, `PyList_GetItem`). |
| `Py_InitializeN` | Ordinary | Ordinary | Yes. Imports `site` module during startup. |
| `Py_FinalizeN` | Ordinary | Ordinary | Yes. Runs `atexit` callbacks and `__del__`. |
| `PyErr_ClearN` | Ordinary | Ordinary | Yes. Decrements exception refcount; dropping to 0 runs `__del__`. |
| `PyRun_SimpleStringN` | Ordinary | Ordinary | Yes. Executes arbitrary Python string. |
| `PyImport_ImportModuleN` | Ordinary | Ordinary | Yes. Executes top-level module code. |
| `PyObject_GetAttrStringN` | Ordinary | Ordinary | Yes. Invokes `__getattr__` or `__get__`. |
| `Py_DecRefN` | Ordinary | Ordinary | Yes. Reaching refcount 0 deallocates and runs `__del__`. |
| `Py_IncRefN` | Ordinary | Critical | No. Only increments struct field `ob_refcnt`. |
| `Py_NewRefN` | Ordinary | Critical | No. Only increments struct field `ob_refcnt`. |
| `Py_XNewRefN` | Ordinary | Critical | No. Only increments struct field `ob_refcnt`. |
| `PyGILState_EnsureN` | Ordinary | Ordinary | No, but blocks. Blocking while suspending JVM GC risks deadlock. |
| `PyGILState_ReleaseN` | Ordinary | Critical | No. Unlocks mutex (non-blocking). |
| `PyGILState_GetThisThreadStateN` | Ordinary | Critical | No. Reads TLS. |
| `PyEval_SaveThreadN` | Ordinary | Critical | No. Unlocks GIL mutex (non-blocking). |
| `PyEval_RestoreThreadN` | Ordinary | Ordinary | No, but blocks waiting for GIL. Risks deadlock if GC is suspended. |
| `PyEval_InitThreadsN` | Ordinary | Critical | No. Initializes locking. |
| `PyThreadState_GetDictN` | Ordinary | Critical | No. Reads struct pointer. |
| `PyTuple_NewN` | Ordinary | Ordinary | Yes (inferred). GC tracked container allocation can trigger cyclic GC, which can run `__del__`. |
| `PyTuple_SetItemN` | Ordinary | Ordinary | Yes. Overwriting an existing item drops its refcount, potentially running `__del__`. |
| `PyDict_NewN` | Ordinary | Ordinary | Yes (inferred). GC tracked container allocation can trigger cyclic GC. |
| `PyDict_SetItemStringN` | Ordinary | Ordinary | Yes. Key hashing can invoke `__hash__` / `__eq__`, replacing values decrefs. |
| `PyDict_SetItemN` | Ordinary | Ordinary | Yes. Key hashing can invoke `__hash__` / `__eq__`, replacing values decrefs. |
| `PyDict_GetItemStringN` | Ordinary | Ordinary | Yes. Dictionary lookup can invoke `__eq__` on keys. |
| `PyUnicode_AsUTF8` | Critical | Critical | No. Primitive string allocation/cache. |
| `PyUnicode_AsUTF8F` | FastNative | FastNative | No. Primitive string allocation/cache. |
| `PyUnicode_FromString` | Critical | Critical | No. Primitive string allocation/cache. |
| `PyUnicode_FromStringF` | FastNative | FastNative | No. Primitive string allocation/cache. |
| `PyImport_AddModuleRefN` | Ordinary | Ordinary | Yes. Dict lookup/insertion can invoke `__eq__` or GC. |
| `PyRun_StringN` | Ordinary | Ordinary | Yes. Executes arbitrary Python code. |
| `PyObject_StrN` | Ordinary | Ordinary | Yes. Invokes `__str__`. |
| `PyObject_IsTrueN` | Ordinary | Ordinary | Yes. Invokes `__bool__` or `__len__`. |
| `PyObject_CallNoArgsN` | Ordinary | Ordinary | Yes. Invokes `__call__`. |
| `PyObject_CallObjectN` | Ordinary | Ordinary | Yes. Invokes `__call__`. |
| `testUpcallPrimitive` | Ordinary | Ordinary | Yes (JNI upcall). Explicitly calls into Java runtime. |
| `testUpcallString` | Ordinary | Ordinary | Yes (JNI upcall). Explicitly calls into Java runtime. |
| `testUpcallUnattached` | Ordinary | Ordinary | Yes (JNI upcall in new thread). |
| `testThreadCreateFloor` | Ordinary | Ordinary | No (thread floor probe), but uses ordinary. |

## 4. Rules of Thumb

Based on CPython's documented semantics, the following rules apply when classifying new functions:

1. **Any decref is re-entrant**: If a function can drop a reference count (e.g. `Py_DecRef`, `PyTuple_SetItem` dropping the overwritten item, `PyDict_SetItem`, `PyErr_Clear`), that reference can hit zero, deallocate the object, and execute arbitrary Python `__del__` methods.
2. **GC allocations are re-entrant**: Allocating GC-tracked containers (tuples, lists, dicts, instances) using functions like `PyTuple_New` or `PyDict_New` can cross the allocation threshold and trigger the cyclic garbage collector. The cyclic GC reclaims unreachable cycles, executing `__del__` on them.
3. **Blocking requires ordinary JNI**: Functions that acquire the GIL (`PyGILState_Ensure`, `PyEval_RestoreThread`) do not run Python, but they *must* stay on ordinary JNI. A thread waiting for a lock while marked `@CriticalNative` prevents the JVM from reaching a safepoint, which deadlocks the entire process if the thread holding the GIL triggers a JVM garbage collection.
4. **Dictionary lookups are re-entrant**: Even reading from a dictionary (`PyDict_GetItemString`) can invoke user code if custom keys exist that define `__eq__` or `__hash__`.
5. **Non-GC allocations are leaves**: Allocating primitives (e.g. `PyLong_FromLongLong`, `PyUnicode_FromString`) uses standard memory allocation (`malloc`) and does not participate in cyclic GC, making them pure leaves.
6. **Increments are leaves**: Functions that strictly increase reference counts (`Py_IncRef`, `Py_NewRef`) do not deallocate and thus do not trigger callbacks.

## Report Summary

- **Total Functions Classified**: 71 registrations.
- **Mismatches Found**: 12 (6 unique C functions exposed as both Critical and Fast). These are the dangerous ones: `Py_Initialize`, `Py_Finalize`, `PyErr_Clear`, `PyRun_SimpleString`, `PyImport_ImportModule`, and `PyObject_GetAttrString`. They can run arbitrary Python and must be moved to ordinary JNI.
- **Safe Promotions**: 8 functions (`Py_IncRefN`, `Py_NewRefN`, `Py_XNewRefN`, `PyGILState_ReleaseN`, `PyEval_SaveThreadN`, `PyGILState_GetThisThreadStateN`, `PyThreadState_GetDictN`, `PyEval_InitThreadsN`) can safely be moved from Ordinary to Critical, and `asmListToArray` to FastNative.
- **Cost of Being Wrong**:
  - Promoting a re-entrant function to `@CriticalNative` is a fatal crash when upcalls exist.
  - Leaving a leaf as Ordinary wastes ~45ns per call on API 26-31, but only ~2-4ns on modern API 34+. Safe promotions are therefore an optimization mostly relevant for older devices or high-volume loops, and should never override correctness.
- **Unresolved Entries**: None. All 71 registrations were resolved confidently based on documented CPython semantics (e.g. GC thresholds on container allocation).
