package python.multiplatform.ffi

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.*


internal object LibPythonManager {
    @Synchronized
    fun loadLibPython() {
        val pyVer = Versions.currentVersion
        var libName = "python" + pyVer.compactVersionString
        val libList = mutableListOf<File>()

        when (currentPlatform.os) {
            OSType.Windows -> {
                libName = libName.replace(".", "")
                libList.add(extractLibrary("vcruntime140"))
                libList.add(extractLibrary("vcruntime140_1"))
                libList.add(extractLibrary(libName))
            }
            OSType.Android -> {
                libName = "multiplatform_$libName"
            }
            else -> {
                libList.add(extractLibrary(libName))
            }
        }

        System.loadLibrary(libName)
        libList.forEach { it.delete() }
    }

    @Synchronized
    private fun extractLibrary(libraryName: String, location: String = "/lib"): File {
        var libraryFileName = System.mapLibraryName(libraryName)
        val tempFile = File(".", libraryFileName)

        libraryFileName = currentPlatform.os.name.lowercase(Locale.getDefault()) +
                (if (currentPlatform.isArm) "-aarch64" else "-x86_64") + "/" + libraryFileName

        javaClass.getResourceAsStream("$location/$libraryFileName").use { inputStream ->
            if (inputStream == null) {
                throw UnsatisfiedLinkError("Library $libraryFileName not found in JAR")
            }
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        return tempFile
    }
}
