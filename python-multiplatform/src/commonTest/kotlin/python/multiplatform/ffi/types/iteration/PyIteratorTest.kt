package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PythonTestFixture
import python.native.ffi.PyObject_GetIter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Red-phase functional tests for [PyIterator]: obtaining one via `iter()`
 * and driving it to exhaustion. Both [PyIterator.hasNext] and
 * [PyIterator.next] are `TODO`.
 */
class PyIteratorTest {

    @Test
    fun iteratesOverAllElementsThenStops() = PythonTestFixture.withInterpreter {
        val listObj = PythonTestFixture.eval("[1, 2, 3]")
        val iterPtr = PyObject_GetIter(listObj.pointer) ?: error("PyObject_GetIter returned null for a list")
        val iterator = PyIterator(iterPtr, false)

        val seen = mutableListOf<String>()
        while (iterator.hasNext()) {
            seen.add(iterator.next().toString())
        }
        assertEquals(listOf("1", "2", "3"), seen)
        assertFalse(iterator.hasNext(), "iterator should report exhausted once all elements are consumed")
    }

    @Test
    fun emptyIterableHasNoNextImmediately() = PythonTestFixture.withInterpreter {
        val listObj = PythonTestFixture.eval("[]")
        val iterPtr = PyObject_GetIter(listObj.pointer) ?: error("PyObject_GetIter returned null")
        val iterator = PyIterator(iterPtr, false)
        assertTrue(!iterator.hasNext())
    }
}
