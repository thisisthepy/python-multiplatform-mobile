package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `frozenset` object. Not present in the mermaid
 * sketch at all, but explicitly requested by the task; modelled as the
 * read-only counterpart to [PySet] (`Set<PyObject>` rather than
 * `MutableSet<PyObject>`), matching how `frozenset` relates to `set` in
 * Python itself.
 *
 * Backed by `PyFrozenSet_New`/`PySet_Contains` (the read-only query
 * functions are shared between `set` and `frozenset` in the C API).
 */
open class PyFrozenSet(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<Set<Any?>>, Set<PyObject> {

    companion object {
        /** The `PyType` for `frozenset` (`builtins.frozenset`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Builds a new Python `frozenset` containing (references to) [elements]. */
        fun fromSet(elements: Set<PyObject>): PyFrozenSet {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: Set<Any?>?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}

    /** Snapshot conversion to a plain Kotlin set, recursively converting elements ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]). */
    fun toNativeSet(): Set<Any?> = TODO("Not yet implemented")

    /** `self | other`. */
    fun union(other: PyFrozenSet): PyFrozenSet = TODO("Not yet implemented")

    /** `self & other`. */
    fun intersection(other: PyFrozenSet): PyFrozenSet = TODO("Not yet implemented")

    /** `self - other`. */
    fun difference(other: PyFrozenSet): PyFrozenSet = TODO("Not yet implemented")

    override val size: Int
        get() = TODO("Not yet implemented")

    override fun isEmpty(): Boolean = TODO("Not yet implemented")
    override fun iterator(): Iterator<PyObject> = TODO("Not yet implemented")
    override fun contains(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun containsAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
}
