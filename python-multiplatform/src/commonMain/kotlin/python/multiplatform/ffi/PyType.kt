package python.multiplatform.ffi

import python.multiplatform.ffi.types.collections.PyDict
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyObject_GetAttr
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_Type
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyType_GetName
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef


// TODO: PyObject를 PyTypeObject 자리에 넣어도 될까?
// TODO: 어떤 instance의 type을 알고 싶을 때 PyObject_Type 사용?
// TODO: __slots__를 사용하는 type을 고려 할 필요가 있을까?
class PyType(pointer: NativePointer): PyObject(pointer, false) {
    val name: String by lazy {
        PyUnicode_AsUTF8(PyType_GetName(pointer)!!)!!
    }

    val baseType: PyType by lazy {
        PyType(PyObject_GetAttrString(pointer, "__base__")!!)
    } // TODO: 어떻게 가져올지 알아보기. 현재 직접적으로 부모 클래스에 접근하는 API는 없어보임.
//    val baseTypes: List<PyType> // TODO: PyObject_GetAttrString로 가져오기
//    val mro: List<PyType>
//    val dict: PyDict // TODO: PyType_GetDict 사용

    init {
        if (!isPyTypeObject()) throw IllegalArgumentException("Object is not a type")

        var temp: MutableList<PyType> = mutableListOf<PyType>()
        // TODO: PyObject_GetAttrString return값이 Tuple인지도 확인 해야할까?
        val basesPyObject: NativePointer = PyObject_GetAttrString(pointer, "__bases__").let {it ?: throw NullPointerException("Failed to get base types")}
        val basesSize: Long = PyTuple_Size(basesPyObject).let {
            if (it == -1L) throw IllegalStateException("Failed to get base types size")
            else it
        }
        for (i in 0 until basesSize) {
            // TODO: type check 필요
//            temp.add(PyType(PyTuple_GetItem(basesPyObject, i)!!, true))
        }
    }

//    fun isSubtypeOf(other: PyType): Boolean {
//        // TODO: PyType_IsSubtype(PyTypeObject *a, PyTypeObject *b) 사용
//    }
//
//    fun cast(obj: PyObject): PyObject {
//
//    }
//
//    fun isInstance(obj: PyObject): Boolean {
//        // TODO: PyObject_IsInstance 이용
//    }
//
//    fun getIterator(): PyIterator {
//        // TODO: 이건 무슨 함수인가?
//    }
//
//    operator fun invoke(args: Array<>): PyObject {
//
//    }
//
//    fun __new__(): PyObject {
//
//    }
//
//    fun __init__(obj: PyObject, args: Array<>) {
//
//    }

    private fun isPyTypeObject(): Boolean {
        val pyTypeObject: NativePointer? = PyObject_Type(pointer)
        val typeNamePyObject: NativePointer? = PyObject_GetAttrString(pointer, "__name__")

        if (pyTypeObject != null && typeNamePyObject != null) {
            val typeName: String = PyUnicode_AsUTF8(typeNamePyObject).let {it ?: throw NullPointerException("Failed to get type name") }
            val result: Boolean = typeName == "type"

            Py_DecRef(typeNamePyObject)
            Py_DecRef(pyTypeObject)

            return result
        }

        return false
    }
}