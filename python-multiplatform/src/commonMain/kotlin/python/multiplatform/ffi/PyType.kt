package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.types.collections.PyDict
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_GetIter
import python.native.ffi.PyObject_IsInstance
import python.native.ffi.PyObject_Type
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyType_GetName
import python.native.ffi.PyType_IsSubtype
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef


class PyType private constructor(pointer: NativePointer): PyObject(pointer, false) {
    companion object {
        private val cachedObjects: MutableMap<NativePointer, PyType> = mutableMapOf()
        fun getInstance(pointer: NativePointer): PyType = cachedObjects.getOrPut(pointer) { PyType(pointer) }
    }

    val name: String by lazy {
        // PyType_GetName: new reference on success.
        val namePtr: NativePointer = python.multiplatform.ffi.Python3.withPython { PyType_GetName(pointer) } ?: throw pyErrorOrGeneric("Failed to get the type's name")
        val nameStr: String? = python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(namePtr) }
        Py_DecRef(namePtr)
        nameStr ?: throw pyErrorOrGeneric("Failed to decode the type's name")
    }

    val baseType: PyType by lazy {
        // PyObject_GetAttrString: new reference; getInstance() takes ownership
        // of it when not already cached (see the comment on `getInstance`).
        val attrPtr: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "__base__") }
            ?: throw pyErrorOrGeneric("Failed to get __base__")
        getInstance(attrPtr)
    }

    /** `type.__bases__`, i.e. the type's immediate (non-transitive) base classes. */
    val baseTypes: List<PyType>
        get() = tupleAttrAsTypes("__bases__")

    /** Method Resolution Order, i.e. `type.__mro__` -- the linearised lookup order for attributes. */
    val mro: List<PyType> by lazy {
        tupleAttrAsTypes("__mro__")
    }

    /** Reads a tuple-valued attribute (e.g. `__bases__`, `__mro__`) off this type and wraps each entry as a [PyType]. */
    private fun tupleAttrAsTypes(attrName: String): List<PyType> {
        // PyObject_GetAttrString: new reference to the tuple.
        val tuplePointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, attrName) }
            ?: throw pyErrorOrGeneric("Failed to get $attrName")
        try {
            val size = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(tuplePointer) }
            if (size == -1L) throw pyErrorOrGeneric("Failed to get the size of $attrName")

            val list = ArrayList<PyType>(size.toInt())
            for (i in 0 until size) {
                // PyTuple_GetItem returns a *borrowed* reference; getInstance()
                // assumes ownership of a new reference for cache misses, so give
                // it its own +1. When the entry is already cached, this extra
                // reference is simply never released -- harmless for built-in
                // types, which CPython treats as immortal.
                val itemPointer = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(tuplePointer, i) }
                    ?: throw pyErrorOrGeneric("Failed to get $attrName[$i]")
                Py_IncRef(itemPointer)
                list.add(getInstance(itemPointer))
            }
            return list
        } finally {
            Py_DecRef(tuplePointer)
        }
    }

    val dict: PyDict by lazy {
        // TODO: Add null check for PyObject_GetAttrString. And you should replace PyObject_GetAttrString to python.multiplatform.ffi.Python3.withPython { PyType_GetDict() }.
        PyDict(python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "__dict__") }!!, false)
}

    init {
        if (!isPyTypeObject()) throw PyException("Object is not a type")
    }

    /** `python.multiplatform.ffi.Python3.withPython { PyType_IsSubtype(this, other) }`, i.e. Python's `issubclass(self, other)`. */
    fun isSubtypeOf(other: PyType): Boolean = python.multiplatform.ffi.Python3.withPython { PyType_IsSubtype(pointer, other.pointer) } != 0

    /**
     * Attempts to view [obj] as an instance of this type, raising [PyException]
     * (mirroring a Python `TypeError`) if it is not an instance of, or convertible to, this type.
     */
    @Throws(PyException::class)
    fun cast(obj: PyObject): PyObject {
        if (!isInstance(obj)) {
            throw PyTypeError("Object is not an instance of '$name'")
        }
        return obj
    }

    /** `python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(obj, this) }`, i.e. Python's `isinstance(obj, self)`. */
    fun isInstance(obj: PyObject): Boolean {
        val result = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(obj.pointer, pointer) }
        if (result < 0) throw pyErrorOrGeneric("isinstance() check failed")
        return result != 0
    }

    /** `PyObject_GetIter` applied to an instance of this type, when this type describes an iterable. */
    fun getIterator(): PyIterator {
        // Constructs a fresh, no-argument instance of this type and returns its iterator.
        val instance = invoke()
        val iterPointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetIter(instance.pointer) }
            ?: throw pyErrorOrGeneric("Instances of '$name' are not iterable")
        return PyIterator(iterPointer, false)
    }

    /** Calls this type object, i.e. constructs a new instance: `self(*args, **kwargs)`. */
    @Throws(PyException::class)
    override operator fun invoke(vararg args: PyObject, kwargs: Map<String, PyObject>): PyObject {
        // Type objects are themselves callable (`self(*args, **kwargs)` runs
        // `type.__call__`, i.e. `__new__` + `__init__`); reuse PyObject's
        // generic call machinery rather than duplicating it here.
        return super.invoke(*args, kwargs = kwargs)
    }

    /** `type.__new__(self)` -- allocates (but does not initialise) a new instance of this type. */
    fun __new__(): PyObject {
        // `__new__` is a static method taking the class as its first argument;
        // there's no dedicated tp_new FFI entry point here, so go through the
        // normal attribute + call path (`type.__new__(self)`).
        return getAttr("__new__").invoke(this)
    }

    /** `type.__init__(obj, *args)` -- initialises an already-allocated [obj] in place. */
    fun __init__(obj: PyObject, vararg args: PyObject) {
        obj.getAttr("__init__").invoke(*args)
    }

    private fun isPyTypeObject(): Boolean {
        // A "type" in the CPython sense is an object whose own type ("meta-type")
        // is `type` (or, in principle, a metaclass derived from it -- but the
        // FFI surface here has no PyType_Check/PyType_IsSubtype-friendly handle
        // on the builtin `type` object itself to test against, so this checks
        // the meta-type's __name__ instead of `pointer`'s own __name__, which
        // is the bug this fixes: reading `pointer.__name__` rejects every
        // legitimate type, since e.g. `int.__name__` is "int", never "type").
        val metaTypePointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(pointer) } ?: run {
            // PyObject_Type basically never fails (every live object has a
            // type), but if it somehow did, it would leave the error
            // indicator set; this method reports "not a type" rather than
            // propagating, so the indicator must be cleared here too --
            // otherwise it would silently poison whatever Python call runs
            // next (see the *OrNull helpers on PyObject for the same class
            // of bug).
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
            return false
        }
        val metaTypeNamePointer: NativePointer? = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(metaTypePointer, "__name__") }
        val metaTypeName: String? = metaTypeNamePointer?.let { python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(it) } }
        if (metaTypeNamePointer == null) python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }

        metaTypeNamePointer?.let { Py_DecRef(it) }
        Py_DecRef(metaTypePointer)

        return metaTypeName == "type"
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }
}