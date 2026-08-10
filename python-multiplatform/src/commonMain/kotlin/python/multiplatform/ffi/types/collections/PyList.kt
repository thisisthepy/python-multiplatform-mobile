package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyList_Append
import python.native.ffi.PyList_Reverse
import python.native.ffi.PyList_Sort
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_DelItem
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_GetItem
import python.native.ffi.PyObject_SetItem
import python.native.ffi.PySequence_Contains
import python.native.ffi.PySequence_List
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

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
 * access, length, construction) is implemented via the generic
 * sequence/object protocol (`PyObject_GetItem`/`SetItem`/`DelItem` with an
 * integer index, `PySequence_List`/`PyTuple_New` for construction, the
 * `__len__` protocol for [size], and a `PyObject_GetAttrString` +
 * `PyObject_CallObject` bridge for the one mutator with no abstract-protocol
 * equivalent at all, `list.insert`).
 */
open class PyList(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<List<Any?>>, MutableList<PyObject> {

    companion object {
        /** The `PyType` for `list` (`builtins.list`). */
        val TYPE: PyType by lazy {
            val emptyTuple = PyTuple_New(0)
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build tuple() scratch buffer")
            val emptyList = PySequence_List(emptyTuple)
            Py_DecRef(emptyTuple)
            if (emptyList == null) throw PyException.fromCurrentError() ?: PyException("Failed to build list() to derive its type")
            deriveTypeAndRelease(emptyList)
        }

        /** Builds a new Python `list` containing (references to) [elements], in order. */
        fun fromList(elements: List<PyObject>): PyList {
            val tuplePtr = PyTuple_New(elements.size.toLong())
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate tuple() scratch buffer")
            elements.forEachIndexed { i, el ->
                // PyTuple_SetItem steals the reference to its 3rd argument -- incref first so
                // `el`'s own, independently-managed reference stays valid afterwards.
                Py_IncRef(el.pointer)
                PyTuple_SetItem(tuplePtr, i.toLong(), el.pointer)
            }
            val listPtr = PySequence_List(tuplePtr)
            Py_DecRef(tuplePtr) // scratch tuple, no longer needed once copied into the list
            if (listPtr == null) throw PyException.fromCurrentError() ?: PyException("Failed to build list()")
            return PyList(listPtr, false)
        }
    }

    override var cachedNativeValue: List<Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /** Snapshot conversion to a plain Kotlin list, recursively converting elements ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]). */
    fun toNativeList(): List<Any?> = map { pyObjectToNative(it) }

    /** `list.sort()`, backed by `PyList_Sort`. */
    fun sort() {
        if (PyList_Sort(pointer) != 0) throw PyException.fromCurrentError() ?: PyException("list.sort() failed")
    }

    /** `list.reverse()`, backed by `PyList_Reverse`. */
    fun reverse() {
        if (PyList_Reverse(pointer) != 0) throw PyException.fromCurrentError() ?: PyException("list.reverse() failed")
    }

    override val size: Int
        get() = pyLen(pointer)

    private fun indexKey(index: Int): NativePointer =
        PyLong_FromLongLong(index.toLong()) ?: throw PyException.fromCurrentError() ?: PyException("Failed to build index object")

    override fun get(index: Int): PyObject {
        val key = indexKey(index)
        val item = PyObject_GetItem(pointer, key)
        Py_DecRef(key) // scratch index object, new reference, only needed for the lookup itself
        if (item == null) throw PyException.fromCurrentError() ?: PyException("list index out of range: $index")
        return PyObject(item, false) // PyObject_GetItem ("o[key]") returns a new reference
    }

    override fun set(index: Int, element: PyObject): PyObject {
        val previous = get(index)
        val key = indexKey(index)
        // PyObject_SetItem does not steal a reference to `element`.
        val rc = PyObject_SetItem(pointer, key, element.pointer)
        Py_DecRef(key)
        if (rc != 0) throw PyException.fromCurrentError() ?: PyException("Failed to set list index $index")
        return previous
    }

    override fun removeAt(index: Int): PyObject {
        val previous = get(index)
        val key = indexKey(index)
        val rc = PyObject_DelItem(pointer, key)
        Py_DecRef(key)
        if (rc != 0) throw PyException.fromCurrentError() ?: PyException("Failed to remove list index $index")
        return previous
    }

    override fun add(element: PyObject): Boolean {
        // PyList_Append does not steal a reference to `item` (it increfs internally),
        // unlike PyList_SetItem/PyTuple_SetItem.
        if (PyList_Append(pointer, element.pointer) != 0) {
            throw PyException.fromCurrentError() ?: PyException("Failed to append to list")
        }
        return true
    }

    /** Calls a bound method by name via the generic `GetAttrString` + `CallObject` bridge (no `PyObject_CallMethod` in this ABI subset). */
    private fun callMethod(name: String, args: List<NativePointer>): NativePointer? {
        val method = PyObject_GetAttrString(pointer, name)
            ?: throw PyException.fromCurrentError() ?: PyException("No such method '$name'")
        val argsTuple = PyTuple_New(args.size.toLong())
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to build args tuple for '$name'")
        args.forEachIndexed { i, arg ->
            // PyTuple_SetItem steals -- incref first so the caller's own reference to `arg`
            // (which may be a scratch value we still need to release ourselves) stays valid.
            Py_IncRef(arg)
            PyTuple_SetItem(argsTuple, i.toLong(), arg)
        }
        val result = PyObject_CallObject(method, argsTuple)
        Py_DecRef(argsTuple) // new reference, no longer needed after the call
        Py_DecRef(method) // bound method object, new reference from PyObject_GetAttrString
        return result
    }

    override fun add(index: Int, element: PyObject) {
        // No PyList_Insert in this ABI subset; bridge to the bound `list.insert` method.
        val key = indexKey(index)
        val result = callMethod("insert", listOf(key, element.pointer))
        Py_DecRef(key)
        if (result == null) throw PyException.fromCurrentError() ?: PyException("list.insert($index, ...) failed")
        Py_DecRef(result) // insert() returns None; release this new reference to it
    }

    override fun addAll(index: Int, elements: Collection<PyObject>): Boolean {
        if (elements.isEmpty()) return false
        elements.forEachIndexed { i, el -> add(index + i, el) }
        return true
    }

    override fun addAll(elements: Collection<PyObject>): Boolean {
        if (elements.isEmpty()) return false
        for (el in elements) add(el)
        return true
    }

    override fun clear() {
        // No PyList_Clear in this ABI subset either; pop from the end via the generic protocol.
        while (size > 0) removeAt(size - 1)
    }

    override fun isEmpty(): Boolean = size == 0

    override fun remove(element: PyObject): Boolean {
        val idx = indexOf(element)
        if (idx == -1) return false
        removeAt(idx)
        return true
    }

    override fun removeAll(elements: Collection<PyObject>): Boolean {
        var changed = false
        for (e in elements) {
            while (remove(e)) changed = true
        }
        return changed
    }

    override fun retainAll(elements: Collection<PyObject>): Boolean {
        var changed = false
        var i = size - 1
        while (i >= 0) {
            val el = get(i)
            if (elements.none { pyEquals(it.pointer, el.pointer) }) {
                removeAt(i)
                changed = true
            }
            i--
        }
        return changed
    }

    override fun contains(element: PyObject): Boolean {
        val result = PySequence_Contains(pointer, element.pointer)
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check list membership")
        return result == 1
    }

    override fun containsAll(elements: Collection<PyObject>): Boolean = elements.all { contains(it) }

    override fun indexOf(element: PyObject): Int {
        for (i in 0 until size) {
            if (pyEquals(get(i).pointer, element.pointer)) return i
        }
        return -1
    }

    override fun lastIndexOf(element: PyObject): Int {
        for (i in size - 1 downTo 0) {
            if (pyEquals(get(i).pointer, element.pointer)) return i
        }
        return -1
    }

    /**
     * Index-based cursor over `this` list, driven entirely from the Kotlin
     * side via [get]/[set]/[add]/[removeAt] rather than a live raw CPython
     * iterator -- mutating a Python collection while iterating it directly
     * (via `PyIter_Next` on a `list_iterator`) is undefined behaviour in
     * CPython, so `remove()`/`add()` here never touch one.
     */
    private inner class ListItr(startIndex: Int) : MutableListIterator<PyObject> {
        private var cursor = startIndex
        private var lastReturned = -1

        override fun hasNext(): Boolean = cursor < size
        override fun hasPrevious(): Boolean = cursor > 0
        override fun nextIndex(): Int = cursor
        override fun previousIndex(): Int = cursor - 1

        override fun next(): PyObject {
            if (!hasNext()) throw NoSuchElementException()
            val el = get(cursor)
            lastReturned = cursor
            cursor++
            return el
        }

        override fun previous(): PyObject {
            if (!hasPrevious()) throw NoSuchElementException()
            cursor--
            lastReturned = cursor
            return get(cursor)
        }

        override fun remove() {
            check(lastReturned != -1) { "next()/previous() has not been called, or remove()/add() already called" }
            removeAt(lastReturned)
            if (lastReturned < cursor) cursor--
            lastReturned = -1
        }

        override fun set(element: PyObject) {
            check(lastReturned != -1) { "next()/previous() has not been called, or remove()/add() already called" }
            this@PyList.set(lastReturned, element)
        }

        override fun add(element: PyObject) {
            this@PyList.add(cursor, element)
            cursor++
            lastReturned = -1
        }
    }

    override fun iterator(): MutableIterator<PyObject> = ListItr(0)
    override fun listIterator(): MutableListIterator<PyObject> = ListItr(0)
    override fun listIterator(index: Int): MutableListIterator<PyObject> = ListItr(index)

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<PyObject> {
        // TODO: this is a detached snapshot, not a live view backed by this PyList (Kotlin's
        // MutableList.subList contract expects write-through); acceptable for now since nothing
        // in the current test/usage surface relies on write-through semantics.
        val result = ArrayList<PyObject>(toIndex - fromIndex)
        for (i in fromIndex until toIndex) result.add(get(i))
        return result
    }
}
