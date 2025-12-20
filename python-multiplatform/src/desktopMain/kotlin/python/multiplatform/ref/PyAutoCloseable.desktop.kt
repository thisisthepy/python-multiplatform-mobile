package python.multiplatform.ref

import python.native.ffi.NativePointer
import java.lang.ref.Cleaner


actual abstract class PyAutoCloseable actual constructor(pointer: NativePointer): AutoCloseable {
    private val cleaner = Cleaner.create()
    private val cleanable: Cleaner.Cleanable = cleaner.register(this) {
        clean()
    }

    actual abstract fun clean()

    override fun close() {
        cleanable.clean()
    }
}
