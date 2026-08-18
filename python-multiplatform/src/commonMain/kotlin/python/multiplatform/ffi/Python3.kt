package python.multiplatform.ffi

import python.multiplatform.BuildConfig
import python.multiplatform.env.PythonHomeCheck
import python.multiplatform.env.PythonPayload
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
        if (isInitialized) {
            // Not a no-op: something else may have reached Py_Initialize() first (isInitialized is
            // seeded from Py_IsInitialized()), in which case this is the only call that will ever
            // get here and the consumer's payload has still not been put on sys.path. The hook
            // does its work at most once per process, so arriving here repeatedly is free.
            PythonPayload.installStagedRootsOnStartup()
            return
        }
        // Runs once per process, before the call whose own failure mode is either an uncatchable
        // `Py_FatalError()` abort or -- on a sandboxed `PYTHONHOME` -- a silent hang (see
        // PythonHomeCheck's doc comment). Cheap even so: one or two filesystem probes against a
        // handful of candidate paths, not a directory walk.
        PythonHomeCheck.verifyOrThrow()
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
        // The consumer's own Python code goes on sys.path here: after Py_Initialize() built the
        // list (nothing can add to it before that point) and before this function returns, so
        // before any import a caller can reach. `PYTHONHOME` above is the *standard library* and
        // is a separate mechanism entirely -- see PythonPayload for the whole reasoning, and for
        // the `autoInstall` switch that turns this off.
        PythonPayload.installStagedRootsOnStartup()
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
        // initialize() rebuilds it instead of calling through a dangling one. Same for the
        // runMain helper, which is cached the same way and would dangle the same way.
        checkpointCallable = null
        runModuleCallable = null
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
     * Runs the module [moduleName] as `__main__`, the way `python -m <moduleName>` does, in an
     * interpreter that is **still alive when this returns**.
     *
     * ### What this is instead of
     *
     * The body this replaces called `Py_RunMain()`, and the reason that is not a bug that can be
     * patched is in the header: `Py_RunMain()` is declared in `cpython/pylifecycle.h` — a
     * *lifecycle* function, and not part of the Limited API this binding otherwise targets — and
     * its contract is to run whatever `PyConfig.run_command`/`run_module`/`run_filename` names
     * **and then finalize Python**. There is no mode in which it leaves the runtime standing. So
     * it destroyed the interpreter it was asked to run a module in, while [isInitialized] still
     * said `true`. (`Py_BytesMain()` is not an escape either: it is the whole CLI `main`, and it
     * reaches the same `Py_RunMain()`. See [runApp].) The same old body also did
     * `sys.argv[1] = ...`, an assignment to an index that need not exist, through
     * `PyRun_SimpleString`, which prints and clears the error indicator so the resulting
     * `IndexError` was invisible.
     *
     * `PyImport_ImportModule(moduleName)` is the other obvious candidate and is *not* the same
     * thing twice over: it runs the module body under its own `__name__`, so the
     * `if __name__ == "__main__":` block that is usually the entire point does not fire, and it
     * caches the module in `sys.modules`, so calling it a second time runs nothing at all.
     *
     * ### What it does
     *
     * `runpy.run_module(moduleName, run_name="__main__", alter_sys=True)` — the standard library's
     * own answer, and the machinery CPython's `-m` switch itself goes through
     * (`runpy._run_module_as_main` shares `_get_module_details`/`_run_code` with it). It touches
     * no lifecycle function, so nothing here can finalize anything. Concretely, `alter_sys=True`
     * makes `runpy._run_module_code` wrap the execution in two context managers:
     *
     * - `_TempModule("__main__")` installs a **fresh** module as `sys.modules["__main__"]` for the
     *   duration and puts the previous one back in `__exit__`. The module therefore gets a clean
     *   `__main__` namespace, and the embedder's own `__main__` — the one [exec] writes into — is
     *   neither read nor written, and is restored whether the module returns or raises.
     * - `_ModifiedArgv0(spec.origin)` points `sys.argv[0]` at the module's file while it runs and
     *   restores it afterwards, which is what `python -m` does.
     *
     * ### `sys.argv`
     *
     * `sys.argv` is set to `[moduleName] + `[args] for the duration and **put back afterwards**,
     * including when the module raises. Two halves to that:
     *
     * - It has to be *set*, because `runpy`'s `_ModifiedArgv0.__enter__` reads `sys.argv[0]`
     *   before writing it. An embedded interpreter is not guaranteed to have a usable one — this
     *   is the same soft spot the old body fell into from the other side.
     * - It has to be *restored*, because it is process-global state that the caller did not ask
     *   to have rewritten. A module run is a nested activity here, not the process's reason for
     *   existing.
     *
     * Element 0 is a placeholder: `runpy` overwrites it with the module's origin, so what the
     * module actually observes is `[<module file>] + `[args], exactly as under `-m`.
     *
     * ### Exceptions, and `sys.exit()`
     *
     * An exception escaping the module body propagates as a [PyException] carrying the real
     * Python type, message and traceback, and the interpreter stays usable — a failing module is
     * an ordinary failure, not a poisoned runtime.
     *
     * `SystemExit` is deliberately **not** in that class. A module written to be run as `__main__`
     * uses `sys.exit(n)` to say "I am done, and this is my status"; CPython's own command line
     * agrees, catching `SystemExit` and turning it into the process exit status rather than
     * printing a traceback. The half of that behaviour an embedder must not inherit is the
     * *process* exit, so the status is returned instead of being acted on:
     *
     * - `sys.exit()` / `sys.exit(None)` / falling off the end of the module → `0`
     * - `sys.exit(n)` for an integer `n` → `n`
     * - `sys.exit(x)` for anything else → `x` is written to `sys.stderr` and `1` is returned,
     *   which is what CPython's `_Py_HandleSystemExit` does with it
     *
     * ### Cost
     *
     * This is not in the same class as [exec] and should not be reached for in a loop: every call
     * goes back through `importlib.util.find_spec`, obtains the module's code object again and
     * builds a fresh namespace. `RunMainTest.runMainCostAgainstExec` prints the measured
     * per-call cost of both side by side.
     *
     * `RunMainTest` is the guard; ROADMAP §12 records the decision.
     *
     * @param moduleName an absolute module or package name, as after `python -m`. A package runs
     *   its `__main__` submodule, since that is what `runpy._get_module_details` resolves to.
     * @param args the arguments the module should see as `sys.argv[1:]`.
     * @return the exit status: 0 for a clean run, otherwise whatever the module passed to
     *   `sys.exit()`.
     * @throws PyException if the module cannot be found, or if it raises anything other than
     *   `SystemExit`.
     */
    fun runMain(moduleName: String, args: List<String> = emptyList()): Int = withPython {
        val helper = runModuleHelperHoldingGIL()

        // The list the module will see as sys.argv. Element 0 is a placeholder that runpy
        // replaces with the module's origin (_ModifiedArgv0); the rest is args verbatim.
        // PyList_New: new reference.
        val argv = PyList_New(0)
            ?: throw pyErrorOrGeneric("Failed to build sys.argv for '$moduleName'")
        try {
            for (arg in listOf(moduleName) + args) {
                // PyUnicode_FromString: new reference. PyList_Append does *not* steal, so this
                // is released either way.
                val item = PyUnicode_FromString(arg)
                    ?: throw pyErrorOrGeneric("Failed to convert argument '$arg' for '$moduleName'")
                try {
                    if (PyList_Append(argv, item) != 0) {
                        throw pyErrorOrGeneric("Failed to append argument '$arg' for '$moduleName'")
                    }
                } finally {
                    Py_DecRef(item)
                }
            }

            // PyTuple_New: new reference. PyTuple_SetItem *steals* one reference per slot, and
            // steals it even when it fails, so nothing set below is released again here.
            val callArgs = PyTuple_New(2)
                ?: throw pyErrorOrGeneric("Failed to build the argument tuple for '$moduleName'")
            try {
                val name = PyUnicode_FromString(moduleName)
                    ?: throw pyErrorOrGeneric("Failed to convert the module name '$moduleName'")
                if (PyTuple_SetItem(callArgs, 0, name) != 0) {
                    throw pyErrorOrGeneric("Failed to build the argument tuple for '$moduleName'")
                }
                // The tuple takes a reference of its own; `argv`'s is still ours to release in
                // the outer finally.
                Py_IncRef(argv)
                if (PyTuple_SetItem(callArgs, 1, argv) != 0) {
                    throw pyErrorOrGeneric("Failed to build the argument tuple for '$moduleName'")
                }

                // PyObject_CallObject: new reference, or null with the indicator set. Anything
                // the module raised other than SystemExit arrives here -- the helper lets it
                // through untouched, so the type and traceback are the module's own.
                val result = PyObject_CallObject(helper, callArgs)
                    ?: throw pyErrorOrGeneric("Running module '$moduleName' as __main__ failed")
                try {
                    val status = PyLong_AsLongLong(result)
                    if (status == -1L && PyErr_Occurred() != null) {
                        throw pyErrorOrGeneric("Could not read the exit status of '$moduleName'")
                    }
                    return@withPython status.toInt()
                } finally {
                    Py_DecRef(result)
                }
            } finally {
                Py_DecRef(callArgs)
            }
        } finally {
            Py_DecRef(argv)
        }
    }

    /** The cached `__pmp_run_module__` helper compiled from [RUN_MODULE_SOURCE]. */
    private var runModuleCallable: NativePointer? = null

    /**
     * The Python side of [runMain].
     *
     * It is written in Python rather than assembled out of C API calls because every line of it
     * is a `try`/`finally` or an `except` — restoring `sys.argv` on both paths, and telling
     * `SystemExit` apart from a real failure. Expressing that through the C API would mean
     * `PyErr_GetRaisedException` plus a `PyErr_GivenExceptionMatches` against a `SystemExit` this
     * binding exposes no `PyExc_*` handle for, then re-raising by hand — more code, and all of it
     * in the one path that exists to report failures.
     *
     * What it deliberately does *not* do is catch anything but `SystemExit`: any other exception
     * leaves the helper with the indicator set, so [runMain]'s `PyObject_CallObject` returns null
     * and `pyErrorOrGeneric` builds a [PyException] out of the module's own error.
     */
    private const val RUN_MODULE_SOURCE = """
import runpy as _pmp_runpy
import sys as _pmp_sys


def __pmp_run_module__(mod_name, argv):
    _pmp_saved = _pmp_sys.argv if hasattr(_pmp_sys, "argv") else None
    _pmp_sys.argv = list(argv)
    try:
        try:
            _pmp_runpy.run_module(mod_name, run_name="__main__", alter_sys=True)
        except SystemExit as exc:
            code = exc.code
            if code is None:
                return 0
            if isinstance(code, int):
                return code
            try:
                _pmp_sys.stderr.write(str(code) + "\n")
                _pmp_sys.stderr.flush()
            except Exception:
                pass
            return 1
        return 0
    finally:
        if _pmp_saved is None:
            try:
                del _pmp_sys.argv
            except AttributeError:
                pass
        else:
            _pmp_sys.argv = _pmp_saved
"""

    /**
     * Compiles [RUN_MODULE_SOURCE] once into a private globals dict and keeps a strong reference
     * to the resulting function, exactly as [checkpointCallableHoldingGIL] does — including the
     * detail that a module-level `def` needs no `__builtins__` entry of its own, because frame
     * setup falls back to the interpreter's builtins when the globals mapping has none.
     *
     * The private dict is released here; the function keeps it alive through `__globals__`.
     *
     * The caller must hold the GIL.
     */
    private fun runModuleHelperHoldingGIL(): NativePointer {
        runModuleCallable?.let { return it }
        val code = Py_CompileString(RUN_MODULE_SOURCE, "<python-multiplatform:runmain>", PY_FILE_INPUT)
            ?: throw pyErrorOrGeneric("Failed to compile the runMain helper")
        try {
            val globals = PyDict_New() ?: throw pyErrorOrGeneric("Failed to allocate the runMain helper's globals")
            try {
                // This is where `import runpy` actually happens, so a build with no standard
                // library reports it here rather than as a mysterious null further down.
                val evaluated = PyEval_EvalCode(code, globals, globals)
                    ?: throw pyErrorOrGeneric("Failed to define the runMain helper (is `runpy` importable?)")
                Py_DecRef(evaluated)
                // PyDict_GetItemString returns a borrowed reference, so take one of our own.
                val borrowed = PyDict_GetItemString(globals, "__pmp_run_module__")
                    ?: throw pyErrorOrGeneric("The runMain helper did not define __pmp_run_module__")
                Py_IncRef(borrowed)
                runModuleCallable = borrowed
                return borrowed
            } finally {
                Py_DecRef(globals)
            }
        } finally {
            Py_DecRef(code)
        }
    }

    /**
     * Run Python script as an application (Automatically initializes Python).
     *
     * **Still refusing, and the reason recorded here previously was the smaller half of it.**
     *
     * **This used to do nothing at all** -- its only statement was commented out, and so is the
     * `Py_BytesMain` `expect` declaration it would call (`EmbedAPI.kt`, two commented-out lines;
     * `EmbedAPI.native.kt` additionally carries an `actual` for it that *looks* live but sits
     * inside the file's leading nested block comment, so it is dead too). It neither initialized
     * Python nor ran anything, and returned `Unit` regardless, so a caller could not tell.
     * It has no caller in `src/` or `sample/`.
     *
     * Two things block it, and they are not the same size:
     *
     * 1. `Py_BytesMain` takes `(int argc, char **argv)`, so wiring it up means marshalling an
     *    array of C strings, which every platform in this build does differently. This is the
     *    reason that was recorded, and it is the *cheap* one.
     * 2. **`Py_BytesMain` finalizes the interpreter**, for the same reason [runMain]'s old body
     *    did: it is the CLI's `main`, so it reaches `Py_RunMain()` (`cpython/pylifecycle.h`),
     *    whose contract is to run and then finalize. Marshalling the argv would therefore have
     *    bought a function with exactly the defect [runMain] was just fixed to avoid — the ROADMAP
     *    entry that described this as one marshalling path away from working was wrong.
     *
     * What is *not* blocked is the useful part of what a caller wants from this. `python -m mod`
     * is [runMain], which now works. What `Py_BytesMain` adds on top is CPython's **command-line
     * parser** — `-c`, `-m`, a script path, `-O`, `-X`, `PYTHONPATH` handling — and several of
     * those are `PyConfig` fields that must be set *before* initialisation, i.e. they cannot mean
     * anything for an already-running interpreter. Reimplementing that parser against a
     * `PyConfig` this binding deliberately does not carry (struct layout is exactly what the
     * Stable ABI does not promise -- see [initialize]) is the actual cost, and it is not paid
     * here.
     *
     * `Python3Test.runAppRefusesRatherThanSilentlyDoingNothing` is the guard.
     *
     * @throws UnsupportedOperationException always.
     */
    fun runApp(argv: Array<String>): Nothing =
        throw UnsupportedOperationException(
            "Python3.runApp is not implemented: it needs Py_BytesMain(int argc, char **argv), " +
                "which is still commented out because marshalling an array of C strings differs " +
                "on every platform in this build -- and, more seriously, because Py_BytesMain " +
                "reaches Py_RunMain() and therefore finalizes the interpreter, which is exactly " +
                "the defect Python3.runMain was fixed to avoid. See ROADMAP §12. Use " +
                "Python3.runMain(module, args) to run a module as __main__ in an interpreter " +
                "that survives it. It previously returned silently without running " +
                "${argv.joinToString(" ")}."
        )

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
     * **This is not only a free-threading fix.** `_PY_GC_SCHEDULED_BIT` — the bit `_Py_ScheduleGC`
     * sets on every allocation that crosses a generation threshold — is cleared by the same
     * `_Py_HandlePending` this reaches. That scheduling has been how allocation triggers a cyclic
     * collection since 3.12, on *both* builds. An embedder driving CPython purely through the C
     * API therefore never runs the cyclic collector at all, GIL or not: reference cycles the
     * collector would otherwise break simply accumulate. Calling this reclaims them exactly as it
     * merges the free-threaded queue — one call, one checkpoint, both jobs. See
     * `docs/gc-scheduling-investigation.md` §1 and §6.
     *
     * The trade-off is what runs at the checkpoint, not just what it reclaims: any `__del__`,
     * weakref callback or pending call attached to something the collector or the queue was
     * holding can execute here, inside whatever `withGIL` scope happened to trip the checkpoint —
     * a scope its caller did not ask to yield control from. Measured reentrant-safe (a `__del__`
     * that calls back into Kotlin mid-collection completes without deadlocking, looping or
     * crashing — `GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint`), but it is still a
     * side effect the caller did not request, and is why [autoDrainInterval] defaults to off on
     * the GIL build rather than always on.
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
     * [drainPendingReleases] — a build with the global lock frees a *plain* reference the
     * cleaner's `Py_DecRef` immediately, with no queue to drain, so that half of the checkpoint's
     * job really is pure overhead there.
     *
     * **It still defaults to off on the GIL build**, but not because there is nothing left to
     * reclaim: reference *cycles* are collected only by a scheduled cyclic collection, and that
     * scheduling is read by the same eval-loop checkpoint on both builds (see
     * [drainPendingReleases] and `docs/gc-scheduling-investigation.md` §1). An embedder that never
     * runs Python bytecode accumulates cyclic garbage forever on the GIL build too. The default
     * stays off there because turning it on is a behavioural change an embedder has to opt into
     * knowingly, not a free one: a checkpoint can run `__del__`/weakref callbacks/pending calls at
     * a `withGIL` scope exit the caller never asked to yield from. Measured cost when on: ~5.5 ns
     * per outermost scope (~2.9% of the ~190 ns floor), amortised over this interval. Turn it on
     * explicitly, call [drainPendingReleases] to force one checkpoint without changing this, or
     * call [PyGC_Collect] directly for an immediate, unconditional collection — [drainPendingReleases]
     * and `PyGC_Collect` both work regardless of this setting.
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
