package python.multiplatform.ffi

import python.native.ffi.NativePointer
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner


actual open class PyObject actual constructor(actual val pointer: NativePointer, borrowed: Boolean): AutoCloseable {
    @OptIn(ExperimentalNativeApi::class)
    //private val cleaner = createCleaner(this) { clean() }
    // TODO: Fix this top-level function error

    private fun clean() {
        //PyDecRef(this.pointer)
    }

    override fun close() {
        clean()
    }
}
