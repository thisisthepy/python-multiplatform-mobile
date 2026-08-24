package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.native.ffi.UpcallStub

actual object UpcallBootstrap {
    actual fun publishToGlobals(): Boolean { return true }
}
