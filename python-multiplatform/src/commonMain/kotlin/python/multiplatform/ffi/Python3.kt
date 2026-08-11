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
    inline fun <T> withPython(block: () -> T): T {
        if (!isInitialized) throw IllegalStateException("Python is not initialized")
        return withGIL {
            memScoped {
                block()
            }
        }
    }

    /**
     * The thread state belonging to the thread that ran [initialize].
     *
     * `Py_Initialize()` leaves its calling thread holding the GIL. If that thread simply kept it,
     * no other thread could ever attach: `PyGILState_Ensure` would block forever waiting for a GIL
     * that is never released. So the state is saved away here immediately after initialisation,
     * leaving the GIL unheld, and every entry into Python goes through [withGIL] instead.
     *
     * `Py_Finalize()` must run with the GIL held, so [finalize] restores this first.
     */
    private var mainThreadState: NativePointer? = null

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
        // Release the GIL so other threads (in particular cleaner threads running
        // Py_DecRef) can attach via PyGILState_Ensure. Every C API call reachable from
        // commonMain and commonTest is now inside withPython{} or withGIL{}, so this is safe.
        // See ROADMAP §1 and §4 for the history and the previous revert.
        // See ROADMAP §1 and §4 for the history and the previous revert.
        if (mainThreadState == null) mainThreadState = PyEval_SaveThread()
    }

    /**
     * Finalize Python (Will not be able to re-initialize after this | Let this function be internal)
     */
    internal fun finalize(silent: Boolean = false) {
        if (!isInitialized) return
        // Py_Finalize() requires the GIL. Reclaim the state parked by initialize().
        mainThreadState?.let {
            PyEval_RestoreThread(it)
            mainThreadState = null
        }
        // Lowered BEFORE Py_Finalize(), not after. Cleaner threads consult this flag to decide
        // whether releasing a reference is still legal, and finalization is exactly the window
        // where it stops being legal -- leaving the flag up until afterwards let a cleaner pass
        // the check and call Py_DecRef while Py_Finalize() was tearing the interpreter down,
        // which crashed the process inside _PyObject_ClearFreeLists.
        //
        // With the flag down first, a cleaner arriving from here on returns without touching the
        // C API at all. One already inside its GIL scope still holds the GIL, and Py_Finalize()
        // waits for it, so that case finishes safely before teardown begins.
        isInitialized = false
        memScoped {
            Py_Finalize()
            // TODO: print error message if exists
            if (Py_IsInitialized() != 0) {
                isInitialized = true
                throw IllegalStateException("Python finalization failed")
            }
            if (!silent) println("INFO: Python finalized successfully!")
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
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(result) }
                } finally {
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(globalsPointer) }
                }
            } finally {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(modulePointer) }
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
        return withPython {
            // PyImport_ImportModule: new reference on success, null + exception
            // (typically ModuleNotFoundError) set on failure.
            val module: NativePointer = PyImport_ImportModule(name)
                ?: throw pyErrorOrGeneric("Failed to import module '$name'")
            PyModule(module, false)
        }
    }

    val version by lazy { withPython { Py_GetVersion() ?: throw IllegalStateException("Failed to get Python version") } }
    val platform by lazy { withPython { Py_GetPlatform() ?: throw IllegalStateException("Failed to get Python platform") } }
    val copyright by lazy { withPython { Py_GetCopyright() ?: throw IllegalStateException("Failed to get Python copyright") } }
    val compiler by lazy { withPython { Py_GetCompiler() ?: throw IllegalStateException("Failed to get Python compiler") } }
    val buildInfo by lazy { withPython { Py_GetBuildInfo() ?: throw IllegalStateException("Failed to get Python build info") } }
}
