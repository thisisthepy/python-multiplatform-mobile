package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Red-phase functional tests for [PyList]: construction, indexing,
 * iteration, mutation and conversion to/from Kotlin collections. Every
 * member is `TODO("Not yet implemented")`, so all of these are expected to
 * fail with [NotImplementedError] until the next phase.
 */
class PyListTest {

    @Test
    fun fromListThenToNativeListRoundTrips() = PythonTestFixture.withInterpreter {
        val elements = listOf(
            PythonTestFixture.eval("1"),
            PythonTestFixture.eval("2"),
            PythonTestFixture.eval("3"),
        )
        val list = PyList.fromList(elements)
        assertEquals(3, list.size)
        assertEquals(listOf(1L, 2L, 3L), list.toNativeList())
    }

    @Test
    fun getReturnsElementAtIndex() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[10, 20, 30]")
        val list = PyList(obj.pointer, false)
        assertEquals("20", list[1].toString())
    }

    @Test
    fun iterationVisitsAllElementsInOrder() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val list = PyList(obj.pointer, false)
        val seen = list.map { it.toString() }
        assertEquals(listOf("1", "2", "3"), seen)
    }

    @Test
    fun addAppendsAnElement() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2]")
        val list = PyList(obj.pointer, false)
        list.add(PythonTestFixture.eval("3"))
        assertEquals(3, list.size)
    }

    @Test
    fun removeAtDeletesTheElement() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val list = PyList(obj.pointer, false)
        list.removeAt(0)
        assertEquals(2, list.size)
    }

    @Test
    fun sortOrdersElementsAscending() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[3, 1, 2]")
        val list = PyList(obj.pointer, false)
        list.sort()
        assertEquals(listOf(1L, 2L, 3L), list.toNativeList())
    }

    @Test
    fun isEmptyReflectsSize() = PythonTestFixture.withInterpreter {
        val list = PyList.fromList(emptyList())
        assertTrue(list.isEmpty())
    }
}
