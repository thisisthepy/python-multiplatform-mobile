package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer


open class PyBool(pointer: NativePointer, borrowed: Boolean): PyObject(pointer, borrowed), PyProxy<Boolean> {
    companion object {
        fun from(value: Boolean): PyBool {
            return if (value) True else False
        }
    }
}

fun Boolean.toPyBool(): PyBool = PyBool.from(this)
