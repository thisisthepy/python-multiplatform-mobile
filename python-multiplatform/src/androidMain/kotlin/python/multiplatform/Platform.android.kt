package python.multiplatform

import android.os.Build


object AndroidPlatform : Platform {
    override val os = OSType.Android
    override val arch: String = System.getProperty("os.arch")?.lowercase() ?: "unknown"
    override val version: String = Build.VERSION.RELEASE
    override val versionCode = Build.VERSION.SDK_INT
    override val versionText = "SDK $versionCode"
    override val platformType = PlatformType.JVM
    override val platformVersion: String? = System.getProperty("java.specification.version")

    override val name = super.name.replace(PlatformType.JVM.name, "ART VM")

    override fun toString(): String {
        return name
    }
}

actual val currentPlatform: Platform = AndroidPlatform
