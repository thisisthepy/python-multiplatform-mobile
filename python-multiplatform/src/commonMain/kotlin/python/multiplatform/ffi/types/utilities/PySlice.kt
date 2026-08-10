package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/** Wrapper around a Python `slice` object (`start:stop:step`). */
open class PySlice(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** Builds a new `slice(start, stop, step)`; any of the three may be `null` (Python's `None`). */
        fun of(start: Int? = null, stop: Int? = null, step: Int? = null): PySlice {
            TODO("Not yet implemented")
        }
    }

    val start: Int? get() = TODO("Not yet implemented")
    val stop: Int? get() = TODO("Not yet implemented")
    val step: Int? get() = TODO("Not yet implemented")
}
