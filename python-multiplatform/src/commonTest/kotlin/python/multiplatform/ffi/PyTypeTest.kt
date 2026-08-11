package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.errors.PyTypeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Red-phase functional tests for [PyType]: name/base-type introspection,
 * MRO, `isInstance`/`isSubtypeOf`, and construction via `invoke`/`__new__`.
 *
 * `name` and `baseType` are already implemented; `mro`, `isInstance`,
 * `isSubtypeOf`, `getIterator`, `invoke`, `__new__` and `__init__` are new
 * `TODO` stubs added as part of this pass and are expected to fail with
 * [NotImplementedError]. `baseTypes` has a pre-existing implementation with
 * a known bug (see `PyType.kt`); it is exercised here too so a fix shows up
 * as this test flipping from failing-with-a-crash to passing.
 */
class PyTypeTest {

    @Test
    fun nameReflectsThePythonTypeName() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        assertEquals("int", intType.name)

        val strType = PythonTestFixture.eval("'x'").Type
        assertEquals("str", strType.name)
    }

    @Test
    fun baseTypeOfIntIsObject() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        assertEquals("object", intType.baseType.name)
    }

    @Test
    fun baseTypesOfBoolIncludesInt() = PythonTestFixture.withInterpreter {
        val boolType = PythonTestFixture.eval("True").Type
        val baseNames = boolType.baseTypes.map { it.name }
        assertTrue("int" in baseNames, "expected bool.__bases__ to include int, got $baseNames")
    }

    @Test
    fun mroOfBoolIncludesIntAndObject() = PythonTestFixture.withInterpreter {
        val boolType = PythonTestFixture.eval("True").Type
        val mroNames = boolType.mro.map { it.name }
        assertTrue(mroNames.containsAll(listOf("bool", "int", "object")), "expected bool.__mro__ to include bool/int/object, got $mroNames")
    }

    @Test
    fun isSubtypeOfReflectsPythonSubclassing() = PythonTestFixture.withInterpreter {
        val boolType = PythonTestFixture.eval("True").Type
        val intType = PythonTestFixture.eval("42").Type
        assertTrue(boolType.isSubtypeOf(intType), "bool should be a subtype of int in Python")
    }

    @Test
    fun isInstanceRecognisesActualInstances() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val fortyTwo = PythonTestFixture.eval("42")
        val hello = PythonTestFixture.eval("'hello'")
        assertTrue(intType.isInstance(fortyTwo))
        assertTrue(!intType.isInstance(hello))
    }

    @Test
    fun invokeConstructsANewInstance() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val result = intType.invoke(PythonTestFixture.eval("'7'"))
        assertEquals("7", result.toString())
    }

    @Test
    fun castRaisesForIncompatibleObjects() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").Type
        val notAnInt = PythonTestFixture.eval("'not an int'")
        assertFailsWith<PyTypeError> {
            intType.cast(notAnInt)
        }
    }
}
