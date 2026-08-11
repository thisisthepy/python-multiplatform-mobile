package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/** Wrapper around Python's `Ellipsis` (`...`) singleton. See [python.multiplatform.ffi.types.basic.PyNone] for the same singleton-access caveat. */
class PyEllipsis private constructor(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** Returns the singleton `Ellipsis` object, initialising it on first access. */
        fun get(): PyEllipsis {
            TODO("Not yet implemented")
        }
    }
}
