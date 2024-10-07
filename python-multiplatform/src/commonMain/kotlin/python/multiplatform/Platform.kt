package python.multiplatform


interface Platform {
    val os: OSType
    val arch: String
    val version: String
    val versionCode: Int?
    val versionText: String?
        get() = if (versionCode == null) null else "$versionCode"
    val platformType: PlatformType
    val platformVersion: String?

    val name: String
        get() {
            val versionInfo = if (versionText == null) "" else "$versionText, "
            return "$os $version ($versionInfo$arch) / $platformType" + if (platformVersion != null) " $platformVersion" else ""
        }

    val isWindows get(): Boolean = os == OSType.Windows
    val isMacOs get(): Boolean = os == OSType.MacOS
    val isLinux get(): Boolean = os == OSType.Linux
    val isAndroid get(): Boolean = os == OSType.Android
    val isIos get(): Boolean = os == OSType.IOS

    val isMobileOS get(): Boolean = isAndroid || isIos
    val isDesktopOS get(): Boolean = isWindows || isMacOs || isLinux

    val is64Bit get(): Boolean = arch.contains("64") || arch.contains("armv8", ignoreCase = true)
    val isArm get(): Boolean =
        arch.contains("arm", ignoreCase = true) || arch.contains("aarch", ignoreCase = true)
    val isX86 get(): Boolean =
        arch in setOf("i386", "i486", "i586", "i686")
                || arch.contains("x86", ignoreCase = true)
                || arch.contains("amd64", ignoreCase = true)

    val isJvm get(): Boolean = platformType == PlatformType.JVM
    val isNative get(): Boolean = platformType == PlatformType.Native
}

enum class OSType {
    Windows, MacOS, Linux, Android, IOS;

    override fun toString(): String {
        if (this == IOS) return "iOS"
        return super.toString()
    }
}

enum class PlatformType {
    JVM, Native
}

expect val currentPlatform: Platform
