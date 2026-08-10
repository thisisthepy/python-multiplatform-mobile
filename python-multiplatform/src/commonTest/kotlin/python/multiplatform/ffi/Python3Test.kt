package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Red-phase functional tests for [Python3]: lifecycle, script execution,
 * evaluation, module import and interpreter metadata.
 *
 * `initialize`/`exec`/`eval`/`import`/the metadata properties are already
 * implemented (unlike most of the rest of the object model, which is still
 * `TODO`), so several of these are expected to pass immediately -- that is
 * fine and expected for this slice of the surface; the point of the red
 * phase is that the *typed* wrapper layer (PyInt, PyList, ...) is not
 * implemented yet, which the other test files in this package cover.
 */
class Python3Test {

    @Test
    fun isInitializedReflectsLifecycle() = PythonTestFixture.withInterpreter {
        assertTrue(Python3.isInitialized, "expected Python3.isInitialized to be true once initialize() has run")
    }

    @Test
    fun initializeIsIdempotent() = PythonTestFixture.withInterpreter {
        // Calling initialize() again after it already succeeded must not throw or blow up state.
        Python3.initialize(silent = true)
        assertTrue(Python3.isInitialized)
    }

    @Test
    fun execRunsAStatement() = PythonTestFixture.withInterpreter {
        Python3.exec("x_for_exec_test = 1 + 2")
        val result = PythonTestFixture.eval("x_for_exec_test")
        assertTrue(result.toString() == "3", "expected the assigned global to read back as 3, got '$result'")
    }

    @Test
    fun execOnInvalidSyntaxThrowsPyException() = PythonTestFixture.withInterpreter {
        assertFailsWith<PyException>("exec() of invalid Python source should raise a PyException") {
            Python3.exec("this is not valid python !!!")
        }
    }

    @Test
    fun evalReturnsExpressionResult() = PythonTestFixture.withInterpreter {
        val result = PythonTestFixture.eval("21 * 2")
        assertTrue(result.toString() == "42", "expected '42', got '$result'")
    }

    @Test
    fun evalOnUndefinedNameThrowsPyException() = PythonTestFixture.withInterpreter {
        assertFailsWith<PyException> {
            PythonTestFixture.eval("this_name_is_not_defined_anywhere")
        }
    }

    @Test
    fun importReturnsAUsableModule() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        val pi = math.getAttr("pi")
        assertTrue(pi.toString().startsWith("3.14"), "expected math.pi to start with 3.14, got '$pi'")
    }

    @Test
    fun importOfUnknownModuleThrows() = PythonTestFixture.withInterpreter {
        assertFailsWith<PyException> {
            Python3.import("this_module_does_not_exist_anywhere_xyz")
        }
    }

    @Test
    fun versionReportsPython313() = PythonTestFixture.withInterpreter {
        assertTrue(Python3.version.startsWith("3.13"), "expected embedded interpreter to be 3.13.x, got '${Python3.version}'")
    }

    @Test
    fun platformIsNotBlank() = PythonTestFixture.withInterpreter {
        assertTrue(Python3.platform.isNotBlank())
    }

    @Test
    fun copyrightMentionsPythonSoftwareFoundation() = PythonTestFixture.withInterpreter {
        assertTrue(Python3.copyright.contains("Python"), "expected copyright string to mention Python, got '${Python3.copyright}'")
    }

    @Test
    fun compilerIsNotBlank() = PythonTestFixture.withInterpreter {
        assertTrue(Python3.compiler.isNotBlank())
    }

    @Test
    fun buildInfoIsNotBlank() = PythonTestFixture.withInterpreter {
        assertTrue(Python3.buildInfo.isNotBlank())
    }
}
