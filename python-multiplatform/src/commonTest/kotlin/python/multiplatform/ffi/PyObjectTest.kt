package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Red-phase functional tests for [PyObject]: attribute access, equality,
 * string conversion and calling.
 *
 * Attribute get/set/delete are already implemented on [PyObject] (unlike
 * most of the rest of the object model), so those are expected to genuinely
 * pass against a live interpreter; [PyObject.invoke] and friends are still
 * `TODO` and are expected to fail with [NotImplementedError].
 */
class PyObjectTest {

    /** A fresh, attribute-settable Python object: an instance of a throwaway class. */
    private fun freshInstance(): PyObject {
        Python3.exec(
            "class _PyObjectTestSubject:\n" +
                "    pass\n"
        )
        return PythonTestFixture.eval("_PyObjectTestSubject()")
    }

    @Test
    fun getAttrReturnsExistingAttribute() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        val pi = math.getAttr("pi")
        assertTrue(pi.toString().startsWith("3.14"))
    }

    @Test
    fun getAttrOnMissingAttributeThrowsPyException() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        assertFailsWith<PyException> {
            math.getAttr("this_attribute_does_not_exist")
        }
    }

    @Test
    fun getAttrOrNullReturnsNullForMissingAttribute() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        assertNull(math.getAttrOrNull("this_attribute_does_not_exist"))
    }

    @Test
    fun setAttrThenGetAttrRoundTrips() = PythonTestFixture.withInterpreter {
        val obj = freshInstance()
        val value = PythonTestFixture.eval("123")
        obj.setAttr("custom_field", value)
        assertEquals("123", obj.getAttr("custom_field").toString())
    }

    @Test
    fun delAttrRemovesAPreviouslySetAttribute() = PythonTestFixture.withInterpreter {
        val obj = freshInstance()
        obj.setAttr("custom_field", PythonTestFixture.eval("'gone soon'"))
        obj.delAttr("custom_field")
        assertFailsWith<PyException> {
            obj.getAttr("custom_field")
        }
    }

    @Test
    fun delAttrOnMissingAttributeDoesNotThrow() = PythonTestFixture.withInterpreter {
        val obj = freshInstance()
        // delAttrOrNull is the non-throwing variant; must be safe to call even when the attribute was never set.
        obj.delAttrOrNull("never_existed")
    }

    @Test
    fun equalsIsTrueForTheSameUnderlyingPythonObject() = PythonTestFixture.withInterpreter {
        Python3.exec("_shared_for_equality_test = object()")
        val first = PythonTestFixture.eval("_shared_for_equality_test")
        val second = PythonTestFixture.eval("_shared_for_equality_test")
        assertEquals(first, second, "two PyObject wrappers around the same underlying Python object should be equal")
    }

    @Test
    fun equalsIsFalseForDifferentPythonObjects() = PythonTestFixture.withInterpreter {
        val a = freshInstance()
        val b = freshInstance()
        assertFalse(a == b, "two distinct Python instances must not compare equal")
    }

    @Test
    fun toStringMatchesPythonStr() = PythonTestFixture.withInterpreter {
        assertEquals("42", PythonTestFixture.eval("42").toString())
        assertEquals("hello", PythonTestFixture.eval("'hello'").toString())
    }

    @Test
    fun hashCodeIsConsistentForTheSameObject() = PythonTestFixture.withInterpreter {
        val obj = freshInstance()
        assertEquals(obj.hashCode(), obj.hashCode())
    }

    @Test
    fun getTypeReturnsTheObjectsPythonType() = PythonTestFixture.withInterpreter {
        val intType = PythonTestFixture.eval("42").getType()
        assertEquals("int", intType.name)
    }

    @Test
    fun invokeCallsAPythonCallable() = PythonTestFixture.withInterpreter {
        Python3.exec(
            "def _add_one_for_invoke_test(x):\n" +
                "    return x + 1\n"
        )
        val fn = PythonTestFixture.eval("_add_one_for_invoke_test")
        val result = fn.invoke(PythonTestFixture.eval("41"))
        assertEquals("42", result.toString())
    }

    @Test
    fun isCallableDistinguishesFunctionsFromValues() = PythonTestFixture.withInterpreter {
        Python3.exec("def _callable_for_test(): pass")
        val fn = PythonTestFixture.eval("_callable_for_test")
        val value = PythonTestFixture.eval("42")
        assertTrue(fn.isCallable())
        assertFalse(value.isCallable())
    }

    @Test
    fun isTruthyMatchesPythonBoolConversion() = PythonTestFixture.withInterpreter {
        assertTrue(PythonTestFixture.eval("1").isTruthy())
        assertFalse(PythonTestFixture.eval("0").isTruthy())
        assertFalse(PythonTestFixture.eval("[]").isTruthy())
    }

    @Test
    fun reprMatchesPythonRepr() = PythonTestFixture.withInterpreter {
        assertEquals("'hello'", PythonTestFixture.eval("'hello'").repr())
    }

    @Test
    fun richCompareEvaluatesPythonComparisonOperators() = PythonTestFixture.withInterpreter {
        val one = PythonTestFixture.eval("1")
        val two = PythonTestFixture.eval("2")
        assertTrue(one.richCompare(two, PyCompareOp.LT))
        assertFalse(one.richCompare(two, PyCompareOp.GT))
    }
}
