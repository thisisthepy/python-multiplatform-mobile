package python.multiplatform.ffi

import python.native.ffi.NativePointer
import java.lang.ref.Cleaner


abstract class PyObjectAutoCloseable(open val pointer: NativePointer, borrowed: Boolean): AutoCloseable {
    private val cleaner = Cleaner.create()
    private val cleanable: Cleaner.Cleanable = cleaner.register(this) {
        //PyDecRef(this.pointer)
    }

    override fun close() {
        cleanable.clean()
    }
}
