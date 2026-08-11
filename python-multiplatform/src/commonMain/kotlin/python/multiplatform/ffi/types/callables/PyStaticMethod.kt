package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/** Wrapper around a Python `staticmethod` descriptor object. */
open class PyStaticMethod(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `staticmethod.__func__`: the underlying plain function. */
    val function: PyFunction
        get() = TODO("Not yet implemented")
}
