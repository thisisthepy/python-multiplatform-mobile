package python.multiplatform

import kotlinx.cinterop.*
import platform.UIKit.UIDevice
import platform.Foundation.NSProcessInfo
import platform.posix.uname
import platform.posix.utsname


object IOSPlatform: Platform {
    override val os = OSType.IOS  // UIDevice.currentDevice.systemName()
    override val arch: String = getDeviceArch()
    override val version: String = UIDevice.currentDevice.systemVersion
    override val versionCode = getBuildVersion()
    override val versionText = "Build $versionCode"
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String {
        return name
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun getDeviceArch(): String {
    memScoped {
        val systemInfo = alloc<utsname>()
        if (uname(systemInfo.ptr) != 0) {
            throw RuntimeException("Failed to get device arch info")
        }
        return systemInfo.machine.toKString()
    }
}

private fun getBuildVersion(): Int? {
    val processInfo = NSProcessInfo.processInfo
    return processInfo.operatingSystemVersionString.trim()?.map { it.code }?.joinToString("")?.toIntOrNull()
}

actual val currentPlatform: Platform = IOSPlatform
