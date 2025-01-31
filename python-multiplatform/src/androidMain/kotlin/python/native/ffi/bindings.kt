package python.native.ffi

import python.native.ffi.manager.loadLibPython


object bindings {
    init {
        loadLibPython()
    }

    external fun Py_Initialize()
    external fun Py_InitializeEx(sigint: Int)
    //external fun Py_InitializeFromConfig()
    external fun Py_IsInitialized(): Int
    external fun Py_Finalize()
    external fun Py_FinalizeEx(): Int
}
