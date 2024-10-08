package python.multiplatform.ffi

import python.multiplatform.ffi.LibPythonManager.loadLibPython


actual object Python3 {
    actual var isInitialized: Boolean = false
        get() {
            field = internalIsInitialized()
            return field
        }
        private set

    init {
        loadLibPython()
        sayHello()  // TODO: Remove this line after testing
    }

    external fun sayHello()

    actual external fun initialize()
    private external fun internalIsInitialized(): Boolean
    actual external fun finalize()
}
