package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.collections.PyDict
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyLong_AsInt
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyObject_CallNoArgs
import python.native.ffi.PyObject_GetAttr
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_Type
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyType_GetName
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef


class PyType private constructor(pointer: NativePointer): PyObject(pointer, false) {
    companion object {
        private val cachedObjects: MutableMap<NativePointer, PyType> = mutableMapOf()
        fun getInstance(pointer: NativePointer): PyType = cachedObjects.getOrPut(pointer) { PyType(pointer) }
    }

    val name: String by lazy {
        val namePtr: NativePointer? = PyType_GetName(pointer)
        if (namePtr == null) throw PyException("Failed to get pointer of name")

        val nameStr: String? = PyUnicode_AsUTF8(namePtr)
        if (nameStr == null) throw PyException("Failed to get String of name")

        nameStr
    }

    val baseType: PyType by lazy {
        val attrPtr: NativePointer? = PyObject_GetAttrString(pointer, "__base__")
        if (attrPtr == null) throw PyException("Failed to get __base__")

        PyType(attrPtr)
    }

    private var cachedBaseTypes: List<PyType>? = null
    private var cachedBaseTypesPointer: NativePointer? = null
    val baseTypes: List<PyType>
        get() {
            // TODO: 에러 발생 여부 확인이 필요한건가?
            val bases = PyObject_GetAttrString(pointer, "__bases__")  // tuple object
            if (bases != null) {

            } else {
                // TODO: 에러? 아니면 항상 성공 보장?
            }
            val baseTypes = cachedBaseTypes
            val baseTypesPointer = cachedBaseTypesPointer
            if (baseTypesPointer != null && bases?.address == baseTypesPointer.address) {
                return baseTypes ?: listOf()
            } else {
                // TODO: Do null check
                val lenFunc = PyObject_GetAttrString(pointer, "__len__")
                val lenObj = PyObject_CallNoArgs(lenFunc!!)
                val size = PyLong_AsInt(lenObj!!)


                val list: MutableList<PyType> = let {
                    val temp: MutableList<PyType> = mutableListOf()
                    for (i in 0 until size) {
                        temp[i] = getInstance(PyTuple_GetItem(bases!!, i.toLong())!!)
                    }
                    temp
                }


                return list.toList()
            }
        }

    /** Method Resolution Order, i.e. `type.__mro__` -- the linearised lookup order for attributes. */
    val mro: List<PyType> by lazy {
        TODO("Not yet implemented")
    }

    val dict: PyDict by lazy {
        // TODO: Add null check for PyObject_GetAttrString. And you should replace PyObject_GetAttrString to PyType_GetDict().
        PyDict(PyObject_GetAttrString(pointer, "__dict__")!!, false)
}

    init {
        if (!isPyTypeObject()) throw PyException("Object is not a type")

        var temp: MutableList<PyType> = mutableListOf<PyType>()
        // TODO: PyObject_GetAttrString return값이 Tuple인지도 확인 해야할까?
        val basesPyObject: NativePointer = PyObject_GetAttrString(pointer, "__bases__").let {it ?: throw PyException("Failed to get base types")}
        val basesSize: Long = PyTuple_Size(basesPyObject).let {
            if (it == -1L) throw PyException("Failed to get base types size")
            else it
        }
        for (i in 0 until basesSize) {
            // TODO: type check 필요
//            temp.add(PyType(PyTuple_GetItem(basesPyObject, i)!!, true))
        }
    }

    /** `PyType_IsSubtype(this, other)`, i.e. Python's `issubclass(self, other)`. */
    fun isSubtypeOf(other: PyType): Boolean {
        TODO("Not yet implemented")
    }

    /**
     * Attempts to view [obj] as an instance of this type, raising [PyException]
     * (mirroring a Python `TypeError`) if it is not an instance of, or convertible to, this type.
     */
    @Throws(PyException::class)
    fun cast(obj: PyObject): PyObject {
        TODO("Not yet implemented")
    }

    /** `PyObject_IsInstance(obj, this)`, i.e. Python's `isinstance(obj, self)`. */
    fun isInstance(obj: PyObject): Boolean {
        TODO("Not yet implemented")
    }

    /** `PyObject_GetIter` applied to an instance of this type, when this type describes an iterable. */
    fun getIterator(): PyIterator {
        TODO("Not yet implemented")
    }

    /** Calls this type object, i.e. constructs a new instance: `self(*args, **kwargs)`. */
    @Throws(PyException::class)
    override operator fun invoke(vararg args: PyObject, kwargs: Map<String, PyObject>): PyObject {
        TODO("Not yet implemented")
    }

    /** `type.__new__(self)` -- allocates (but does not initialise) a new instance of this type. */
    fun __new__(): PyObject {
        TODO("Not yet implemented")
    }

    /** `type.__init__(obj, *args)` -- initialises an already-allocated [obj] in place. */
    fun __init__(obj: PyObject, vararg args: PyObject) {
        TODO("Not yet implemented")
    }

    private fun isPyTypeObject(): Boolean {
        val pyTypeObject: NativePointer? = PyObject_Type(pointer)
        val typeNamePyObject: NativePointer? = PyObject_GetAttrString(pointer, "__name__")

        if (pyTypeObject != null && typeNamePyObject != null) {
            val typeName: String = PyUnicode_AsUTF8(typeNamePyObject).let {it ?: throw PyException("Failed to get type name") }
            val result: Boolean = typeName == "type"

            Py_DecRef(typeNamePyObject)
            Py_DecRef(pyTypeObject)

            return result
        }

        return false
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }
}