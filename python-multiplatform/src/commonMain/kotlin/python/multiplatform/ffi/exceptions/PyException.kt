package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.native.ffi.NativePointer

class PyException(val errMsg: String): Exception() {
//    private val pyException = object: PyObject(pointer, false) {
//        val type: PyType by lazy {
//            //TODO: PyType은 python의 BaseException 이어야 하나? PyException과 BaseException이 같은 층위에 있는건가? 근데 Python Exception 중 Exception이 아니라 BaseException을 직접 상속받는 에러가 있음. 그런데 이것들은 프로그램 종료와 관련있음.
//        }
//
//
//    }
//
//    fun toPyObject(): PyObject {
//        return
//    }
//
//
//    fun checkPyError(): Boolean {
//        if (PyErr_Occurred() != null) {
//            PyErr_Clear();
//            return true
//        }
//        return false
//    }
//

}