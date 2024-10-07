package python.multiplatform.ffi

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform


internal fun loadLibPython() {
    val pyVer = Versions.currentVersion
    val libName = (if (currentPlatform.os == OSType.Android) "multiplatform_" else "") + "python" + pyVer.compactVersionString
    System.loadLibrary(if (currentPlatform.os == OSType.Windows) libName.replace(".", "") else libName)
}
