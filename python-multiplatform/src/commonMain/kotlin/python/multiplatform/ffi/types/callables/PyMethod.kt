package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python bound method object (`instance.method`), i.e. a
 * function together with the `self` it is bound to.
 */
open class PyMethod(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `method.__self__`: the bound receiver. */
    val instance: PyObject
        get() = TODO("Not yet implemented")

    /** `method.__func__`: the underlying unbound function. */
    val function: PyFunction
        get() = TODO("Not yet implemented")
}
