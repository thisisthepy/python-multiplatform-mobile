package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.Python3.isInitialized
import python.multiplatform.ffi.PyObject
import python.native.ffi.*


// PyModule -> PyObject 상속하기
// PyObject 상속할 때 pointer와 borrowed는 어떻게 받아오지?
class PyModule(pointer: NativePointer, borrowed: Boolean): PyObject(pointer, borrowed) {
    //val builtins: Builtins

    init {
    }
}


class Builtins(

)
