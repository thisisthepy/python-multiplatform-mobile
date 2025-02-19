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
//
//
//    fun checkPyError(): Boolean {
//        if (PyErr_Occurred() != null) {
//            PyErr_Clear();
//            return true
//        }
//        return false
//    }
//
//    actual fun pyLongFromLong(arg0: Long): Long {
//        if (!isInitialized) return -1
//        memScoped {
//            val pyLong = PyLong_FromLong(arg0)
//            if (pyLong == null) {
//                throw IllegalStateException("Python long from long failed")
//            }
//            return pyLong.toLong()
//        }
//    }
//
//    actual fun pyLongAsLong(arg0: Long): Long {
//        if (!isInitialized) return -1
//        memScoped {
//            val restoredPyObj: CValuesRef<_object>? = arg0.toCPointer()
//            val ktLong = PyLong_AsLong(restoredPyObj)
//            if (ktLong == -1L && PyErr_Occurred() != null) {
//                throw IllegalStateException("Python long as long failed")
//            }
//            return ktLong
//        }
//    }
}
