package python.native.ffi

import python.multiplatform.Versions

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import python.multiplatform.ffi.Python3
import java.io.File
import java.io.FileOutputStream

/**
 * Gets a usable CPython interpreter running inside the instrumentation process.
 *
 * `Py_Initialize` aborts the whole process with "Failed to import encodings module" unless the
 * standard library is present on disk and PYTHONHOME points at it. The library ships that
 * stdlib as APK assets, so it has to be unpacked to app-private storage first.
 *
 * Idempotent on purpose: JUnit does not guarantee test order, and one test finalises the
 * interpreter when it is done. Every test that needs Python calls [ensureInitialised] and gets
 * a live interpreter whether or not one was already running.
 */
object PythonOnDevice {

    /**
     * Derived rather than literal. `MainActivity` already builds the same path this way, and a
     * hardcoded release line silently stops matching the staged assets on every version bump --
     * `copyAndroidPythonAssets` stages `<abi>/lib/python$libVersion`. `taggedVersionString`
     * also carries the `t` suffix for a free-threaded build, which the literal could not.
     */
    private val PYTHON_DIR = "lib/python${Versions.currentVersion.taggedVersionString}"

    @Volatile
    private var stdlibStaged = false

    /**
     * Stages the stdlib, then brings the interpreter up **through [Python3.initialize]** rather
     * than through a bare `Py_Initialize()`.
     *
     * The difference is the GIL, and it was worth three GCLeakTest failures on both API levels.
     * `Py_Initialize()` returns with the GIL held by its caller. [Python3.initialize] parks that
     * thread state with `PyEval_SaveThread()` immediately afterwards, which is what lets a cleaner
     * thread attach through `PyGILState_Ensure` and call `Py_DecRef` (ROADMAP §1).
     *
     * Calling the C function directly here skipped the parking, and then made the omission
     * permanent: `Python3.isInitialized` is seeded from `Py_IsInitialized()` the first time the
     * object is touched, so it latched to `true` and [Python3.initialize] returned early for the
     * rest of the process. The instrumentation thread — which is also the thread every test body
     * runs on — held the GIL for the entire run, and every cleaner blocked on the first
     * `PyGILState_Ensure` it reached.
     *
     * That state is invisible while nothing releases the GIL, which is why it survived: the old
     * `forceGC()` called `PyEval_SaveThread()`/`PyEval_RestoreThread` around its sleep, and that
     * accidental 200 ms window was the only thing letting cleaners through at all.
     */
    fun ensureInitialised() {
        stageStdlibOnce()
        Python3.initialize(silent = true)
        check(Py_IsInitialized() != 0) { "Py_Initialize() did not take effect" }
    }

    /**
     * Attaches the calling thread for the body of a test that reaches [bindings] directly.
     *
     * The object model takes the GIL for itself on every call, so tests written against `Python3`
     * and `PyObject` need nothing. Tests that call the raw JNI surface bypass that, and since
     * [ensureInitialised] now parks the main thread state they would otherwise run the C API with
     * no thread state attached, which is undefined behaviour rather than a clean failure.
     */
    fun attach(): Int = PyGILState_Ensure()

    fun detach(state: Int) = PyGILState_Release(state)

    /** Allocates a C string the caller must release with [freeUtf8]. */
    fun utf8(s: String): Long = bindings.ffiAllocUtf8(s)

    fun freeUtf8(ptr: Long) = bindings.ffiFreeUtf8(ptr)

    /** Runs [block] with a temporary C string, releasing it even if [block] throws. */
    inline fun <T> withUtf8(s: String, block: (Long) -> T): T {
        val ptr = utf8(s)
        try {
            return block(ptr)
        } finally {
            freeUtf8(ptr)
        }
    }

    @Synchronized
    private fun stageStdlibOnce() {
        if (stdlibStaged) return

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val archDir = if (abi.contains("arm64")) "arm64-v8a" else "x86_64"

        val target = File(context.filesDir, PYTHON_DIR)
        if (!File(target, "encodings").isDirectory) {
            target.deleteRecursively()
            target.mkdirs()
            copyAssetFolder(context.assets, "$archDir/$PYTHON_DIR", target.absolutePath)
        }

        // PYTHONHOME is the prefix, i.e. the parent of lib/pythonX.Y -- not the stdlib dir.
        android.system.Os.setenv("PYTHONHOME", context.filesDir.absolutePath, true)
        stdlibStaged = true
    }

    private fun copyAssetFolder(assets: AssetManager, from: String, to: String) {
        val entries = assets.list(from) ?: return
        for (entry in entries) {
            val src = "$from/$entry"
            val dst = "$to/$entry"
            if (!assets.list(src).isNullOrEmpty()) {
                File(dst).mkdirs()
                copyAssetFolder(assets, src, dst)
                continue
            }
            assets.open(src).use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
        }
    }
}
