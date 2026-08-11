package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Red-phase functional tests for [PyTuple]: construction, indexing, size,
 * sub-listing and conversion to a Kotlin `List`. All members are `TODO`.
 */
class PyTupleTest {

    @Test
    fun fromListThenToNativeListRoundTrips() = PythonTestFixture.withInterpreter {
        val elements = listOf(
            PythonTestFixture.eval("'a'"),
            PythonTestFixture.eval("'b'"),
        )
        val tuple = PyTuple.fromList(elements)
        assertEquals(2, tuple.size)
        assertEquals(listOf("a", "b"), tuple.toNativeList())
    }

    @Test
    fun getReturnsElementAtIndex() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(10, 20, 30)")
        val tuple = PyTuple(obj.pointer, false)
        assertEquals("20", tuple[1].toString())
    }

    @Test
    fun sizeMatchesPythonLen() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(1, 2, 3, 4)")
        val tuple = PyTuple(obj.pointer, false)
        assertEquals(4, tuple.size)
    }

    @Test
    fun subListReturnsAContiguousSlice() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(1, 2, 3, 4, 5)")
        val tuple = PyTuple(obj.pointer, false)
        val sub = tuple.subList(1, 3)
        assertEquals(listOf("2", "3"), sub.map { it.toString() })
    }

    @Test
    fun iterationVisitsAllElementsInOrder() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("(1, 2, 3)")
        val tuple = PyTuple(obj.pointer, false)
        assertEquals(listOf("1", "2", "3"), tuple.map { it.toString() })
    }
}
