package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer


expect open class PyObject(pointer: NativePointer, borrowed: Boolean) {
    val pointer: NativePointer
}
/*
    //init {
    //    if (!borrowed) {
            //PyIncRef(pointer)
            // TODO: PyIncRef을 사용하는게 적절한 선택일까?
    //    }
    //}
    // TODO: Temporary commented due to the error: Expected declaration cannot have a body.
    // TODO: Move this to the actual implementation.

    val pointer: NativePointer
    protected val typePointer: NativePointer = lazy { PyType_GetType(this.pointer) }
    val Type: PyType = lazy { PyType(typePointer!!) }

    fun incRef() {
        println("hi")
    }

    fun decRef() {
        println("hi")
    }

    @Throws(PyException::class)
    fun getAttr(name: String): PyObject {
        val attr = PyObject_GetAttrString(pointer, name)
        if (attr == null) {
            throw PyException("Attribute '$name' not found")
        }
        return PyObject(attr, false)
    }

    fun getAttrOrNull(name: String): PyObject? {
        val attr = PyObject_GetAttrString(pointer, name)
        return if (attr != null) PyObject(attr, false) else null
    }

    @Throws(PyException::class)
    fun setAttr(name: String, value: PyObject) {
        if (PyObject_SetAttrString(pointer, name, value.pointer) != 0) {
            throw PyException("Failed to set attribute '$name'")
        }
    }

    fun setAttrOrNull(name: String, value: PyObject?) {
        PyObject_SetAttrString(pointer, name, value?.pointer)
    }

    @Throws(PyException::class)
    fun delAttr(name: String) {
        if (PyObject_DelAttrString(pointer, name) != 0) {
            throw PyException("Failed to delete attribute '$name'")
        }
    }

    fun delAttrOrNull(name: String) {
        PyObject_DelAttrString(pointer, name)
    }

    override fun toString(): String {
        return PyObject_GetStr(pointer)
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

    operator fun invoke(arg0: PyObject): PyObject {
        return PyObject_CallObject(pointer, arg0)
    }

    operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject {
        return PyObject_CallObject(pointer, arg0, arg1)
    }

    //...

    operator fun invoke(vararg args: PyObject): PyObject {
        return PyObject_CallObject(pointer, args)  // TODO: 이거는 변수 하나로 잡히던가? 아님 여러개인가?
                                                    // 리스트로 들어오는 거였던가?
    }//    actual fun pyLongFromLong(arg0: Long): Long {
//        if (!isInitialized) return -1
//        memScoped {
//            val pyLong = PyLong_FromLong(arg0)
//            if (pyLong == null) {
//                throw IllegalStateException("Python long from long failed")
//            }
//            return pyLong.toLong()
//        }
//    }
//
//    actual fun pyLongAsLong(arg0: Long): Long {
//        if (!isInitialized) return -1
//        memScoped {
//            val restoredPyObj: CValuesRef<_object>? = arg0.toCPointer()
//            val ktLong = PyLong_AsLong(restoredPyObj)
//            if (ktLong == -1L && PyErr_Occurred() != null) {
//                throw IllegalStateException("Python long as long failed")
//            }
//            return ktLong
//        }
//    }
}
*/