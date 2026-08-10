package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyCompareOp
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.PyTypeChecks
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.basic.PyNone
import python.native.ffi.NativePointer
import python.native.ffi.PyFloat_AsDouble
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyObject_RichCompareBool
import python.native.ffi.PyObject_Type
import python.native.ffi.PySequence_Tuple
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef

/**
 * Small helpers shared by the collection wrappers in this package.
 */

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
 * `None` is checked first via the cheap, zero-FFI-call [PyNone.isNone]
 * (a pointer comparison against the already-resolved singleton). Everything
 * else is dispatched by fetching [obj]'s type *once* (`PyObject_Type`) and
 * comparing the resulting pointer against [PyTypeChecks]'s cached builtin
 * type objects -- plain Kotlin `NativePointer` equality, no further FFI
 * calls -- rather than the `PyType.name` string dispatch this used before
 * [PyTypeChecks] existed (which cost three FFI crossings, `PyObject_Type` +
 * `PyType_GetName` + `PyUnicode_AsUTF8`, and a UTF-8 decode, just to read a
 * name well enough to compare). This intentionally mirrors `PyLong_Check`'s
 * exact-type dispatch semantics, not `isinstance`'s subclass-inclusive ones:
 * a value of a *user-defined* subclass of e.g. `list` still falls through to
 * the `else` branch below, exactly as the old name-based dispatch did (its
 * exact `PyType.name` would have been the subclass's own name, not `"list"`).
 * Falls back to `str(obj)` (via [PyObject.toString]) for any type not
 * explicitly handled -- not a fully native representation for arbitrary
 * user-defined objects, but always available without depending on
 * [python.multiplatform.ffi.conversion.PyContext] (whose `autoConvert`/
 * `proxyConvert` machinery is still `TODO` elsewhere).
 */
internal fun pyObjectToNative(obj: PyObject): Any? {
    if (PyNone.isNone(obj)) return null

    val typePtr = PyObject_Type(obj.pointer)
        ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the type of this object")
    try {
        return when (typePtr) {
            // bool is a subtype of int at the C level; PyLong_AsLongLong works on it directly.
            PyTypeChecks.boolType -> PyLong_AsLongLong(obj.pointer) != 0L
            PyTypeChecks.intType -> PyLong_AsLongLong(obj.pointer)
            PyTypeChecks.floatType -> PyFloat_AsDouble(obj.pointer)
            PyTypeChecks.strType -> PyUnicode_AsUTF8(obj.pointer) ?: ""
            PyTypeChecks.listType -> PyList(obj.pointer, true).toNativeList()
            PyTypeChecks.tupleType -> PyTuple(obj.pointer, true).toNativeList()
            PyTypeChecks.dictType -> PyDict(obj.pointer, true).toNativeMap()
            PyTypeChecks.setType -> PySet(obj.pointer, true).toNativeSet()
            PyTypeChecks.frozensetType -> PyFrozenSet(obj.pointer, true).toNativeSet()
            // TODO: no generic fallback beyond str() for arbitrary user-defined objects --
            // a full implementation would hook into PyContext's (still-TODO) conversion machinery.
            else -> obj.toString()
        }
    } finally {
        Py_DecRef(typePtr) // PyObject_Type: new reference, only needed for the dispatch above
    }
}
