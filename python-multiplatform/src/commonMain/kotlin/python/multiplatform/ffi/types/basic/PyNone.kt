package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.native.ffi.NativePointer

/**
 * Wrapper around Python's `None` singleton.
 *
 * Unlike the other basic types, `None` cannot be freshly constructed --
 * there is exactly one instance for the lifetime of the interpreter. The
 * Stable ABI subset available in `EmbedAPI` does not expose a direct
 * `Py_None` accessor (it is a data symbol, not a function, in the full C
 * API), so [get] is expected to obtain the singleton indirectly (e.g. by
 * evaluating the literal `None` via [python.multiplatform.ffi.Python3.eval]).
 */
class PyNone private constructor(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** The `PyType` for `NoneType`. */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Returns the singleton `None` object, initialising it on first access. */
        fun get(): PyNone {
            TODO("Not yet implemented")
        }

        /** Whether [obj] is Python's `None` singleton (`Py_IsNone` / `obj is None`). */
        fun isNone(obj: PyObject): Boolean {
            TODO("Not yet implemented")
        }
    }
}
