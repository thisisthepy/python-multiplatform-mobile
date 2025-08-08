package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer


open class PyComplex(pointer: NativePointer, borrowed: Boolean): PyObject(pointer, borrowed) {

}
