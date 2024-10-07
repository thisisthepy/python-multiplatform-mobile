package python.multiplatform

import platform.UIKit.UIDevice
import platform.Foundation.NSProcessInfo


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

private fun getDeviceArch(): String {
    val systemInfo = utsname()
    uname(systemInfo.ptr)
    return systemInfo.machine.toKString()
}

private fun getBuildVersion(): Int? {
    val processInfo = NSProcessInfo.processInfo
    return processInfo.operatingSystemVersionString.trim()?.map { it.code }?.joinToString("")?.toIntOrNull()
}

actual val currentPlatform: Platform = IOSPlatform
