package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `list` object, adopting `MutableList<PyObject>` per
 * the mermaid sketch (`MutableList <|.. PyList`).
 *
 * The sketch also has a separate `PyIterable` in the hierarchy above
 * `PyMutableIterable <|-- PyList`, but Kotlin's `MutableList<T>` already
 * extends `Iterable<T>` (transitively via `MutableCollection`/`Collection`),
 * and its `iterator()` must return `MutableIterator<PyObject>` -- which
 * [python.multiplatform.ffi.types.iteration.PyIterator] does not implement
 * (it is a plain, read-only `Iterator`). Implementing both here would be a
 * genuine return-type conflict, so `PyIterable` is deliberately not mixed
 * into the collection types; it is reserved for wrappers around Python
 * objects that are iterable but are not already a Kotlin collection type.
 *
 * `EmbedAPI`'s Stable ABI subset has no `PyList_New`/`PyList_GetItem`/
 * `PyList_SetItem`/`PyList_Size` -- only `PyList_Append`, `PyList_Sort` and
 * `PyList_Reverse` are exposed directly. The rest of this interface (element
 * access, length, construction) is expected to be implemented later via the
 * generic sequence/object protocol (`PyObject_GetItem`/`SetItem`/`DelItem`
 * with an integer index, `list()` called through `PyObject_CallObject`,
 * etc.) rather than dedicated list-specific entry points.
 */
open class PyList(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<List<Any?>>, MutableList<PyObject> {

    companion object {
        /** The `PyType` for `list` (`builtins.list`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Builds a new Python `list` containing (references to) [elements], in order. */
        fun fromList(elements: List<PyObject>): PyList {
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

    /** `list.sort()`, backed by `PyList_Sort`. */
    fun sort() {
        TODO("Not yet implemented")
    }

    /** `list.reverse()`, backed by `PyList_Reverse`. */
    fun reverse() {
        TODO("Not yet implemented")
    }

    override fun iterator(): MutableIterator<PyObject> = TODO("Not yet implemented")

    override val size: Int
        get() = TODO("Not yet implemented")

    override fun get(index: Int): PyObject = TODO("Not yet implemented")
    override fun set(index: Int, element: PyObject): PyObject = TODO("Not yet implemented")
    override fun add(index: Int, element: PyObject) { TODO("Not yet implemented") }
    override fun add(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun addAll(index: Int, elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun addAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun clear() { TODO("Not yet implemented") }
    override fun isEmpty(): Boolean = TODO("Not yet implemented")
    override fun removeAt(index: Int): PyObject = TODO("Not yet implemented")
    override fun remove(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun removeAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun retainAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun contains(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun containsAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun indexOf(element: PyObject): Int = TODO("Not yet implemented")
    override fun lastIndexOf(element: PyObject): Int = TODO("Not yet implemented")
    override fun listIterator(): MutableListIterator<PyObject> = TODO("Not yet implemented")
    override fun listIterator(index: Int): MutableListIterator<PyObject> = TODO("Not yet implemented")
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<PyObject> = TODO("Not yet implemented")
}
