package python.native.ffi

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the lookup half of the filesystem-sidecar path that lets a GraalVM native image ship
 * without a 19.4 MB copy of `libpython` baked into its image heap.
 *
 * The load half only runs when the classpath copy is absent, which on the JVM it never is -- the
 * desktop jar bundles it. So the JVM cannot exercise `System.load` here, and `runNativeUpcallDemo`
 * is what covers that end (it is the configuration with no bundled resource). What *is* worth
 * pinning down on the JVM is the path arithmetic: if `resolveSidecarLibrary` stops finding CPython
 * under `PYTHONHOME` -- a renamed directory in the standalone build, a changed version string --
 * the native image fails at startup with an `UnsatisfiedLinkError` and nothing before it complains.
 */
class SidecarLibraryLookupTest {

    @Test
    fun resolvesCPythonUnderPythonHome() {
        val pythonHome = System.getenv("PYTHONHOME")
        assertNotNull(
            pythonHome,
            "PYTHONHOME is unset for desktopTest; the sidecar lookup has nothing to resolve against"
        )

        val compact = Versions.currentVersion.compactVersionString
        val libraryName = if (currentPlatform.os == OSType.Windows) {
            "python" + compact.replace(".", "")
        } else {
            "python$compact"
        }

        val resolved = manager.resolveSidecarLibrary(libraryName)
        assertNotNull(
            resolved,
            "no ${System.mapLibraryName(libraryName)} found under PYTHONHOME ($pythonHome). " +
                "A native image built without the bundled library resource would fail to start."
        )
        assertTrue(resolved.isFile, "${resolved.absolutePath} is not a regular file")
    }
}
