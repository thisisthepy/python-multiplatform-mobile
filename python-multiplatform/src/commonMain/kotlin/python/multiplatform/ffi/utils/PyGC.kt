package python.multiplatform.ffi.utils

import python.multiplatform.ffi.PyObject

/**
 * Thin façade over CPython's garbage collector and refcounting.
 *
 * `EmbedAPI` exposes no dedicated `PyGC_*` Stable ABI functions (there is no
 * stable `PyGC_Collect`/`PyGC_Enable`/`PyGC_IsEnabled` in this project's FFI
 * surface), so this is expected to be implemented later by driving the
 * `gc` module through [python.multiplatform.ffi.Python3.import] /
 * `PyObject_CallObject` (i.e. `import gc; gc.collect()`), rather than
 * through direct C entry points.
 */
object PyGC {
    /** `gc.collect()`; returns the number of unreachable objects found. */
    fun collect(): Int {
        TODO("Not yet implemented")
    }

    /** `gc.enable()`. */
    fun enable() {
        TODO("Not yet implemented")
    }

    /** `gc.disable()`. */
    fun disable() {
        TODO("Not yet implemented")
    }

    /** `gc.isenabled()`. */
    fun isEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    /** `sys.getrefcount(obj) - 1` (subtracting the temporary reference `getrefcount` itself creates). */
    fun refCount(obj: PyObject): Long {
        TODO("Not yet implemented")
    }
}
