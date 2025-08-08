package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer


open class PyFloat(pointer: NativePointer, borrowed: Boolean): PyObject(pointer, borrowed), PyProxy<Boolean> {
    companion object {
        //fun from(value: Boolean): PyBool {
        //    return if (value) True else False
        //}
    }

    override var cachedNativeValue: Boolean?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}
}

//fun Boolean.toPyBool(): PyBool = PyBool.from(this)
