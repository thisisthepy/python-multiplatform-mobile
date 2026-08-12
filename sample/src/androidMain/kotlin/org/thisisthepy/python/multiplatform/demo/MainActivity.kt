package org.thisisthepy.python.multiplatform.demo

import android.content.Context
import android.content.res.AssetManager
import android.os.Bundle
import android.system.Os
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.thisisthepy.python.multiplatform.demo.ui.App
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


private const val TAG = "PythonDemo"

/**
 * Android is the one platform where the host app has real work to do before `Py_Initialize`:
 * CPython's stdlib ships inside the library module's assets and has to be unpacked to a readable
 * directory, and `PYTHONHOME` has to name that directory's prefix.
 *
 * The stdlib path is derived from [Versions] rather than written out. It used to read
 * `lib/python3.13`, and the library moved to 3.14 — `python-multiplatform`'s
 * `copyAndroidPythonAssets` stages `<abi>/lib/python3.14`, so the hardcoded path matched nothing,
 * `copyPythonFromAssets` threw `No Python files found in assets`, and the app died in `onCreate`.
 * Deriving it means a version bump cannot reintroduce that.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CPython reads PYTHONHOME from the process environment, not from a JVM system property,
        // and it wants the *prefix* — the directory holding `lib/python3.14`, not that directory.
        Os.setenv("PYTHONHOME", filesDir.absolutePath, true)
        unpackStdlib(applicationContext)

        PythonDemo.start()

        setContent { App() }
    }

    /** `lib/python3.14` — the layout `Py_Initialize` looks for under `PYTHONHOME`. */
    val stdlibPath: String = "lib/python${Versions.currentVersion.taggedVersionString}"

    /** Where that same tree sits in the merged assets, staged per ABI by the library module. */
    val stdlibAssetPath: String = abiDirectoryName() + "/" + stdlibPath

    private fun abiDirectoryName(): String = when {
        currentPlatform.is64Bit && currentPlatform.isArm -> "arm64-v8a"
        currentPlatform.is64Bit && currentPlatform.isX86 -> "x86_64"
        else -> throw IllegalStateException("Unsupported platform: ${currentPlatform.os} ${currentPlatform.arch}")
    }

    private fun unpackStdlib(context: Context) {
        val target = File(context.filesDir, stdlibPath)
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            Log.d(TAG, "stdlib already unpacked at ${target.absolutePath}")
            return
        }

        val assets = context.assets
        val staged = assets.list(stdlibAssetPath)
        if (staged.isNullOrEmpty()) {
            throw IOException(
                "No CPython stdlib under assets/$stdlibAssetPath. The library module's " +
                    "copyAndroidPythonAssets task stages it; check that the configured version " +
                    "(${Versions.currentVersion}) matches what was extracted.",
            )
        }

        target.mkdirs()
        copyAssetFolder(assets, stdlibAssetPath, target.absolutePath)
        Log.d(TAG, "unpacked ${target.list()?.size ?: 0} stdlib entries to ${target.absolutePath}")
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String) {
        val files = assetManager.list(fromAssetPath) ?: return

        for (file in files) {
            val assetFilePath = "$fromAssetPath/$file"
            val destFilePath = "$toPath/$file"

            val children = assetManager.list(assetFilePath)
            if (!children.isNullOrEmpty()) {
                File(destFilePath).mkdirs()
                copyAssetFolder(assetManager, assetFilePath, destFilePath)
                continue
            }

            assetManager.open(assetFilePath).use { input ->
                FileOutputStream(destFilePath).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFilePath.endsWith(".so")) File(destFilePath).setExecutable(true)
        }
    }
}
