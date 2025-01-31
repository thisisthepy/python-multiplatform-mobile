package python.multiplatform.ffi

import python.native.ffi.*


object Python3 {
    var isInitialized: Boolean = Py_IsInitialized() != 0
        private set

    fun initialize(silent: Boolean = false) {
        if (isInitialized) return
        memScoped {
            Py_Initialize()
            if (Py_IsInitialized() == 0) {
                // TODO: Add error handling
                throw IllegalStateException("Python initialization failed")
            }
            if (!silent) println("INFO: Python initialized successfully!")
            isInitialized = true
        }
    }

    fun finalize(silent: Boolean = false) {
        if (!isInitialized) return
        memScoped {
            Py_Finalize()
            // TODO: print error message if exists
            if (Py_IsInitialized() != 0) {
                throw IllegalStateException("Python finalization failed")
            }
            if (!silent) println("INFO: Python finalized successfully!")
            isInitialized = false
        }
    }

    //val builtins: Builtins

}
