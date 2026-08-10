package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyCompareOp
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyFloat_AsDouble
import python.native.ffi.PyLong_AsInt
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyObject_CallNoArgs
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_RichCompareBool
import python.native.ffi.PyObject_Type
import python.native.ffi.PySequence_Tuple
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef

/**
 * Small helpers shared by the collection wrappers in this package.
 *
 * `EmbedAPI`'s Stable ABI subset has no generic `PyObject_Size` /
 * `PySequence_Size` / type-check family (`PyLong_Check`, `PyDict_Check`, ...)
 * -- only `PyTuple_Size` is exposed directly, and only for tuples. The
 * helpers below work around that gap using what *is* available (the
 * `__len__` protocol, `PyObject_RichCompareBool`, and dispatching on
 * `PyType.name`) rather than inventing new `expect` declarations.
 */

/**
 * `len(obj)` via the generic `__len__` protocol, since this ABI subset has
 * no `PyObject_Size`/`PySequence_Size`.
 */
internal fun pyLen(pointer: NativePointer): Int {
    val lenFn = PyObject_GetAttrString(pointer, "__len__")
        ?: throw PyException.fromCurrentError() ?: PyException("Object has no __len__")
    val result = PyObject_CallNoArgs(lenFn)
    Py_DecRef(lenFn) // bound method object -- new reference from PyObject_GetAttrString
    if (result == null) throw PyException.fromCurrentError() ?: PyException("__len__() call failed")
    val n = PyLong_AsInt(result)
    Py_DecRef(result) // __len__()'s return value -- new reference
    return n
}

/**
 * `a == b`, via the raw `PyObject_RichCompareBool` FFI call directly (opid
 * `Py_EQ` == 2, see [PyCompareOp.EQ]) rather than [PyObject.richCompare],
 * which is still `TODO` elsewhere -- this lets element-equality-dependent
 * operations here (`indexOf`, `retainAll`, ...) work independently of that.
 */
internal fun pyEquals(a: NativePointer, b: NativePointer): Boolean {
    val result = PyObject_RichCompareBool(a, b, PyCompareOp.EQ.opId)
    if (result == -1) throw PyException.fromCurrentError() ?: PyException("Comparison failed")
    return result == 1
}

/**
 * Derives the [PyType] of a freshly-built, not-yet-published, exclusively
 * owned scratch instance (e.g. an empty `list()`/`dict()` built only to read
 * its `type`), then releases that scratch instance -- used by each
 * collection wrapper's `TYPE` companion property.
 */
internal fun deriveTypeAndRelease(instancePointer: NativePointer): PyType {
    val typePtr = PyObject_Type(instancePointer)
        ?: throw PyException.fromCurrentError() ?: PyException("Failed to get PyType of scratch instance")
    Py_DecRef(instancePointer) // only needed to read its type; release the scratch instance itself
    return PyType.getInstance(typePtr)
}

/**
 * Snapshots an iterable Python object (typically a `set`/`frozenset`, which
 * support no indexed access) into a Kotlin `List<PyObject>`, via
 * `PySequence_Tuple` + `PyTuple_GetItem`. Used instead of driving a live
 * `PyIter_Next` loop so that mutating the *live* container afterwards (e.g.
 * `PySet`'s iterator `remove()`) never races with an in-progress raw
 * CPython iterator over the same container -- mutating a Python collection
 * while iterating it directly is undefined behaviour in CPython.
 */
internal fun snapshotElements(pointer: NativePointer): List<PyObject> {
    val tuplePtr = PySequence_Tuple(pointer)
        ?: throw PyException.fromCurrentError() ?: PyException("Failed to snapshot elements (PySequence_Tuple failed)")
    val count = PyTuple_Size(tuplePtr)
    val result = ArrayList<PyObject>(count.toInt())
    for (i in 0 until count) {
        // Borrowed reference into tuplePtr, valid only while tuplePtr is alive -- wrap with
        // borrowed = true so each element gets its own independent, incref'd reference before
        // the scratch tuple below is released.
        val itemPtr = PyTuple_GetItem(tuplePtr, i)!!
        result.add(PyObject(itemPtr, true))
    }
    Py_DecRef(tuplePtr) // scratch snapshot tuple, new reference, no longer needed
    return result
}

/**
 * Recursively converts a live [PyObject] to a "fully native" Kotlin
 * projection ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]).
 *
 * Dispatches on the Python-level type name (via [PyObject.getType]) since
 * this ABI subset has no `PyLong_Check`/`PyFloat_Check`/`PyDict_Check`/...
 * family to type-switch on directly. Falls back to `str(obj)` (via
 * [PyObject.toString]) for any type not explicitly handled -- not a fully
 * native representation for arbitrary user-defined objects, but always
 * available without depending on [python.multiplatform.ffi.conversion.PyContext]
 * (whose `autoConvert`/`proxyConvert` machinery is still `TODO` elsewhere).
 */
internal fun pyObjectToNative(obj: PyObject): Any? {
    return when (obj.getType().name) {
        "NoneType" -> null
        // bool is a subtype of int at the C level; PyLong_AsLongLong works on it directly.
        "bool" -> PyLong_AsLongLong(obj.pointer) != 0L
        "int" -> PyLong_AsLongLong(obj.pointer)
        "float" -> PyFloat_AsDouble(obj.pointer)
        "str" -> PyUnicode_AsUTF8(obj.pointer) ?: ""
        "list" -> PyList(obj.pointer, true).toNativeList()
        "tuple" -> PyTuple(obj.pointer, true).toNativeList()
        "dict" -> PyDict(obj.pointer, true).toNativeMap()
        "set" -> PySet(obj.pointer, true).toNativeSet()
        "frozenset" -> PyFrozenSet(obj.pointer, true).toNativeSet()
        // TODO: no generic fallback beyond str() for arbitrary user-defined objects --
        // a full implementation would hook into PyContext's (still-TODO) conversion machinery.
        else -> obj.toString()
    }
}
