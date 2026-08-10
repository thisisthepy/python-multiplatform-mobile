package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType

/**
 * Kotlin-side mirror of a Python exception.
 *
 * Historically this only carried a plain message (used throughout
 * [python.multiplatform.ffi.PyObject] / [python.multiplatform.ffi.PyType] for
 * FFI-level failures where no live Python error is involved). It now also
 * exposes the pieces of `PyErr_Fetch()` / `PyErr_GetRaisedException()`
 * (type, value, traceback) so a genuine Python-level error can be surfaced
 * as a fully-populated Kotlin throwable, matching the mermaid sketch
 * (`type`, `value`, `traceback`, `message`, `cause`, `context`).
 *
 * [errMsg] stays the primary constructor parameter (and thus [message]) so
 * every existing call site (`PyException("...")`) keeps compiling unchanged.
 */
open class PyException(
    val errMsg: String,
    val type: PyType? = null,
    val value: PyObject? = null,
    val traceback: PyTraceback? = null,
    /** Python's `__context__`: the exception that was being handled when this one was raised, if any. */
    val context: PyException? = null,
    cause: PyException? = null,
) : Exception(errMsg, cause) {

    companion object {
        /**
         * Builds a [PyException] from whatever CPython's error indicator
         * currently holds (via `PyErr_GetRaisedException()` / `PyErr_Fetch()`),
         * clearing the indicator in the process. Returns `null` if no error
         * is currently set.
         */
        fun fromCurrentError(): PyException? {
            TODO("Not yet implemented")
        }
    }

    /** Re-raises this exception into the current Python error indicator (`PyErr_Restore`/`PyErr_SetObject`). */
    fun restore() {
        TODO("Not yet implemented")
    }
}
