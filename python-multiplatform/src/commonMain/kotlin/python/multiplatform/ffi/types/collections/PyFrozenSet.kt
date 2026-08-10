package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyFrozenSet_New
import python.native.ffi.PyNumber_And
import python.native.ffi.PyNumber_Or
import python.native.ffi.PyNumber_Subtract
import python.native.ffi.PySet_Contains
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * Wrapper around a Python `frozenset` object. Not present in the mermaid
 * sketch at all, but explicitly requested by the task; modelled as the
 * read-only counterpart to [PySet] (`Set<PyObject>` rather than
 * `MutableSet<PyObject>`), matching how `frozenset` relates to `set` in
 * Python itself.
 *
 * Backed by `PyFrozenSet_New`/`PySet_Contains` (the read-only query
 * functions are shared between `set` and `frozenset` in the C API), plus
 * `PyNumber_Or`/`And`/`Subtract` for the set-algebra operations, same as
 * [PySet].
 */
open class PyFrozenSet(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<Set<Any?>>, Set<PyObject> {

    companion object {
        /** The `PyType` for `frozenset` (`builtins.frozenset`). */
        val TYPE: PyType by lazy {
            val emptyTuple = PyTuple_New(0)
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build tuple() scratch buffer")
            val emptyFrozenSet = PyFrozenSet_New(emptyTuple)
            Py_DecRef(emptyTuple)
            if (emptyFrozenSet == null) throw PyException.fromCurrentError() ?: PyException("Failed to build frozenset() to derive its type")
            deriveTypeAndRelease(emptyFrozenSet)
        }

        /** Builds a new Python `frozenset` containing (references to) [elements]. */
        fun fromSet(elements: Set<PyObject>): PyFrozenSet {
            val tuplePtr = PyTuple_New(elements.size.toLong())
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate tuple() scratch buffer")
            elements.forEachIndexed { i, el ->
                Py_IncRef(el.pointer) // PyTuple_SetItem steals; keep `el`'s own reference valid
                PyTuple_SetItem(tuplePtr, i.toLong(), el.pointer)
            }
            val setPtr = PyFrozenSet_New(tuplePtr)
            Py_DecRef(tuplePtr) // scratch tuple, no longer needed once copied into the frozenset
            if (setPtr == null) throw PyException.fromCurrentError() ?: PyException("Failed to build frozenset()")
            return PyFrozenSet(setPtr, false)
        }
    }

    override var cachedNativeValue: Set<Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /** Snapshot conversion to a plain Kotlin set, recursively converting elements ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]). */
    fun toNativeSet(): Set<Any?> = snapshotElements(pointer).mapTo(LinkedHashSet()) { pyObjectToNative(it) }

    /** `self | other`, via `PyNumber_Or`. */
    fun union(other: PyFrozenSet): PyFrozenSet {
        val result = PyNumber_Or(pointer, other.pointer)
            ?: throw PyException.fromCurrentError() ?: PyException("frozenset union failed")
        return PyFrozenSet(result, false)
    }

    /** `self & other`, via `PyNumber_And`. */
    fun intersection(other: PyFrozenSet): PyFrozenSet {
        val result = PyNumber_And(pointer, other.pointer)
            ?: throw PyException.fromCurrentError() ?: PyException("frozenset intersection failed")
        return PyFrozenSet(result, false)
    }

    /** `self - other`, via `PyNumber_Subtract`. */
    fun difference(other: PyFrozenSet): PyFrozenSet {
        val result = PyNumber_Subtract(pointer, other.pointer)
            ?: throw PyException.fromCurrentError() ?: PyException("frozenset difference failed")
        return PyFrozenSet(result, false)
    }

    override val size: Int
        get() = pyLen(pointer)

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): Iterator<PyObject> = snapshotElements(pointer).iterator()

    override fun contains(element: PyObject): Boolean {
        val result = PySet_Contains(pointer, element.pointer)
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check frozenset membership")
        return result == 1
    }

    override fun containsAll(elements: Collection<PyObject>): Boolean = elements.all { contains(it) }
}
