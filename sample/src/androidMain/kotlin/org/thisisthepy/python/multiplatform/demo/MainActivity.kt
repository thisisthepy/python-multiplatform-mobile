package org.thisisthepy.python.multiplatform.demo

import android.content.Context
import android.content.res.AssetManager
import android.os.Bundle
import android.system.Os
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import python.multiplatform.currentPlatform
import python.multiplatform.ffi.Python3
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Os.setenv("PYTHONHOME", filesDir.toString(), true)

        initPython()

        setContent {
            App()
        }
    }

    @Synchronized
    fun initPython() {
        try {
            copyPythonFromAssets(applicationContext)

            val filesDir = applicationContext.filesDir
            val pythonDir = File(filesDir, PYTHON_DIR)
            val pythonHome = pythonDir.absolutePath

            Log.d("PythonInit", "Python home path: $pythonHome")
            Log.d("PythonInit", "Python home exists: ${pythonDir.exists()}")
            Log.d("PythonInit", "Python home contents: ${pythonDir.list()?.joinToString()}")

            System.setProperty("PYTHONHOME", pythonHome)
            Log.d("PythonInit", "PYTHONHOME property set to: ${System.getProperty("PYTHONHOME")}")
            Log.d("PythonInit", "PYTHONHOME: ${System.getProperty("PYTHONHOME")}")

            val libDir = applicationContext.applicationInfo.nativeLibraryDir
            Log.d("PythonInit", "Native library dir: $libDir")

            System.setProperty("LD_LIBRARY_PATH", libDir)
            Log.d("PythonInit", "LD_LIBRARY_PATH: ${System.getProperty("LD_LIBRARY_PATH")}")

            // 현재 작업 디렉토리도 확인
            Log.d("PythonInit", "Current working directory: ${File(".").absolutePath}")

            Python3.initialize()
        } catch (e: IOException) {
            Log.e("PythonManager", "Failed to copy Python files", e)
            throw RuntimeException("Python initialization failed", e)
        } catch (e: Exception) {  // 다른 예외도 캐치
            Log.e("PythonManager", "Unexpected error during Python initialization", e)
            throw RuntimeException("Python initialization failed", e)
        }
    }

    val PYTHON_DIR: String = "lib/python3.13"
    val PYTHON_ASSET_DIR: String = (if (currentPlatform.is64Bit && currentPlatform.isArm) {
        "arm64-v8a"
    } else if (currentPlatform.is64Bit && currentPlatform.isX86) {
        "x86_64"
    } else {
        throw IllegalStateException("Unsupported platform: ${currentPlatform.os} ${currentPlatform.arch}")
    }) + "/" + PYTHON_DIR

    private fun copyPythonFromAssets(context: Context) {
        val filesDir = context.filesDir
        val pythonDir = File(filesDir, PYTHON_DIR)

        Log.d("PythonCopy", "Starting copy process")
        Log.d("PythonCopy", "Target directory: ${pythonDir.absolutePath}")

        // 디렉토리가 존재하고 내용물이 있는지 확인
        if (pythonDir.exists() && pythonDir.list()?.isNotEmpty() == true) {
            Log.d("PythonCopy", "Python directory exists and has contents")
            pythonDir.listFiles()?.forEach {
                //Log.d("PythonCopy", "Existing file: ${it.name}")
            }
            return
        }

        // 디렉토리가 비어있거나 없으면 새로 생성
        pythonDir.mkdirs()
        val assetManager = context.assets

        try {
            Log.d("PythonCopy", "Listing root assets:")
            assetManager.list("")?.forEach {
                Log.d("PythonCopy", "Found: $it")
            }
        } catch (e: IOException) {
            Log.e("PythonCopy", "Error listing assets", e)
        }

        try {
            Log.d("PythonCopy", "Listing assets in python directory (searching $PYTHON_ASSET_DIR)")
            val files = assetManager.list(PYTHON_ASSET_DIR)
            Log.d("PythonCopy", "Found files in assets: ${files?.joinToString()}")

            if (files.isNullOrEmpty()) {
                throw IOException("No Python files found in assets")
            }

            copyAssetFolder(assetManager, PYTHON_ASSET_DIR, pythonDir.absolutePath)
            Log.d("PythonCopy", "Copy completed, checking results:")
            pythonDir.listFiles()?.forEach {
                Log.d("PythonCopy", "Copied file: ${it.name}")
            }

        } catch (e: IOException) {
            Log.e("PythonCopy", "Error copying Python files", e)
            throw e
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String) {
        val files = assetManager.list(fromAssetPath)

        for (file in files!!) {
            val assetFilePath = "$fromAssetPath/$file"
            val destFilePath = "$toPath/$file"

            // 디렉토리인 경우 재귀적으로 처리
            val subfiles = assetManager.list(assetFilePath)
            if (!subfiles.isNullOrEmpty()) {
                File(destFilePath).mkdirs()
                copyAssetFolder(assetManager, assetFilePath, destFilePath)
                continue
            }

            assetManager.open(assetFilePath).use { `in` ->
                FileOutputStream(destFilePath).use { out ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) != -1) {
                        out.write(buffer, 0, read)
                    }

                    if (destFilePath.endsWith(".so")) {
                        File(destFilePath).setExecutable(true)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
