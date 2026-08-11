package python.multiplatform.ref

import python.native.ffi.NativePointer


actual abstract class PyAutoCloseable actual constructor(pointer: NativePointer): AutoCloseable {
    actual abstract fun clean()

    override fun close() {
        clean()
    }
}
