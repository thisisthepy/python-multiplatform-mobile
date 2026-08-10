package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/** Wrapper around a Python `classmethod` descriptor object. */
open class PyClassMethod(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `classmethod.__func__`: the underlying function, called with the owning class as its first argument. */
    val function: PyFunction
        get() = TODO("Not yet implemented")
}
