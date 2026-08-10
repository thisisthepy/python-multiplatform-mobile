package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ref.PyAutoCloseable
import python.native.ffi.NativePointer
import python.native.ffi.PyCallable_Check
import python.native.ffi.PyDict_New
import python.native.ffi.PyDict_SetItemString
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_Call
import python.native.ffi.PyObject_CallNoArgs
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_DelAttrString
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_IsTrue
import python.native.ffi.PyObject_Repr
import python.native.ffi.PyObject_RichCompare
import python.native.ffi.PyObject_SetAttrString
import python.native.ffi.PyObject_Str
import python.native.ffi.PyObject_Type
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
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

/**
 * Builds a [PyException] from CPython's current error indicator (via
 * [PyException.fromCurrentError]), falling back to a generic message if the
 * indicator happens not to be set (e.g. a `null`/failure return whose cause
 * was not, in fact, a live Python exception). Centralises the
 * "null means an exception is set" contract used throughout this file and
 * [PyType]/[python.multiplatform.ffi.Python3] (same package, so no import
 * needed at those call sites).
 */
internal fun pyErrorOrGeneric(fallback: String): PyException =
    PyException.fromCurrentError() ?: PyException(fallback)

// TODO: !!IMPORTANT!! We need to check the case where the pointer is null one more time. (PyObject, PyType, PyException)
open class PyObject(val pointer: NativePointer, borrowed: Boolean): PyAutoCloseable(pointer) {

    init {
        if (borrowed) {
            Py_IncRef(pointer)
            // TODO: PyIncRef을 사용하는게 적절한 선택일까?
        }
    }

    protected val type: PyType by lazy {
        // PyObject_Type returns a new reference; PyType.getInstance's private
        // constructor stores it via PyObject(pointer, borrowed = false), i.e.
        // it takes ownership of exactly that reference (no extra incRef).
        val typePointer: NativePointer = PyObject_Type(pointer)
            ?: throw pyErrorOrGeneric("Failed to get the type of this object")
        PyType.getInstance(typePointer)
    }

    protected fun incRef() {
        Py_IncRef(pointer)
    }

    protected fun decRef() {
        Py_DecRef(pointer)
    }

    @Throws(PyException::class)
    fun getAttr(name: String): PyObject {
        // PyObject_GetAttrString: new reference on success, null + AttributeError
        // (or similar) set on the error indicator on failure.
        val attr = PyObject_GetAttrString(pointer, name)
            ?: throw pyErrorOrGeneric("Attribute '$name' not found")
        return PyObject(attr, false)
    }

    fun getAttrOrNull(name: String): PyObject? {
        val attr = PyObject_GetAttrString(pointer, name)
        if (attr == null) {
            // A missing attribute sets the Python error indicator (typically
            // AttributeError). This is the "OrNull" variant -- the caller has
            // opted out of exception handling -- so the indicator must be
            // cleared here rather than left set: the C API contract is that
            // you must not call back into it with a pending exception, and
            // leaving one set would silently corrupt whatever Python call
            // runs next (observed as spurious failures in unrelated,
            // logically unconnected calls further down the line).
            PyErr_Clear()
            return null
        }
        return PyObject(attr, false)
    }

    @Throws(PyException::class)
    fun setAttr(name: String, value: PyObject) {
        if (PyObject_SetAttrString(pointer, name, value.pointer) != 0) {
            throw pyErrorOrGeneric("Failed to set attribute '$name'")
        }
    }

    fun setAttrOrNull(name: String, value: PyObject?) {
        val target = value?.pointer ?: return
        // See getAttrOrNull() for why a failure here must clear the error
        // indicator rather than leave it set for whatever runs next.
        if (PyObject_SetAttrString(pointer, name, target) != 0) {
            PyErr_Clear()
        }
    }

    @Throws(PyException::class)
    fun delAttr(name: String) {
        if (PyObject_DelAttrString(pointer, name) != 0) {
            throw pyErrorOrGeneric("Failed to delete attribute '$name'")
        }
    }

    fun delAttrOrNull(name: String) {
        // See getAttrOrNull() for why a failure here must clear the error
        // indicator rather than leave it set for whatever runs next.
        if (PyObject_DelAttrString(pointer, name) != 0) {
            PyErr_Clear()
        }
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
        if (args.isEmpty() && kwargs.isEmpty()) {
            val result = PyObject_CallNoArgs(pointer) ?: throw pyErrorOrGeneric("Call failed")
            return PyObject(result, false)
        }

        val argTuple = PyTuple_New(args.size.toLong()) ?: throw pyErrorOrGeneric("Failed to build argument tuple")
        for ((index, arg) in args.withIndex()) {
            // PyTuple_SetItem steals the reference to the item it's given.
            // `arg.pointer` is owned by `arg` for the rest of its lifetime, so
            // hand the tuple a fresh +1 rather than `arg`'s own reference.
            Py_IncRef(arg.pointer)
            if (PyTuple_SetItem(argTuple, index.toLong(), arg.pointer) != 0) {
                Py_DecRef(argTuple)
                throw pyErrorOrGeneric("Failed to populate argument tuple")
            }
        }

        try {
            if (kwargs.isEmpty()) {
                val result = PyObject_CallObject(pointer, argTuple) ?: throw pyErrorOrGeneric("Call failed")
                return PyObject(result, false)
            }

            val kwargsDict = PyDict_New() ?: throw pyErrorOrGeneric("Failed to build keyword argument dict")
            try {
                for ((key, value) in kwargs) {
                    // PyDict_SetItemString does NOT steal `value.pointer` -- CPython
                    // increfs it internally, so no extra incRef is needed here.
                    if (PyDict_SetItemString(kwargsDict, key, value.pointer) != 0) {
                        throw pyErrorOrGeneric("Failed to populate keyword argument dict")
                    }
                }
                val result = PyObject_Call(pointer, argTuple, kwargsDict) ?: throw pyErrorOrGeneric("Call failed")
                return PyObject(result, false)
            } finally {
                Py_DecRef(kwargsDict)
            }
        } finally {
            Py_DecRef(argTuple)
        }
    }

    /** `callable(self)`, i.e. whether [invoke] has any chance of succeeding. */
    open fun isCallable(): Boolean = PyCallable_Check(pointer) != 0

    /** `bool(self)`. */
    open fun isTruthy(): Boolean {
        val result = PyObject_IsTrue(pointer)
        if (result < 0) throw pyErrorOrGeneric("Failed to evaluate truthiness")
        return result != 0
    }

    /** `repr(self)`. */
    open fun repr(): String {
        // PyObject_Repr: new reference on success, null + exception set on failure.
        val reprPointer = PyObject_Repr(pointer) ?: throw pyErrorOrGeneric("Failed to compute repr()")
        val result = PyUnicode_AsUTF8(reprPointer)
        Py_DecRef(reprPointer)
        return result ?: throw pyErrorOrGeneric("Failed to decode repr() result")
    }

    /** `PyObject_RichCompare(self, other, op)`, i.e. the Python-level `<`, `<=`, `==`, `!=`, `>`, `>=` operators. */
    open fun richCompare(other: PyObject, op: PyCompareOp): Boolean {
        // PyObject_RichCompare: new reference to the (usually bool) result on
        // success, null + exception set on failure.
        val resultPointer = PyObject_RichCompare(pointer, other.pointer, op.opId)
            ?: throw pyErrorOrGeneric("Comparison failed")
        val truthy = PyObject_IsTrue(resultPointer)
        Py_DecRef(resultPointer)
        if (truthy < 0) throw pyErrorOrGeneric("Failed to evaluate comparison result")
        return truthy != 0
    }

    override fun toString(): String {
        // PyObject_Str returns a new reference; release it once we've copied
        // the UTF-8 contents out into a Kotlin String. toString() is not
        // declared to throw, so on failure this clears whatever error
        // PyObject_Str set (via fromCurrentError()) and falls back to a
        // placeholder rather than propagating it.
        val strPointer = PyObject_Str(pointer) ?: run {
            val message = PyException.fromCurrentError()?.errMsg
            return "<error converting to str${message?.let { ": $it" } ?: ""}>"
        }
        val result = PyUnicode_AsUTF8(strPointer)
        Py_DecRef(strPointer)
        return result ?: "<error decoding str>"
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
        // Release *this* object's own reference. The previous `type.decRef()`
        // both released the wrong object (the meta-type, not `pointer`) and
        // forced the lazy `type` property to materialise (an extra
        // PyObject_Type() FFI round-trip) purely as a side effect of cleanup.
        decRef()
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