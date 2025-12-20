package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ref.PyAutoCloseable
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_DelAttrString
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_SetAttrString
import python.native.ffi.PyObject_Str
import python.native.ffi.PyObject_Type
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef
import python.native.ffi.memScoped


open class PyObject(val pointer: NativePointer, borrowed: Boolean): PyAutoCloseable(pointer) {

    init {
        if (borrowed) {
            Py_IncRef(pointer)
            // TODO: PyIncRef을 사용하는게 적절한 선택일까?
        }
    }

    //    protected val typePointer: NativePointer = lazy { PyType_GetType(this.pointer) } // TODO: PyType_GetType 함수 제작 | 함수 body를 어디에 작성...? 일단 EmbedAPI.kt에 작성
    // protected val typePointer: NativePointer = lazy { PyObject_Type(this.pointer) } // TODO: PyObject_Type가 위의 PyType_GetType의 역할이 맞는지 확인 | 이 instance가 사라지면 이 변수가 가리키는 Object의 RefCount를 1 줄여주어야 함
    protected val typePointer: NativePointer by lazy { PyObject_Type(this.pointer).let {
        // TODO: catch python side error **Do Not cause error in kotlin**
        if (it == null) throw NullPointerException("Failed to get type pointer")
        else it
    }} // TODO: Null check 하기
//    val Type: PyType = lazy { PyType(typePointer!!) }
    val type: PyType by lazy { PyType(typePointer) } // TODO: borrowed를 바꿀 수 있게 할지 결정

    fun incRef() {
        println("hi")
        // TODO: 예외 처리 추가
        Py_IncRef(pointer)
    }

    fun decRef() {
        println("hi")
        // TODO: 예외 처리 추가
        Py_DecRef(pointer)
    }

//    @Throws(PyException::class)
//    fun getAttr(name: String): PyObject {
//        val attr = PyObject_GetAttrString(pointer, name)
//        if (attr == null) {
//            throw PyException("Attribute '$name' not found")
//        }
//        return PyObject(attr, false)
//    }

    fun getAttrOrNull(name: String): PyObject? {
        val attr = PyObject_GetAttrString(pointer, name)
        return if (attr != null) PyObject(attr, false) else null
    }

//    @Throws(PyException::class)
//    fun setAttr(name: String, value: PyObject) {
//        if (PyObject_SetAttrString(pointer, name, value.pointer) != 0) {
//            throw PyException("Failed to set attribute '$name'")
//        }
//    }

    fun setAttrOrNull(name: String, value: PyObject?) {
        value?.pointer?.let { PyObject_SetAttrString(pointer, name, it) }
    }

//    @Throws(PyException::class)
//    fun delAttr(name: String) {
//        if (PyObject_DelAttrString(pointer, name) != 0) {
//            throw PyException("Failed to delete attribute '$name'")
//        }
//    }

    fun delAttrOrNull(name: String) {
        PyObject_DelAttrString(pointer, name)
    }

    override fun toString(): String {
        // TODO: PyObject_Str, PyUnicode_AsUTF8 return value가 null인 경우 처리
        return PyUnicode_AsUTF8(PyObject_Str(pointer)!!)!!
//        return PyObject_GetStr(pointer)
    }

    override fun hashCode(): Int {
        // TODO: 같은 포인터 객체는 한번만 생성하도록 해야 함
        return pointer.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is PyObject) {
            return pointer == other.pointer
        }
        return false
    }

    override fun clean() {
        Py_DecRef(typePointer)
    }

    // TODO: 밑에 세 함수 수정 (return type 불일치 등)
//    operator fun invoke(arg0: PyObject): PyObject {
//        return PyObject_CallObject(pointer, arg0)
//    }
//
//    operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject {
//        return PyObject_CallObject(pointer, arg0, arg1) // TODO: PyObject_CallObject 함수 매개변수는 2개 아닌가...?
//    }
//
//    //...
//
//    operator fun invoke(vararg args: PyObject): PyObject {
//        return PyObject_CallObject(pointer, args)  // TODO: 이거는 변수 하나로 잡히던가? 아님 여러개인가?
//        // 리스트로 들어오는 거였던가?
//    }

//    actual fun pyLongFromLong(arg0: Long): Long {
//        if (!Python3.isInitialized) return -1
//        memScoped {
////            val pyLong = PyLong_FromLong(arg0) // TODO: PyLong_FromLong을 PyLong_FromLongLong으로 교체
//            val pyLong = PyLong_FromLongLong(arg0)
//            if (pyLong == null) {
//                throw IllegalStateException("Python long from long failed")
//            }
//            return pyLong.toLong()
//        }
//    }
//
//    actual fun pyLongAsLong(arg0: Long): Long {
//        if (!Python3.isInitialized) return -1
//        memScoped {
//            val restoredPyObj: CValuesRef<_object>? = arg0.toCPointer()
////            val ktLong = PyLong_AsLong(restoredPyObj) // TODO: PyLong_AsLong을 PyLong_AsLongLong으로 교체
//            val ktLong = PyLong_AsLongLong(restoredPyObj)
//            if (ktLong == -1L && PyErr_Occurred() != null) {
//                throw IllegalStateException("Python long as long failed")
//            }
//            return ktLong
//        }
//    }
}