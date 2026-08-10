package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPythonTest {
    @Test
    fun testPythonEndToEnd() {
        println("Initializing Python...")
        Py_Initialize()
        println("Getting version...")
        val version = Py_GetVersion()
        println("Python version: $version")
        assertTrue(version?.startsWith("3.14") == true, "Expected version to start with 3.14, got $version")
        Py_Finalize()
    }
}
