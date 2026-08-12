package python.multiplatform.overhead

import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.native.ffi.*

class BenchmarkTest {

    companion object {
        var initialized = false
        var interpreterAvailable = false

        fun setupPython() {
            if (initialized) return
            initialized = true
            // Deliberately asks whether an interpreter is already up rather than bringing one up:
            // a benchmark should not be the thing that owns interpreter lifecycle for the suite.
            //
            // This used to be justified by "Py_Initialize() causes a fatal crash (abort) due to
            // missing 'encodings' module inside the test binary environment", which is no longer
            // true of the environments this runs in. It is a real failure mode, but it belongs to
            // a platform whose stdlib has not been staged: desktop and the iOS simulator get a
            // PYTHONHOME from the build (`InterpreterAvailabilityTest` initialises successfully),
            // and on Android `PythonInstrumentationRunner` unpacks the stdlib before any test
            // class loads.
            //
            // The consequence of only asking is that these benchmarks measure nothing unless some
            // earlier test has already initialised. On desktop they do run.
            interpreterAvailable = python.multiplatform.ffi.Python3.isInitialized
            if (!interpreterAvailable) {
                println("SKIPPED: Python interpreter unavailable (cannot initialize safely). Tests requiring the interpreter will be skipped.")
            }
        }
    }

    @BeforeTest
    fun setUp() {
        setupPython()
    }

    @AfterTest
    fun tearDown() {
        Benchmark.printReport()
    }

    @Test
    fun testPointerBoxing() {
        val p = 12345L.toNativePointer()
        if (p == null) {
            println("SKIPPED: Cannot create NativePointer from Long on this platform")
            return
        }

        Benchmark.run("NativePointer.toRawValue()", iterations = 1_000_000) {
            p.toRawValue()
        }

        Benchmark.run("NativePointer.toAddressValue()", iterations = 1_000_000) {
            p.toAddressValue()
        }

        val addr = p.toAddressValue()
        Benchmark.run("AddressValue.toNativePointer()", iterations = 1_000_000) {
            addr.toNativePointer()
        }
    }

    @Test
    fun testRawFfiCost() {
        if (!interpreterAvailable) return

        val obj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(42L) }
        if (obj == null) {
            println("SKIPPED testRawFfiCost: PyLong_FromLongLong returned null")
            return
        }

        Benchmark.run("Py_IncRef/Py_DecRef", iterations = 1_000_000) {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(obj) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
        }
        
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
    }

    @Test
    fun testStringMarshalling() {
        if (!interpreterAvailable) return

        val lengths = listOf(8, 256, 8192)
        for (len in lengths) {
            val str = "A".repeat(len)
            
            Benchmark.run("PyUnicode_FromString ($len chars)", warmupIterations = 100, iterations = 10_000) {
                val pyStr = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString(str) }
                if (pyStr != null) {
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(pyStr) }
                }
            }

            val pyStr = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString(str) }
            if (pyStr != null) {
                Benchmark.run("PyUnicode_AsUTF8 ($len chars)", warmupIterations = 100, iterations = 10_000) {
                    python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(pyStr) }
                }
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(pyStr) }
            }
        }
    }

    @Test
    fun testIntegerMarshalling() {
        if (!interpreterAvailable) return

        Benchmark.run("PyLong_FromLongLong", iterations = 100_000) {
            val obj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(123456789L) }
            if (obj != null) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
            }
        }

        val obj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(123456789L) }
        if (obj != null) {
            Benchmark.run("PyLong_AsLongLong", iterations = 100_000) {
                python.multiplatform.ffi.Python3.withPython { PyLong_AsLongLong(obj) }
            }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
        }
    }

    @Test
    fun testReferenceCountingChurn() {
        if (!interpreterAvailable) return

        // Without machinery (just pointer)
        val rawObj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(42L) }
        if (rawObj != null) {
            Benchmark.run("Manual IncRef/DecRef", iterations = 100_000) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(rawObj) }
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(rawObj) }
            }
            
            Benchmark.run("PyObject wrapper creation", iterations = 100_000) {
                val obj = PyObject(rawObj, borrowed = true)
                obj.close()
            }
            
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(rawObj) }
        }
    }

    @Test
    fun testAttributeAccess() {
        if (!interpreterAvailable) return
        
        // Try to get a module, e.g. sys
        val moduleName = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString("sys") }
        if (moduleName == null) return
        
        // Every C API call needs the GIL, this one included. It was outside a scope and
        // segfaulted the moment initialize() started parking the main thread state.
        val sysModule = python.multiplatform.ffi.Python3.withPython { python.native.ffi.PyImport_Import(moduleName) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(moduleName) }
        
        if (sysModule == null) {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.PyErr_Clear() }
            println("SKIPPED testAttributeAccess: Could not import sys")
            return
        }

        Benchmark.run("PyObject_GetAttrString", iterations = 10_000) {
            val attr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(sysModule, "version") }
            if (attr != null) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(attr) }
            }
        }
        
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(sysModule) }
    }
}
