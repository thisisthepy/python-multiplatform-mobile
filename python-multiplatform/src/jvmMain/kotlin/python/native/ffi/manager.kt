package python.native.ffi

import jdk.incubator.foreign.*
import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import java.io.File
import java.io.FileOutputStream
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*


internal object manager {
    @Synchronized
    fun loadLibPython() {
        val pyVer = Versions.currentVersion
        var libName = "python" + pyVer.compactVersionString
        val libList = mutableListOf<File>()

        when (currentPlatform.os) {
            OSType.Windows -> {
                setupWindowsEnv()

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

    fun setupWindowsEnv() {
        val kernel32 = CLinker.systemLookup().lookup("SetEnvironmentVariableW")
            .orElseThrow { throw RuntimeException("Failed to find SetEnvironmentVariableW") }

        val consoleEncoding = System.console()?.charset()?.name() ?: Charset.defaultCharset().displayName()

    }

    // POSIX 시스템의 경우
    fun setupPosixEnv() {
        val libc = CLinker.systemLookup()
            .lookup("setenv")
            .orElseThrow()

        val setEnvHandle: MethodHandle = CLinker.getInstance().downcallHandle(
            libc,
            MethodType.methodType(Int::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Int::class.java),
            FunctionDescriptor.of(
                CLinker.C_INT,
                CLinker.C_POINTER,
                CLinker.C_POINTER,
                CLinker.C_INT
            )
        )

        val consoleEncoding = System.console()?.charset()?.name() ?: Charset.defaultCharset().displayName()

        ResourceScope.newConfinedScope().use { scope ->
            val name = CLinker.toCString("PYTHONLEGACYWINDOWSSTDIO", scope)
            val value = CLinker.toCString(consoleEncoding, scope)
            setEnvHandle.invoke(name.address(), value.address(), 1)
        }
    }
}
