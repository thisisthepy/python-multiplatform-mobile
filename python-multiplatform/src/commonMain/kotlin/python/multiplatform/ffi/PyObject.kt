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
            incRef()
            // TODO: PyIncRef을 사용하는게 적절한 선택일까?
        }
    }

    /**
     * The Python type of this object, as the design sketch names it.
     *
     * On JVM targets this compiles to a `getType()` accessor, which is why there is no separate
     * `getType()` method -- declaring both clashes on the JVM signature and breaks every
     * subclass on Android and Desktop.
     */
    val Type: PyType by lazy {
        // PyObject_Type returns a new reference; PyType.getInstance's private
        // constructor stores it via PyObject(pointer, borrowed = false), i.e.
        // it takes ownership of exactly that reference (no extra incRef).
        val typePointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(pointer) }
            ?: throw pyErrorOrGeneric("Failed to get the type of this object")
        PyType.getInstance(typePointer)
    }

    /**
     * Takes an extra reference to this object.
     *
     * Refcounting is a C API call like any other and needs a thread state attached, so it goes
     * through [withGIL]. That is true of free-threaded builds too: removing the global lock
     * removes contention, not the requirement that the calling thread be attached.
     *
     * The initialisation check exists for [clean], which runs on a cleaner thread that may have
     * never touched Python and may run after `Py_Finalize()`; attaching to a finalized
     * interpreter is invalid.
     */
    protected fun incRef() {
        if (python.multiplatform.ffi.Python3.isInitialized) withGIL { Py_IncRef(pointer) }
    }

    /** Releases one reference. See [incRef] for why the GIL and the initialisation check are here. */
    protected fun decRef() {
        if (python.multiplatform.ffi.Python3.isInitialized) withGIL { Py_DecRef(pointer) }
    }

    @Throws(PyException::class)
    fun getAttr(name: String): PyObject {
        // PyObject_GetAttrString: new reference on success, null + AttributeError
        // (or similar) set on the error indicator on failure.
        val attr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, name) }
            ?: throw pyErrorOrGeneric("Attribute '$name' not found")
        return PyObject(attr, false)
    }

    fun getAttrOrNull(name: String): PyObject? {
        val attr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, name) }
        if (attr == null) {
            // A missing attribute sets the Python error indicator (typically
            // AttributeError). This is the "OrNull" variant -- the caller has
            // opted out of exception handling -- so the indicator must be
            // cleared here rather than left set: the C API contract is that
            // you must not call back into it with a pending exception, and
            // leaving one set would silently corrupt whatever Python call
            // runs next (observed as spurious failures in unrelated,
            // logically unconnected calls further down the line).
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
            return null
        }
        return PyObject(attr, false)
    }

    @Throws(PyException::class)
    fun setAttr(name: String, value: PyObject) {
        if (python.multiplatform.ffi.Python3.withPython { PyObject_SetAttrString(pointer, name, value.pointer) } != 0) {
            throw pyErrorOrGeneric("Failed to set attribute '$name'")
        }
    }

    fun setAttrOrNull(name: String, value: PyObject?) {
        val target = value?.pointer ?: return
        // See getAttrOrNull() for why a failure here must clear the error
        // indicator rather than leave it set for whatever runs next.
        if (python.multiplatform.ffi.Python3.withPython { PyObject_SetAttrString(pointer, name, target) } != 0) {
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
        }
    }

    @Throws(PyException::class)
    fun delAttr(name: String) {
        if (python.multiplatform.ffi.Python3.withPython { PyObject_DelAttrString(pointer, name) } != 0) {
            throw pyErrorOrGeneric("Failed to delete attribute '$name'")
        }
    }

    fun delAttrOrNull(name: String) {
        // See getAttrOrNull() for why a failure here must clear the error
        // indicator rather than leave it set for whatever runs next.
        if (python.multiplatform.ffi.Python3.withPython { PyObject_DelAttrString(pointer, name) } != 0) {
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
        }
    }

    /**
     * Calls this object as a Python callable.
     *
     * The arity-specific overloads are not boilerplate: a `vararg` parameter allocates an
     * `Array<out PyObject>` at every call site, and one-to-three argument calls dominate FFI
     * use. These build the argument tuple directly and allocate nothing on the Kotlin side.
     * The `vararg` form remains for wider calls and for spreading an existing array.
     *
     * Keyword arguments live on [call] rather than here so the common path never pays for a
     * `Map` parameter it does not use.
     */
    @Throws(PyException::class)
    open operator fun invoke(): PyObject = python.multiplatform.ffi.Python3.withPython {
        // No tuple at all -- PyObject_CallNoArgs is CPython's dedicated zero-argument path.
        PyObject(PyObject_CallNoArgs(pointer) ?: throw pyErrorOrGeneric("Call failed"), false)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject): PyObject = callWithTuple(1) { t ->
        setArg(t, 0, arg0)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject = callWithTuple(2) { t ->
        setArg(t, 0, arg0); setArg(t, 1, arg1)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject, arg1: PyObject, arg2: PyObject): PyObject = callWithTuple(3) { t ->
        setArg(t, 0, arg0); setArg(t, 1, arg1); setArg(t, 2, arg2)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject, arg1: PyObject, arg2: PyObject, arg3: PyObject): PyObject = callWithTuple(4) { t ->
        setArg(t, 0, arg0); setArg(t, 1, arg1); setArg(t, 2, arg2); setArg(t, 3, arg3)
    }

    @Throws(PyException::class)
    open operator fun invoke(vararg args: PyObject): PyObject {
        if (args.isEmpty()) return invoke()
        return callWithTuple(args.size) { t ->
            for (i in args.indices) setArg(t, i, args[i])
        }
    }

    /**
     * `self(*args, **kwargs)`. Separate from [invoke] because keyword arguments require
     * building a dict as well as a tuple, and callers that do not need them should not pay
     * for the parameter.
     */
    @Throws(PyException::class)
    open fun call(args: Array<out PyObject> = emptyArray(), kwargs: Map<String, PyObject>): PyObject =
        python.multiplatform.ffi.Python3.withPython {
            if (kwargs.isEmpty()) return@withPython invoke(*args)

            val argTuple = PyTuple_New(args.size.toLong()) ?: throw pyErrorOrGeneric("Failed to build argument tuple")
            try {
                for (i in args.indices) {
                    // PyTuple_SetItem steals the reference it is given, and args[i].pointer stays
                    // owned by args[i], so hand the tuple a fresh +1 instead of that reference.
                    Py_IncRef(args[i].pointer)
                    if (PyTuple_SetItem(argTuple, i.toLong(), args[i].pointer) != 0) {
                        throw pyErrorOrGeneric("Failed to populate argument tuple")
                    }
                }
                val kwargsDict = PyDict_New() ?: throw pyErrorOrGeneric("Failed to build keyword argument dict")
                try {
                    for ((key, value) in kwargs) {
                        // PyDict_SetItemString does not steal; CPython increfs internally.
                        if (PyDict_SetItemString(kwargsDict, key, value.pointer) != 0) {
                            throw pyErrorOrGeneric("Failed to populate keyword argument dict")
                        }
                    }
                    PyObject(PyObject_Call(pointer, argTuple, kwargsDict) ?: throw pyErrorOrGeneric("Call failed"), false)
                } finally {
                    Py_DecRef(kwargsDict)
                }
            } finally {
                Py_DecRef(argTuple)
            }
        }

    /**
     * Builds an argument tuple of [size], lets [fill] populate it, and calls this object with it.
     * The whole sequence runs under a single GIL acquisition rather than one per C API call.
     */
    private inline fun callWithTuple(size: Int, fill: (NativePointer) -> Unit): PyObject =
        python.multiplatform.ffi.Python3.withPython {
            val argTuple = PyTuple_New(size.toLong()) ?: throw pyErrorOrGeneric("Failed to build argument tuple")
            try {
                fill(argTuple)
                PyObject(PyObject_CallObject(pointer, argTuple) ?: throw pyErrorOrGeneric("Call failed"), false)
            } finally {
                Py_DecRef(argTuple)
            }
        }

    /** Stores [arg] at [index]. Caller must already hold the GIL. */
    private fun setArg(tuple: NativePointer, index: Int, arg: PyObject) {
        // PyTuple_SetItem steals the reference; arg keeps its own, so give the tuple a fresh +1.
        Py_IncRef(arg.pointer)
        if (PyTuple_SetItem(tuple, index.toLong(), arg.pointer) != 0) {
            throw pyErrorOrGeneric("Failed to populate argument tuple")
        }
    }

    /** `callable(self)`, i.e. whether [invoke] has any chance of succeeding. */
    open val isCallable: Boolean
        get() = python.multiplatform.ffi.Python3.withPython { PyCallable_Check(pointer) } != 0

    /** `bool(self)`. Kotlin has no truthiness protocol, so this stays an explicit query. */
    open val isTruthy: Boolean
        get() {
            val result = python.multiplatform.ffi.Python3.withPython { PyObject_IsTrue(pointer) }
            if (result < 0) throw pyErrorOrGeneric("Failed to evaluate truthiness")
            return result != 0
        }

    /** `repr(self)`. */
    open fun repr(): String {
        // PyObject_Repr: new reference on success, null + exception set on failure.
        val reprPointer = python.multiplatform.ffi.Python3.withPython { PyObject_Repr(pointer) } ?: throw pyErrorOrGeneric("Failed to compute repr()")
        val result = python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(reprPointer) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(reprPointer) }
        return result ?: throw pyErrorOrGeneric("Failed to decode repr() result")
    }

    /** `python.multiplatform.ffi.Python3.withPython { PyObject_RichCompare(self, other, op) }`, i.e. the Python-level `<`, `<=`, `==`, `!=`, `>`, `>=` operators. */
    open fun richCompare(other: PyObject, op: PyCompareOp): Boolean {
        // PyObject_RichCompare: new reference to the (usually bool) result on
        // success, null + exception set on failure.
        val resultPointer = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompare(pointer, other.pointer, op.opId) }
            ?: throw pyErrorOrGeneric("Comparison failed")
        val truthy = python.multiplatform.ffi.Python3.withPython { PyObject_IsTrue(resultPointer) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(resultPointer) }
        if (truthy < 0) throw pyErrorOrGeneric("Failed to evaluate comparison result")
        return truthy != 0
    }

    /**
     * Bridges Python's rich comparison to Kotlin's `<`, `<=`, `>` and `>=`.
     *
     * Python has no single three-way comparison, so this asks `<` and then `>`; a type that
     * implements neither raises rather than silently reporting equality. Kotlin's `==` is left
     * to [equals], which compares identity of the underlying pointer -- deliberately not the
     * same question as Python's `==`, which [richCompare] answers.
     */
    open operator fun compareTo(other: PyObject): Int = when {
        richCompare(other, PyCompareOp.LT) -> -1
        richCompare(other, PyCompareOp.GT) -> 1
        else -> 0
    }

    override fun toString(): String {
        // PyObject_Str returns a new reference; release it once we've copied
        // the UTF-8 contents out into a Kotlin String. toString() is not
        // declared to throw, so on failure this clears whatever error
        // PyObject_Str set (via fromCurrentError()) and falls back to a
        // placeholder rather than propagating it.
        val strPointer = python.multiplatform.ffi.Python3.withPython { PyObject_Str(pointer) } ?: run {
            val message = PyException.fromCurrentError()?.errMsg
            return "<error converting to str${message?.let { ": $it" } ?: ""}>"
        }
        val result = python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(strPointer) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(strPointer) }
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
        // python.multiplatform.ffi.Python3.withPython { PyObject_Type() } FFI round-trip) purely as a side effect of cleanup.
        decRef()
    }

    // TODO: 밑에 세 함수 수정 (return type 불일치 등)
//    operator fun invoke(arg0: PyObject): PyObject {
//        return python.multiplatform.ffi.Python3.withPython { PyObject_CallObject(pointer, arg0) }
//    }
//
//    operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject {
//        return python.multiplatform.ffi.Python3.withPython { PyObject_CallObject(pointer, arg0, arg1) }
//    }
//
//    //...
//
//    operator fun invoke(vararg args: PyObject): PyObject {
//        return python.multiplatform.ffi.Python3.withPython { PyObject_CallObject(pointer, args) }  // TODO: 이거는 변수 하나로 잡히던가? 아님 여러개인가?
//        // 리스트로 들어오는 거였던가?
//    }

//    actual fun pyLongFromLong(arg0: Long): Long {
//        if (!Python3.isInitialized) return -1
//        memScoped {
////            val pyLong = python.multiplatform.ffi.Python3.withPython { PyLong_FromLong(arg0) } // TODO: PyLong_FromLong을 PyLong_FromLongLong으로 교체
//            val pyLong = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(arg0) }
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
////            val ktLong = python.multiplatform.ffi.Python3.withPython { PyLong_AsLong(restoredPyObj) } // TODO: PyLong_AsLong을 PyLong_AsLongLong으로 교체
//            val ktLong = python.multiplatform.ffi.Python3.withPython { PyLong_AsLongLong(restoredPyObj) }
//            if (ktLong == -1L && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) {
//                throw IllegalStateException("Python long as long failed")
//            }
//            return ktLong
//        }
//    }
}