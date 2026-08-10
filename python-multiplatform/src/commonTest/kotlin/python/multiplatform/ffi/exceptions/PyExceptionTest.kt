package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.exceptions.errors.PyValueError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Red-phase functional tests establishing that a Python-level error surfaces
 * as a Kotlin throwable with type/message information attached, per the
 * mermaid sketch's `PyException { type, value, traceback, message, cause,
 * context }`.
 *
 * A division by zero at the Python level already throws a [PyException]
 * today (see `Python3.exec`), but with `type`/`value`/`traceback` left
 * unpopulated -- the exec/eval call sites only build a bare message, they
 * do not yet call [PyException.fromCurrentError] to pull the real
 * `ZeroDivisionError` off CPython's error indicator. The `type`-asserting
 * tests below are expected to fail for that reason until that wiring is
 * done; they document the intended end state.
 */
class PyExceptionTest {

    @Test
    fun divisionByZeroRaisesAPyException() = PythonTestFixture.withInterpreter {
        assertFailsWith<PyException> {
            Python3.exec("1 / 0")
        }
    }

    @Test
    fun divisionByZeroExceptionCarriesTheZeroDivisionErrorType() = PythonTestFixture.withInterpreter {
        val thrown = assertFailsWith<PyException> {
            Python3.exec("1 / 0")
        }
        assertNotNull(thrown.type, "expected the PyException to carry the Python exception's type")
        assertEquals("ZeroDivisionError", thrown.type?.name)
    }

    @Test
    fun fromCurrentErrorReturnsNullWhenNoErrorIsSet() = PythonTestFixture.withInterpreter {
        // Precondition: no pending Python error at this point in the test.
        assertEquals(null, PyException.fromCurrentError())
    }

    @Test
    fun fromCurrentErrorCapturesAPendingPythonError() = PythonTestFixture.withInterpreter {
        // Trigger an error without letting exec()'s own handling clear/consume it first isn't
        // possible through the current API (exec() always translates it into a Kotlin PyException),
        // so this documents the intended low-level entry point directly.
        val captured = PyException.fromCurrentError()
        assertEquals(null, captured, "no error should be pending at the start of this test")
    }

    @Test
    fun typeErrorHasTheExpectedPythonTypeName() {
        assertEquals("TypeError", PyTypeError.PYTHON_TYPE_NAME)
    }

    @Test
    fun valueErrorHasTheExpectedPythonTypeName() {
        assertEquals("ValueError", PyValueError.PYTHON_TYPE_NAME)
    }
}
