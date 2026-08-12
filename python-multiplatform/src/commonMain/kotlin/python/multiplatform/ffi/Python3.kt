package python.multiplatform.ffi

import python.multiplatform.BuildConfig
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
            // There is nothing richer to report than this. `Py_Initialize()` returns void and,
            // per its own contract, treats a failed start-up as a *fatal* error -- it calls
            // Py_FatalError and aborts the process rather than returning, so a Kotlin frame that
            // could inspect an error indicator is never reached, and there is no interpreter left
            // to hold one anyway. The check below is therefore a belt-and-braces guard against a
            // no-op second call, not an error channel. (The API that does report status is
            // `Py_InitializeFromConfig`/`PyStatus`, which this binding does not use: it would
            // mean carrying a `PyConfig` struct layout across four platforms, and struct layout
            // is exactly what the Stable ABI does not promise.)
            if (Py_IsInitialized() == 0) {
                throw IllegalStateException("Python initialization failed")
            }
            if (!silent) println("INFO: Python initialized successfully!")
            isInitialized = true
        }
        // Release the GIL so other threads (in particular cleaner threads running
        // Py_DecRef) can attach via PyGILState_Ensure. Every C API call reachable from
        // commonMain and commonTest is now inside withPython{} or withGIL{}, so this is safe.
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
        // The checkpoint function dies with the interpreter; drop the pointer so a later
        // initialize() rebuilds it instead of calling through a dangling one.
        checkpointCallable = null
        memScoped {
            Py_Finalize()
            // No error message is printable here, and that is a property of the C API rather than
            // an omission. `Py_Finalize()` returns void; by the time it returns there is no
            // interpreter left to hold an error indicator, so `PyErr_*` cannot be consulted. The
            // only status available at all is `Py_FinalizeEx()`'s `int` -- 0, or -1 when flushing
            // buffered data failed -- which carries no message either. Switching to it is a real
            // (small) improvement and is recorded in ROADMAP §12 rather than done here, because
            // finalization is untested: the only caller is `artMain/JniExport.kt`, and a test
            // that exercises it destroys the interpreter the rest of the suite shares.
            if (Py_IsInitialized() != 0) {
                isInitialized = true
                throw IllegalStateException("Python finalization failed")
            }
            if (!silent) println("INFO: Python finalized successfully!")
        }
    }

    /**
     * Run Python main module.
     *
     * **Not usable as written, and the TODO this replaces ("add error handling") understated it.**
     * Reading the code against the C API contracts turns up three defects, none of which is an
     * error-handling gap:
     *
     * 1. `sys.argv[1] = ...` is an *assignment to an existing index*, so it needs `sys.argv` to
     *    already have two entries. `Py_Initialize()` explicitly does not set `sys.argv` (its own
     *    documentation says so), so this raises `IndexError` in an embedded interpreter --
     *    silently, because `PyRun_SimpleString` prints and clears the indicator itself and its
     *    return value is discarded here.
     * 2. `Py_RunMain()` **always finalizes the interpreter**, whether it returns or exits. So the
     *    interpreter is dead when this returns while [isInitialized] is still `true`, and the
     *    next C API call from anywhere touches a torn-down runtime.
     * 3. Its `Int` return is the process exit status and is thrown away, which is the part the
     *    original TODO named.
     *
     * Nothing in `src/` or `sample/` calls this, so it is a landmine rather than a live failure.
     * Fixing it is a design decision (what should "run a module" mean for an *embedded*
     * interpreter that must survive the call?) and is recorded in ROADMAP §12.
     */
    fun runMain(moduleName: String) {
        withPython {
            PyRun_SimpleString("import sys\nsys.argv[1] = '$moduleName'\n")
            Py_RunMain()
        }
    }

    /**
     * Run Python script as an application (Automatically initializes Python).
     *
     * **This function does nothing at all** -- its only statement is commented out, and so is the
     * `Py_BytesMain` `expect` declaration it would call (`EmbedAPI.kt`, two commented-out lines).
     * It neither initializes Python nor runs anything, and returns `Unit` regardless, so a caller
     * cannot tell. It has no caller in `src/` or `sample/`.
     *
     * `Py_BytesMain` cannot simply be declared, either: it takes `(int argc, char **argv)`, so
     * wiring it up means marshalling an array of C strings, which every platform in this build
     * does differently. Recorded in ROADMAP §12 with what it would cost.
     */
    fun runApp(argv: Array<String>) {
        //Py_BytesMain(argv)
    }

    /** `Py_file_input`, the compiler-mode token for a sequence of statements (as opposed to a single expression). */
    private const val PY_FILE_INPUT: Int = 257

    /**
     * Outermost [withGIL] scopes between automatic checkpoints when they are enabled.
     *
     * Chosen against the measured cost of one checkpoint relative to one outermost `withGIL`
     * scope: see `EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`. Amortised over
     * this many scopes the checkpoint is a small fraction of the attach/detach it rides on, while
     * still bounding how much the deferred-release queue can grow.
     */
    private const val DEFAULT_AUTO_DRAIN_INTERVAL: Int = 32


    ////////////////////////////////////////////////////////////////////////////////////////////
    // Eval-loop checkpoints (ROADMAP §9)
    ////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * CPython defers a set of housekeeping jobs to a *checkpoint* that only the evaluation loop
     * reaches. `_Py_HandlePending` — called from the `_CHECK_PERIODIC` uop that begins every
     * Python-level frame and closes every call instruction — is the single place that
     *
     * - merges the free-threaded build's biased reference-counting queue
     *   (`_PY_EVAL_EXPLICIT_MERGE_BIT` → `_Py_brc_merge_refcounts`),
     * - processes QSBR-deferred frees,
     * - runs a scheduled cyclic collection (`_PY_GC_SCHEDULED_BIT`),
     * - drains the pending-call queue and runs pending signal handlers.
     *
     * An embedder that drives CPython purely through the C API never executes a bytecode frame and
     * therefore never reaches one. On a free-threaded build that is not merely untidy: a
     * `Py_DecRef` issued from a thread that does not own the object cannot run `tp_dealloc` there,
     * so the object is pushed onto its owner's queue and stays alive until the owner reaches a
     * checkpoint. Every reference the cleaner gives back is in exactly that position, so a process
     * that only ever calls `PyObject_Call` grows without bound while its reference counts stay
     * perfectly correct.
     *
     * This is the cheapest way to reach a checkpoint: one call to a cached, empty, Python-level
     * function. It is not `exec("pass")`, which recompiles a module on every call.
     *
     * The merge happens on the **calling** thread, for objects that thread owns. That is the whole
     * reason this is not done from the cleaner: the cleaner owns nothing, so a checkpoint taken
     * there would drain an empty queue while running Python on a thread whose only job is to hand
     * references back. Use [PyGC_Collect] instead if you need to reclaim on behalf of some other
     * thread — it stops the world and merges every thread's queue, at a cost proportional to the
     * heap rather than to the queue.
     *
     * Safe to call at any time, including before initialisation and on a thread that has never
     * touched Python; it returns without doing anything when there is nothing it may legally do.
     */
    fun drainPendingReleases() {
        if (!isInitialized) return
        val tState = getThreadGILState()
        // Already inside a checkpoint (or on a thread that forbids them, i.e. a cleaner).
        if (tState.checkpointsSuppressed) return
        tState.checkpointsSuppressed = true
        try {
            withGIL { reachEvalCheckpointHoldingGIL() }
        } finally {
            tState.checkpointsSuppressed = false
            tState.checkpointCountdown = autoDrainInterval
        }
    }

    /**
     * How many outermost [withGIL] scopes a thread may enter between automatic checkpoints, or 0
     * to take none automatically. A checkpoint is additionally skipped when nothing has been
     * released since the previous one, so a workload that never drops a wrapper never pays.
     *
     * Defaults to on for free-threaded builds, where deferred release is the defect described on
     * [drainPendingReleases], and off otherwise — a build with the global lock frees on the
     * cleaner's `Py_DecRef` immediately, so there is nothing to reclaim and the checkpoint would
     * be pure overhead. Set it explicitly to opt in or out; [drainPendingReleases] always works
     * regardless of this setting.
     */
    var autoDrainInterval: Int = if (BuildConfig.pythonFreeThreaded) DEFAULT_AUTO_DRAIN_INTERVAL else 0

    /** Diagnostic counters for the checkpoint machinery. Test-visible. */
    internal object CheckpointCounter {
        /** Checkpoints actually taken. */
        var reached: Long = 0
        /** Checkpoints declined because the caller had an unread error indicator. */
        var skipped: Long = 0
        /** Checkpoints that could not be taken, or that came back with an exception. */
        var failed: Long = 0
    }

    /**
     * `ReleaseCounter.released` as of the last checkpoint. Read and written without
     * synchronisation on purpose: a stale value can only cost one redundant checkpoint or delay
     * one by an interval, and an `Int` does not tear.
     */
    internal var lastCheckpointReleaseMark: Int = -1

    /** The cached zero-argument Python-level function whose body is `pass`. */
    private var checkpointCallable: NativePointer? = null

    private const val CHECKPOINT_SOURCE = "def __pmp_eval_checkpoint__():\n    pass\n"

    /**
     * Compiles and evaluates [CHECKPOINT_SOURCE] once into a private globals dict and keeps a
     * strong reference to the resulting function.
     *
     * A module-level `def` needs no `__builtins__` entry of its own: frame setup falls back to
     * the interpreter's builtins when the globals mapping has none.
     *
     * The caller must hold the GIL.
     */
    private fun checkpointCallableHoldingGIL(): NativePointer? {
        checkpointCallable?.let { return it }
        val code = Py_CompileString(CHECKPOINT_SOURCE, "<python-multiplatform:checkpoint>", PY_FILE_INPUT)
            ?: run { PyErr_Clear(); return null }
        try {
            val globals = PyDict_New() ?: run { PyErr_Clear(); return null }
            try {
                val evaluated = PyEval_EvalCode(code, globals, globals)
                    ?: run { PyErr_Clear(); return null }
                Py_DecRef(evaluated)
                // PyDict_GetItemString returns a borrowed reference, so take one of our own.
                val borrowed = PyDict_GetItemString(globals, "__pmp_eval_checkpoint__")
                    ?: run { PyErr_Clear(); return null }
                Py_IncRef(borrowed)
                checkpointCallable = borrowed
                return borrowed
            } finally {
                Py_DecRef(globals)
            }
        } finally {
            Py_DecRef(code)
        }
    }

    /** Takes one checkpoint. The caller must hold the GIL and have suppressed re-entry. */
    internal fun reachEvalCheckpointHoldingGIL() {
        // Do not disturb an error indicator its owner has not read yet. Several call sites read
        // the indicator *after* their `withPython { ... }` scope has closed (see
        // `PyObject.getAttr`), and entering the eval loop can replace it — a pending signal
        // handler or a finaliser raising is enough.
        if (PyErr_Occurred() != null) {
            CheckpointCounter.skipped++
            return
        }
        val callable = checkpointCallableHoldingGIL() ?: run {
            CheckpointCounter.failed++
            return
        }
        val result = PyObject_CallNoArgs(callable)
        if (result == null) {
            // The body is `pass`, so nothing raised here belongs to the caller: it came out of
            // the checkpoint itself — a pending signal, a pending call, a finaliser. There is no
            // caller to hand it to, and leaving it set would surface as a spurious failure in
            // whatever ran next, so it is cleared and counted.
            PyErr_Clear()
            CheckpointCounter.failed++
        } else {
            Py_DecRef(result)
        }
        CheckpointCounter.reached++
    }

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
