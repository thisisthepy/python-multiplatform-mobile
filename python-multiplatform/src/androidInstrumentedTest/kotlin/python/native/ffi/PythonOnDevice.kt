package python.native.ffi

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
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

    private const val PYTHON_DIR = "lib/python3.14"

    @Volatile
    private var stdlibStaged = false

    fun ensureInitialised() {
        stageStdlibOnce()
        if (Py_IsInitialized() == 0) {
            Py_Initialize()
        }
        check(Py_IsInitialized() != 0) { "Py_Initialize() did not take effect" }
    }

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
