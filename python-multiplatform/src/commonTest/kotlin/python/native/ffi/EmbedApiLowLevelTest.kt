package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * The low-level layer: EmbedAPI functions one at a time, without the object model on top.
 *
 * ### Why this file exists
 *
 * Every other test under `commonTest` exercises the assembled layer — `Python3`, `PyObject`,
 * the collections. That is the right default, but it means a broken individual binding is only
 * ever seen through whatever the object model happens to call, and only in the combinations it
 * happens to use. Android demonstrated the cost of the mirror-image gap: its instrumented tests
 * covered only the low level, so the assembled path was never run on a device and turned out to
 * crash on its first call.
 *
 * These tests take each function on its own terms: does it return what its C counterpart
 * returns, does it report failure the documented way, and does it leave the reference count
 * where it found it.
 *
 * The reference conventions are the part worth being explicit about, because getting one wrong
 * is silent in both directions — one too few leaks, one too many frees an object still in use
 * and the crash lands somewhere unrelated.
 */
class EmbedApiLowLevelTest {

    /**
     * Goes through the shared fixture rather than calling `Python3.initialize` directly.
     *
     * Doing it directly crashed the whole desktop suite -- zero tests reported, a fatal error
     * inside `PyGILState_Ensure` -> `new_threadstate`. The fixture initialises exactly once for
     * the process and other classes already rely on that; a second initialise racing it, or one
     * arriving after another class finalised, leaves the interpreter in a state where attaching
     * a thread crashes. Run alone these tests passed, which is exactly how the interaction
     * stayed invisible.
     */
    private fun ready() = PythonTestFixture.available.also {
        check(it) { "CPython could not be initialized: ${PythonTestFixture.failureReason}" }
    }

    @Test
    fun versionRoundTripsThroughTheCApi() {
        ready()
        val v = Python3.withPython { Py_GetVersion() }
        assertNotNull(v, "Py_GetVersion returned null")
        assertTrue(v.startsWith("3.14"), "expected 3.14.x, got $v")
    }

    @Test
    fun longsRoundTripThroughPythonInts() {
        ready()
        val probes = listOf(0L, 1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (probe in probes) {
            val obj = Python3.withPython { PyLong_FromLongLong(probe) }
            assertNotNull(obj, "PyLong_FromLongLong($probe) returned null")
            try {
                assertEquals(probe, Python3.withPython { PyLong_AsLongLong(obj) }, "round trip failed for $probe")
            } finally {
                Python3.withPython { Py_DecRef(obj) }
            }
        }
    }

    @Test
    fun stringsRoundTripThroughPythonStr() {
        ready()
        // Ascii, empty, and non-ascii -- the last one is what a fast-path encoder gets wrong.
        for (probe in listOf("hello", "", "한글과 emoji 🐍")) {
            val obj = Python3.withPython { PyUnicode_FromString(probe) }
            assertNotNull(obj, "PyUnicode_FromString returned null for \"$probe\"")
            try {
                assertEquals(probe, Python3.withPython { PyUnicode_AsUTF8(obj) })
            } finally {
                Python3.withPython { Py_DecRef(obj) }
            }
        }
    }

    @Test
    fun importReturnsANewReferenceAndMissingModulesReportFailure() {
        ready()
        val sys = Python3.withPython { PyImport_ImportModule("sys") }
        if (sys == null) Python3.withPython { PyErr_Print() }; assertNotNull(sys, "importing sys returned null")
        Python3.withPython { Py_DecRef(sys) }

        val missing = Python3.withPython { PyImport_ImportModule("definitely_not_a_real_module_xyz") }
        assertNull(missing, "importing a missing module should return null")
        // A failed import sets the error indicator, and calling on with one pending corrupts
        // whatever runs next -- so clearing it is part of the contract, not tidiness.
        Python3.withPython { PyErr_Clear() }
        assertNull(Python3.withPython { PyErr_Occurred() }, "the error indicator should be clear again")
    }

    @Test
    fun attributeLookupReturnsANewReferenceAndMissingNamesSetTheIndicator() {
        ready()
        val sys = Python3.withPython { PyImport_ImportModule("sys") }
        assertNotNull(sys)
        try {
            val version = Python3.withPython { PyObject_GetAttrString(sys, "version") }
            assertNotNull(version, "sys.version lookup returned null")
            Python3.withPython { Py_DecRef(version) }

            val missing = Python3.withPython { PyObject_GetAttrString(sys, "no_such_attribute_xyz") }
            assertNull(missing, "a missing attribute should return null")
            assertNotNull(Python3.withPython { PyErr_Occurred() }, "a missing attribute should set the error indicator")
            Python3.withPython { PyErr_Clear() }
        } finally {
            Python3.withPython { Py_DecRef(sys) }
        }
    }

    @Test
    fun listSizeMatchesWhatWasPutIn() {
        ready()
        val list = Python3.withPython { PyList_New(3) }
        if (list == null) Python3.withPython { PyErr_Print() }; assertNotNull(list, "PyList_New returned null")
        try {
            assertEquals(3L, Python3.withPython { PyList_Size(list) })
        } finally {
            Python3.withPython { Py_DecRef(list) }
        }
    }

    @Test
    fun borrowedAndNewReferencesBehaveAsDocumented() {
        ready()
        // PyList_GetItem borrows; PyList_SetItem steals. Getting either backwards is silent
        // until something unrelated crashes, so state the expectation here.
        val list = Python3.withPython { PyList_New(1) }
        assertNotNull(list)
        try {
            val item = Python3.withPython { PyLong_FromLongLong(7L) }
            assertNotNull(item)
            // SetItem steals the reference; do not release `item` afterwards.
            assertEquals(0, Python3.withPython { PyList_SetItem(list, 0L, item) })

            // GetItem borrows; the list still owns it, so do not release what comes back.
            val borrowed = Python3.withPython { PyList_GetItem(list, 0L) }
            assertNotNull(borrowed, "PyList_GetItem returned null")
            assertEquals(7L, Python3.withPython { PyLong_AsLongLong(borrowed) })
        } finally {
            Python3.withPython { Py_DecRef(list) }
        }
    }
}
