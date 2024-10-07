package python.multiplatform

import platform.android.*


object AndroidNativePlatform : Platform {
    override val os = OSType.Android
    override val arch: String = "Kotlin/Native"  // TODO: Get the actual architecture
    override val version: String = "Kotlin/Native"  // TODO: Get the actual version
    override val versionCode = 0  // TODO: Get the actual version code
    override val versionText = "SDK $versionCode"
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String {
        return name
    }
}

actual val currentPlatform: Platform = AndroidNativePlatform
