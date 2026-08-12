package python.multiplatform.ffi

import python.multiplatform.Versions
import python.multiplatform.ffi.exceptions.PyException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Functional tests for [Python3]: lifecycle (`initialize`/`isInitialized`),
 * script execution, evaluation, module import, and the interpreter metadata
 * properties (`version`/`platform`/`copyright`/`compiler`/`buildInfo`).
 *
 * This header used to call these a red phase, on the grounds that the typed
 * wrapper layer (PyInt, PyList, ...) covered by the other files in this
 * package was still `TODO`. It is implemented now, so every test here is a
 * regression test and any failure is a real one.
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
    fun versionReportsTheConfiguredRelease() = PythonTestFixture.withInterpreter {
        // Was `versionReportsPython314`, asserting the literal. See EmbedApiLowLevelTest.
        val expected = Versions.currentVersion.compactVersionString
        assertTrue(
            Python3.version.startsWith(expected),
            "expected embedded interpreter to be $expected.x, got '${Python3.version}'"
        )
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
