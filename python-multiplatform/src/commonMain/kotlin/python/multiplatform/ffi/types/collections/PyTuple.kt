package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `tuple` object, adopting the read-only
 * `List<PyObject>` per the mermaid sketch (`List <|.. PyTuple`); tuples are
 * immutable in Python so `MutableList` would be misleading.
 *
 * Backed directly by `PyTuple_New`/`PyTuple_GetItem`/`PyTuple_Size` --
 * unlike [PyList], the Stable ABI subset here does have dedicated
 * tuple-specific entry points for construction and element access.
 * `PyTuple_SetItem` also exists, but per CPython's own contract it is only
 * safe to use while building a tuple that has not yet been exposed/published
 * anywhere else -- it is exposed here only for [fromList]'s internal use,
 * not as a public mutator (tuples stay immutable from the outside).
 */
open class PyTuple(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<List<Any?>>, List<PyObject> {

    companion object {
        /** The `PyType` for `tuple` (`builtins.tuple`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Builds a new Python `tuple` containing (references to) [elements], in order. */
        fun fromList(elements: List<PyObject>): PyTuple {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: List<Any?>?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}

    /** Snapshot conversion to a plain Kotlin list, recursively converting elements ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]). */
    fun toNativeList(): List<Any?> = TODO("Not yet implemented")

    override val size: Int
        get() = TODO("Not yet implemented")

    override fun get(index: Int): PyObject = TODO("Not yet implemented")
    override fun isEmpty(): Boolean = TODO("Not yet implemented")
    override fun iterator(): Iterator<PyObject> = TODO("Not yet implemented")
    override fun contains(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun containsAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun indexOf(element: PyObject): Int = TODO("Not yet implemented")
    override fun lastIndexOf(element: PyObject): Int = TODO("Not yet implemented")
    override fun listIterator(): ListIterator<PyObject> = TODO("Not yet implemented")
    override fun listIterator(index: Int): ListIterator<PyObject> = TODO("Not yet implemented")
    override fun subList(fromIndex: Int, toIndex: Int): List<PyObject> = TODO("Not yet implemented")
}
