package python.multiplatform.ffi

import python.native.ffi.PyImport_AddModule
import python.native.ffi.PyObject_GetAttrString

/**
 * Shared entry point for functional tests that need a live interpreter.
 *
 * A real CPython 3.13 interpreter *does* initialise inside this test binary
 * on the platforms this suite currently runs on (see
 * `python.multiplatform.env.InterpreterAvailabilityTest`, and the
 * `extractIosSimulatorStdlib` task in `build.gradle.kts` that gives the iOS
 * simulator run a `PYTHONHOME` with an actual standard library to find). So
 * [withInterpreter] is the primary, expected-to-succeed path: it initialises
 * eagerly and lets any failure propagate as a real test failure.
 *
 * [requireInterpreterOrSkip] is a defensive fallback for environments where
 * bringing up the interpreter is not guaranteed at all (e.g. a future target
 * with no stdlib payload wired up yet) -- it degrades to a clearly-labelled
 * skip instead of a wall of unrelated crashes, rather than being the normal
 * way tests in this suite are expected to run.
 */
object PythonTestFixture {

    /** Whether [Python3.initialize] succeeded (computed once, lazily, per test binary). */
    val available: Boolean by lazy { attemptInitialize() }

    /** Set when [available] is `false`, describing why initialisation failed. */
    var failureReason: String? = null
        private set

    private fun attemptInitialize(): Boolean {
        return try {
            Python3.initialize(silent = true)
            true
        } catch (t: Throwable) {
            failureReason = "${t::class.simpleName}: ${t.message}"
            false
        }
    }

    /**
     * Runs [block] against a live interpreter. This is the normal path for
     * functional tests in this suite -- the interpreter is expected to be
     * available, so a failure to initialise surfaces as a genuine test
     * failure rather than a silent skip.
     */
    inline fun withInterpreter(block: () -> Unit) {
        check(available) {
            "CPython could not be initialized in this environment (${failureReason ?: "unknown reason"})"
        }
        block()
    }

    /**
     * Same intent as [withInterpreter], but degrades to a skip (prints a
     * notice and returns without running [block]) instead of failing when
     * the interpreter is not available. Reserved for tests that specifically
     * want to tolerate a missing interpreter; most tests should use
     * [withInterpreter] so a regression in interpreter bring-up is caught.
     */
    inline fun requireInterpreterOrSkip(testName: String, block: () -> Unit) {
        if (!available) {
            println("SKIP: $testName -- CPython could not be initialized in this environment (${failureReason ?: "unknown reason"})")
            return
        }
        block()
    }

    /**
     * The `__main__` module's `__dict__`, usable as both the `globals` and
     * `locals` argument to [Python3.eval] -- convenience plumbing for tests
     * that need to evaluate a Python expression to obtain a real [PyObject]
     * to exercise the (still-`TODO`) typed wrappers against.
     */
    fun mainGlobals(): PyObject = Python3.withPython {
        // These reach the C API directly, so they need the GIL like any other call. The
        // initialising thread no longer holds it — see Python3.initialize.
        val modulePtr = python.multiplatform.ffi.Python3.withPython { PyImport_AddModule("__main__") } ?: error("Could not get __main__ module")
        val dictPtr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(modulePtr, "__dict__") } ?: error("Could not get __main__.__dict__")
        PyObject(dictPtr, true)
    }

    /** Evaluates [expression] (e.g. `"1 + 1"`) against [mainGlobals] and returns the resulting [PyObject]. */
    fun eval(expression: String): PyObject {
        val globals = mainGlobals()
        return Python3.eval(expression, PY_EVAL_INPUT, globals, globals)
    }

    /** `Py_eval_input`, for use with [Python3.eval]. */
    const val PY_EVAL_INPUT: Int = 258
}
