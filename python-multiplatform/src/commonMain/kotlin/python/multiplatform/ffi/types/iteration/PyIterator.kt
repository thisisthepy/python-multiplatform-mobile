package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/**
 * Wraps a Python iterator object (anything `iter()` can return, i.e. an
 * object exposing `__next__`) as a Kotlin [Iterator].
 *
 * The mermaid sketch also lists a `remove()` method on this interface, but
 * that has no counterpart in Python's iterator protocol (there is no
 * generic "remove the element I just yielded" operation -- mutation, where
 * supported at all, happens through the underlying container, e.g.
 * [python.multiplatform.ffi.types.collections.PyList.removeAt]). It is
 * intentionally omitted here rather than faked.
 */
class PyIterator(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), Iterator<PyObject> {

    /** Backed by `PyIter_Check` + a one-slot lookahead buffer filled by `PyIter_Next`. */
    override fun hasNext(): Boolean {
        TODO("Not yet implemented")
    }

    /** Backed by `PyIter_Next`; throws [NoSuchElementException] once the iterator is exhausted. */
    override fun next(): PyObject {
        TODO("Not yet implemented")
    }
}
