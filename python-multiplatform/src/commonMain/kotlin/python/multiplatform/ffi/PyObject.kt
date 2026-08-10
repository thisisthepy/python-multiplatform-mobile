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


/**
 * Rich-comparison operators understood by `PyObject_RichCompare`
 * (`Py_LT` .. `Py_GE`, in CPython's fixed `0..5` ordering).
 */
enum class PyCompareOp(val opId: Int) {
    LT(0), LE(1), EQ(2), NE(3), GT(4), GE(5)
}

// TODO: !!IMPORTANT!! We need to check the case where the pointer is null one more time. (PyObject, PyType, PyException)
open class PyObject(val pointer: NativePointer, borrowed: Boolean): PyAutoCloseable(pointer) {

    init {
        if (borrowed) {
            Py_IncRef(pointer)
            // TODO: PyIncRef을 사용하는게 적절한 선택일까?
        }
    }

//    @Throws(PyException::class)
    protected val type: PyType by lazy {
        val typePointer: NativePointer? = PyObject_Type(pointer)
//        throw PyException("Failed to get pointer of type")
        PyType.getInstance(typePointer!!)
    }

    protected fun incRef() {
        Py_IncRef(pointer)
    }

    protected fun decRef() {
        Py_DecRef(pointer)
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
        value?.pointer?.let { PyObject_SetAttrString(pointer, name, it) }
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

    /** Public accessor for [type], mirroring the mermaid sketch's `getType()`. */
    fun getType(): PyType = type

    /**
     * Calls this object as a Python callable: `self(*args, **kwargs)`.
     *
     * The mermaid sketch lists a family of `invoke(arg0)`, `invoke(arg0, arg1)`, ...,
     * `invoke(vararg args)` overloads; those collapse into this single vararg +
     * named-kwargs signature here since Kotlin varargs already cover the arity-specific
     * overloads without the boilerplate.
     */
    @Throws(PyException::class)
    open operator fun invoke(vararg args: PyObject, kwargs: Map<String, PyObject> = emptyMap()): PyObject {
        TODO("Not yet implemented")
    }

    /** `callable(self)`, i.e. whether [invoke] has any chance of succeeding. */
    open fun isCallable(): Boolean {
        TODO("Not yet implemented")
    }

    /** `bool(self)`. */
    open fun isTruthy(): Boolean {
        TODO("Not yet implemented")
    }

    /** `repr(self)`. */
    open fun repr(): String {
        TODO("Not yet implemented")
    }

    /** `PyObject_RichCompare(self, other, op)`, i.e. the Python-level `<`, `<=`, `==`, `!=`, `>`, `>=` operators. */
    open fun richCompare(other: PyObject, op: PyCompareOp): Boolean {
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        // TODO: null check
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
        type.decRef()
    }

    // TODO: 밑에 세 함수 수정 (return type 불일치 등)
//    operator fun invoke(arg0: PyObject): PyObject {
//        return PyObject_CallObject(pointer, arg0)
//    }
//
//    operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject {
//        return PyObject_CallObject(pointer, arg0, arg1)
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