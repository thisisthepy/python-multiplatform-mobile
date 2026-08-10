package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.iteration.PyIterable
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer

/** Wrapper around a Python `range` object. */
open class PyRange(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyIterable {
    companion object {
        /** Builds a new `range(start, stop, step)`. */
        fun of(start: Int, stop: Int, step: Int = 1): PyRange {
            TODO("Not yet implemented")
        }
    }

    val start: Int get() = TODO("Not yet implemented")
    val stop: Int get() = TODO("Not yet implemented")
    val step: Int get() = TODO("Not yet implemented")

    /** `len(range(...))`. */
    val size: Int get() = TODO("Not yet implemented")

    override fun iterator(): PyIterator = TODO("Not yet implemented")
}
