package python.multiplatform.ffi

import python.native.ffi.NativePointer


actual open class PyObject actual constructor(actual override val pointer: NativePointer, borrowed: Boolean): PyObjectAutoCloseable(pointer, borrowed) {

    //actual external fun incRef()

    //...
}
