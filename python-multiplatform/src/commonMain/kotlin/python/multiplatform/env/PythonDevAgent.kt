package python.multiplatform.env

import python.multiplatform.ffi.Python3
import python.native.ffi.PyImport_ExecCodeModule
import python.native.ffi.Py_CompileString
import python.native.ffi.Py_DecRef
import python.multiplatform.ffi.pyErrorOrGeneric

/**
 * The dev agent (SPEC L-12, #233): a debug-only channel from a notebook or a hot reload into this
 * interpreter, designed in toolchain's `docs/design/live-connection.md` (option B).
 *
 * [Python3.initialize] calls [startIfRequested]. It does nothing unless the launcher set
 * [ENVIRONMENT_VARIABLE] (`port=<n>;token=<t>[;overlay=<dir>]`), which a debug launch does and a release
 * build never does; then the agent's Python ([DEV_AGENT_SOURCE], embedded from
 * `src/commonMain/python/python_multiplatform_devagent.py`) is loaded as the module [MODULE] and listens on
 * 127.0.0.1 with that token. A malformed request is reported on stderr and does not stop the app.
 */
internal object PythonDevAgent {
    const val ENVIRONMENT_VARIABLE = "PYTHON_MULTIPLATFORM_DEV_AGENT"
    const val MODULE = "python_multiplatform_devagent"

    private const val PY_FILE_INPUT: Int = 257
    private var loaded = false
    private var requested = false

    fun startIfRequested() {
        if (requested || readEnvVar(ENVIRONMENT_VARIABLE).isNullOrBlank()) return
        requested = true
        try {
            load()
            Python3.exec("__import__(\"$MODULE\").start_from_env()")
        } catch (e: Exception) {
            println("WARNING: python-multiplatform dev agent not started: ${e.message}")
        }
    }

    /** Loads [DEV_AGENT_SOURCE] as the module [MODULE] (once). */
    fun load() {
        if (loaded) return
        Python3.withPython {
            val code = Py_CompileString(DEV_AGENT_SOURCE, "<python-multiplatform:devagent>", PY_FILE_INPUT)
                ?: throw pyErrorOrGeneric("Failed to compile the dev agent")
            try {
                val module = PyImport_ExecCodeModule(MODULE, code)
                    ?: throw pyErrorOrGeneric("Failed to load the dev agent")
                Py_DecRef(module)
            } finally {
                Py_DecRef(code)
            }
        }
        loaded = true
    }
}
