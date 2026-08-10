package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `set` object, adopting `MutableSet<PyObject>` per
 * the mermaid sketch (`MutableSet <|.. PySet`). See [PyList] for why this
 * does not also implement `PyIterable`.
 *
 * Backed by `PySet_New`/`PySet_Add`/`PySet_Discard`/`PySet_Contains`/
 * `PySet_Pop`/`PySet_Clear`.
 */
open class PySet(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<Set<Any?>>, MutableSet<PyObject> {

    companion object {
        /** The `PyType` for `set` (`builtins.set`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Builds a new Python `set` containing (references to) [elements]. */
        fun fromSet(elements: Set<PyObject>): PySet {
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
    fun union(other: PySet): PySet = TODO("Not yet implemented")

    /** `self & other`. */
    fun intersection(other: PySet): PySet = TODO("Not yet implemented")

    /** `self - other`. */
    fun difference(other: PySet): PySet = TODO("Not yet implemented")

    /** Removes and returns an arbitrary element (`PySet_Pop`), throwing if the set is empty. */
    fun pop(): PyObject = TODO("Not yet implemented")

    override val size: Int
        get() = TODO("Not yet implemented")

    override fun isEmpty(): Boolean = TODO("Not yet implemented")
    override fun iterator(): MutableIterator<PyObject> = TODO("Not yet implemented")
    override fun contains(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun containsAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun add(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun addAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun remove(element: PyObject): Boolean = TODO("Not yet implemented")
    override fun removeAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun retainAll(elements: Collection<PyObject>): Boolean = TODO("Not yet implemented")
    override fun clear() { TODO("Not yet implemented") }
}
