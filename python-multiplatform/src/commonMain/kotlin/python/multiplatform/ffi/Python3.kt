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

    /** `Py_file_input`, the compiler-mode token for a sequence of statements (as opposed to a single expression). */
    private const val PY_FILE_INPUT: Int = 257

    /**
     * Run Simple String
     *
     * Deliberately implemented on top of [PyRun_String] rather than
     * `PyRun_SimpleString`: the latter internally calls `PyErr_Print()` on
     * failure, which *prints and clears* the error indicator before we ever
     * get a chance to inspect it, so [PyException.fromCurrentError] would
     * always see nothing pending and every failure would be reported with a
     * generic message instead of the real Python exception (type included).
     */
    fun exec(command: String) {
        withPython {
            // PyImport_AddModuleRef: new/strong reference to the __main__ module.
            val modulePointer = PyImport_AddModuleRef("__main__")
                ?: throw pyErrorOrGeneric("Failed to access the __main__ module")
            try {
                // PyObject_GetAttrString: new reference to __main__'s globals dict.
                val globalsPointer = PyObject_GetAttrString(modulePointer, "__dict__")
                    ?: throw pyErrorOrGeneric("Failed to access __main__.__dict__")
                try {
                    val result = PyRun_String(command, PY_FILE_INPUT, globalsPointer, globalsPointer)
                        ?: throw pyErrorOrGeneric("Python exec failed")
                    // PyRun_String returns a new reference (usually None for
                    // statement-mode execution); we have no use for it here.
                    Py_DecRef(result)
                } finally {
                    Py_DecRef(globalsPointer)
                }
            } finally {
                Py_DecRef(modulePointer)
            }
        }
    }

    /**
     * Evaluate Python script
     */
    fun eval(str: String, start: Int, globals: PyObject, locals: PyObject): PyObject {
        return withPython {
            // PyRun_String: new reference on success, null + exception set on failure.
            val result = PyRun_String(str, start, globals.pointer, locals.pointer)
                ?: throw pyErrorOrGeneric("Python eval failed")
            PyObject(result, false)
        }
    }

    /**
     * Import Python module
     *
     * Previously this pre-checked `name in sys.modules` and failed fast if it
     * wasn't already present -- but `sys.modules` only holds modules that
     * have *already* been imported, so any first-time import was rejected
     * before [PyImport_ImportModule] (which does the actual importing) ever
     * ran. Let CPython's own import machinery attempt the import and report
     * failure through the error indicator instead.
     */
    fun import(name: String): PyModule {
        // PyImport_ImportModule: new reference on success, null + exception
        // (typically ModuleNotFoundError) set on failure.
        val module: NativePointer = PyImport_ImportModule(name)
            ?: throw pyErrorOrGeneric("Failed to import module '$name'")
        return PyModule(module, false)
    }

    val version by lazy { withPython { Py_GetVersion() ?: throw IllegalStateException("Failed to get Python version") } }
    val platform by lazy { withPython { Py_GetPlatform() ?: throw IllegalStateException("Failed to get Python platform") } }
    val copyright by lazy { withPython { Py_GetCopyright() ?: throw IllegalStateException("Failed to get Python copyright") } }
    val compiler by lazy { withPython { Py_GetCompiler() ?: throw IllegalStateException("Failed to get Python compiler") } }
    val buildInfo by lazy { withPython { Py_GetBuildInfo() ?: throw IllegalStateException("Failed to get Python build info") } }
}
