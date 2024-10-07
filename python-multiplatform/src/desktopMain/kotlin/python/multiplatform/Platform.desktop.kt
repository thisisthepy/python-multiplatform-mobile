package python.multiplatform

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*


object JVMPlatform: Platform {
    override val os = getOperatingSystemType()
    override val arch: String = System.getProperty("os.arch")?.lowercase() ?: "unknown"
    override val version: String = System.getProperty("os.version") ?: "unknown"
    override val versionCode: Int?
    override val versionText: String?
    override val platformType = PlatformType.JVM
    override val platformVersion: String? = System.getProperty("java.version")

    init {
        val buildVersion = getBuildVersion(os)
        if (buildVersion != null) {
            versionCode = buildVersion.toIntOrNull()
            versionText = "Build $buildVersion"
        } else {
            versionCode = null
            versionText = null
        }
    }

    override fun toString(): String {
        return name
    }
}

private fun getOperatingSystemType(): OSType {
    val name = System.getProperty("os.name").lowercase(Locale.getDefault())
    return when {
        name.indexOf("win") >= 0 -> OSType.Windows
        name.indexOf("mac") >= 0 -> OSType.MacOS
        name.indexOf("linux") >= 0 -> OSType.Linux
        else -> throw IllegalStateException("Unsupported operating system: $name")
    }
}

private fun getBuildVersion(os: OSType): String? {
    val command = when (os) {
        OSType.Windows -> "wmic os get BuildNumber"
        OSType.MacOS -> "sw_vers -buildVersion"
        else -> "lsb_release -r"  // Ubuntu
    }
    val process = Runtime.getRuntime().exec(command)
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    process.waitFor()
    if (process.exitValue() != 0) return null  // Return null when the command fails
    var result: List<String> = reader.useLines { it.joinToString("\n") }
        .replace(" ", "").split("\n").filter { it.isNotEmpty() }
    reader.close()

    return (if (os == OSType.MacOS) result.firstOrNull() else result.lastOrNull())?.trim()
}

actual val currentPlatform: Platform = JVMPlatform
