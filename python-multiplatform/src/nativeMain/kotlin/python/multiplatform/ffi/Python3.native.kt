package python.multiplatform.ffi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import python.native.ffi.Py_Finalize
import python.native.ffi.Py_Initialize
import python.native.ffi.Py_IsInitialized


@OptIn(ExperimentalForeignApi::class)
actual object Python3 {
    actual var isInitialized: Boolean = false
        private set

    actual fun initialize() {
        if (isInitialized) return
        memScoped {
            Py_Initialize()
            if (Py_IsInitialized() == 0) {
                // TODO: Add error handling
                throw IllegalStateException("Python initialization failed")
            }
            println("INFO: Python initialized successfully!")
            isInitialized = true
        }
    }

    actual fun finalize() {
        if (!isInitialized) return
        memScoped {
            Py_Finalize()
            // TODO: print error message if exists
            if (Py_IsInitialized() != 0) {
                throw IllegalStateException("Python finalization failed")
            }
            println("INFO: Python finalized successfully!")
            isInitialized = false
        }
    }
}
