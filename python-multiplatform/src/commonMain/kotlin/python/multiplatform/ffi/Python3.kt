package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.modules.PyModule
import python.native.ffi.*


object Python3 {
    /**
     * Check if Python is initialized
     */
    var isInitialized: Boolean = Py_IsInitialized() != 0
        private set

    /**
     * Ensure Python is initialized
     */
    inline fun <T : Any> withPython(block: () -> T): T {
        if (!isInitialized) throw IllegalStateException("Python is not initialized")
        return memScoped {
            block()
        }
    }

    /**
     * Initialize Python
     */
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

    /**
     * Finalize Python (Will not be able to re-initialize after this | Let this function be internal)
     */
    internal fun finalize(silent: Boolean = false) {
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

    /**
     * Run Python main module
     */
    fun runMain(moduleName: String) {
        withPython {
            PyRun_SimpleString("import sys\nsys.argv[1] = '$moduleName'\n")
            Py_RunMain()
            // TODO: Add error handling
        }
    }

    /**
     * Run Python script as an application (Automatically initializes Python)
     */
    fun runApp(argv: Array<String>) {
        //Py_BytesMain(argv)
        // TODO: Add error handling
    }

    /**
     * Run Simple String
     */
    fun exec(command: String) {
        return withPython {
            if (PyRun_SimpleString(command) != 0) {
                // TODO: Attach the actual Python exception (PyErr_Fetch/PyErr_GetRaisedException) via PyException.fromCurrentError()
                throw PyException("Python exec failed")
            }
        }
    }

    /**
     * Evaluate Python script
     */
    fun eval(str: String, start: Int, globals: PyObject, locals: PyObject): PyObject {
        withPython {
            val result = PyRun_String(str, start, globals.pointer, locals.pointer)
            if (result == null) {
                // TODO: Attach the actual Python exception (PyErr_Fetch/PyErr_GetRaisedException) via PyException.fromCurrentError()
                throw PyException("Python eval failed (result is null)")
            } else {
                // TODO: 레퍼런스 카운팅
                return PyObject(result, false)
            }
        }
    }

    /**
     * Import Python module
     */
    fun import(name: String): PyModule {
        // TODO: 실제 모듈이 있는지 검사하는 코드 추가
        val pyModuleDict: NativePointer? = PyImport_GetModuleDict() // 변수 이름 적절?
        if (pyModuleDict == null) throw PyException("Failed to import module (Failed to get module dictionary)")

        val pyStringFromName: NativePointer? = PyUnicode_FromString(name) // 변수 이름 적절?
        if (pyStringFromName == null) throw PyException("Failed to import module (Failed to get module name)")

        if (PyDict_Contains(pyModuleDict, pyStringFromName) == 0) throw PyException("Failed to import module (Module not found)")

        val module: NativePointer? = PyImport_ImportModule(name)
        if (module == null) throw PyException("Failed to import module")

        return PyModule(module, false)
    }

    val version by lazy { withPython { Py_GetVersion() ?: throw IllegalStateException("Failed to get Python version") } }
    val platform by lazy { withPython { Py_GetPlatform() ?: throw IllegalStateException("Failed to get Python platform") } }
    val copyright by lazy { withPython { Py_GetCopyright() ?: throw IllegalStateException("Failed to get Python copyright") } }
    val compiler by lazy { withPython { Py_GetCompiler() ?: throw IllegalStateException("Failed to get Python compiler") } }
    val buildInfo by lazy { withPython { Py_GetBuildInfo() ?: throw IllegalStateException("Failed to get Python build info") } }
}
