package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/**
 * Wraps a Python traceback object (`PyTracebackObject`, i.e. what
 * `PyException_GetTraceback` returns). Tracebacks form a singly linked list
 * from the outermost frame to the one where the exception was raised.
 *
 * `PyFrame` is explicitly out of scope for this pass (see task notes), so
 * [frame] is intentionally omitted; only the bits needed to render a
 * human-readable trace are exposed.
 */
class PyTraceback(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {

    /** The line number that was executing in this frame when the exception propagated through it. */
    val lineNumber: Int
        get() = TODO("Not yet implemented")

    /** Name of the source file for this frame, if known. */
    val fileName: String?
        get() = TODO("Not yet implemented")

    /** The next traceback entry (one frame further down the call stack), or `null` at the end of the chain. */
    val next: PyTraceback?
        get() = TODO("Not yet implemented")

    /** Renders this traceback (and the chain reachable via [next]) the way Python's `traceback` module would. */
    fun format(): String {
        TODO("Not yet implemented")
    }
}
