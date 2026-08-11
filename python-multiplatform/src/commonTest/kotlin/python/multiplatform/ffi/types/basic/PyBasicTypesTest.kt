package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Red-phase functional tests for the basic-type wrappers: construction from
 * Kotlin values (`from`), reading back to Kotlin (`toKotlin`/`asNative`),
 * and wrapping an existing Python object obtained via `eval`.
 *
 * Every one of these bodies is `TODO("Not yet implemented")` (see
 * `PyInt`/`PyFloat`/`PyBool`/`PyString`/`PyNone`), so all of these are
 * expected to fail with [NotImplementedError] until the next phase.
 */
class PyBasicTypesTest {

    @Test
    fun intRoundTripsFromKotlinToPythonAndBack() = PythonTestFixture.withInterpreter {
        val pyInt = PyInt.from(42L)
        assertEquals("42", pyInt.toString())
        assertEquals(42L, pyInt.toKotlin())
    }

    @Test
    fun intWrapsAnExistingPythonObject() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("6 * 7")
        val pyInt = PyInt(obj.pointer, true)
        assertEquals(42L, pyInt.toKotlin())
    }

    @Test
    fun floatRoundTripsFromKotlinToPythonAndBack() = PythonTestFixture.withInterpreter {
        val pyFloat = PyFloat.from(3.5)
        assertEquals(3.5, pyFloat.toKotlin())
    }

    @Test
    fun boolRoundTripsFromKotlinToPythonAndBack() = PythonTestFixture.withInterpreter {
        val pyTrue = PyBool.from(true)
        val pyFalse = PyBool.from(false)
        assertTrue(pyTrue.toKotlin())
        assertTrue(!pyFalse.toKotlin())
    }

    @Test
    fun stringRoundTripsFromKotlinToPythonAndBack() = PythonTestFixture.withInterpreter {
        val pyString = PyString.from("hello, python")
        assertEquals("hello, python", pyString.toKotlin())
        assertEquals(13, pyString.length)
    }

    @Test
    fun stringWrapsAnExistingPythonObject() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("'wrapped'")
        val pyString = PyString(obj.pointer, true)
        assertEquals("wrapped", pyString.toKotlin())
    }

    @Test
    fun noneIsASingleton() = PythonTestFixture.withInterpreter {
        val first = PyNone.get()
        val second = PyNone.get()
        assertEquals(first, second, "PyNone.get() should always return the same underlying None object")
    }

    @Test
    fun noneRecognisesTheActualNoneObject() = PythonTestFixture.withInterpreter {
        val noneObj = PythonTestFixture.eval("None")
        assertTrue(PyNone.isNone(noneObj))

        val nonNoneObj = PythonTestFixture.eval("0")
        assertTrue(!PyNone.isNone(nonNoneObj))
    }
}
