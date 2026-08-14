package python.multiplatform.ffi

import python.multiplatform.Versions
import python.multiplatform.ffi.exceptions.PyException
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * [Python3.runApp] used to be a silent no-op: its only statement was commented out, so it
     * returned `Unit` without initialising anything or running anything, and a caller had no way
     * to tell. Wiring it up needs `Py_BytesMain`, whose `(int argc, char **argv)` signature means
     * an array-of-C-strings marshalling path that each of the four platforms does differently
     * (ROADMAP §12), so until that exists the honest behaviour is to refuse.
     *
     * Red phase, observed: before the fix this failed with
     * `Expected an exception of class UnsupportedOperationException to be thrown, but was
     * completed successfully.` -- i.e. the no-op. A failure here now means either the refusal was
     * removed without `Py_BytesMain` landing, or the real implementation landed and this test
     * should be replaced by one that runs a script.
     */
    @Test
    fun runAppRefusesRatherThanSilentlyDoingNothing() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<UnsupportedOperationException> {
            Python3.runApp(arrayOf("python", "-c", "pass"))
        }
        assertTrue(
            failure.message?.contains("Py_BytesMain") == true,
            "The refusal should name what is missing, got: ${failure.message}"
        )
    }

    /**
     * [Python3.runMain] used to be a landmine. `sys.argv[1] = ...` assigns to an existing index,
     * but `Py_Initialize()` does not set `sys.argv`, so it raised `IndexError` invisibly
     * (`PyRun_SimpleString` prints and clears the indicator, and its return was discarded); then
     * `Py_RunMain()` -- which **always finalizes the interpreter** -- ran, leaving the process
     * with a torn-down runtime while [Python3.isInitialized] still said `true`. Its `Int` exit
     * status was discarded too. Nothing in `src/` or `sample/` called it.
     *
     * What "run a module" should mean for an *embedded* interpreter that has to survive the call
     * is a design decision recorded in ROADMAP §12; refusing is not that decision, it is the
     * removal of the landmine.
     *
     * **Red phase not executed, deliberately.** Calling the pre-fix body reaches `Py_RunMain()`,
     * which with no `PyConfig.run_*` set enters the REPL on the process's stdin and finalizes the
     * interpreter this whole suite shares -- it would either hang the test worker or crash every
     * class scheduled after it (the exact failure `DesktopPythonTest`'s header records). That
     * hazard is the thing under test, so it was reasoned about rather than triggered on a machine
     * running other agents' builds. The pre-fix code path is quoted above from the source it
     * replaced.
     */
    @Test
    fun runMainRefusesRatherThanFinalizingTheSharedInterpreter() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<UnsupportedOperationException> {
            Python3.runMain("json.tool")
        }
        assertTrue(
            failure.message?.contains("Py_RunMain") == true,
            "The refusal should name why it cannot run, got: ${failure.message}"
        )
        // The point of refusing: the interpreter the rest of the suite shares is still there.
        assertTrue(Python3.isInitialized)
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }
}
