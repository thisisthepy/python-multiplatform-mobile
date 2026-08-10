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
            // Py_Initialize() causes a fatal crash (abort) due to missing 'encodings'
            // module inside the test binary environment.
            interpreterAvailable = Py_IsInitialized() != 0
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

        val obj = PyLong_FromLongLong(42L)
        if (obj == null) {
            println("SKIPPED testRawFfiCost: PyLong_FromLongLong returned null")
            return
        }

        Benchmark.run("Py_IncRef/Py_DecRef", iterations = 1_000_000) {
            Py_IncRef(obj)
            Py_DecRef(obj)
        }
        
        Py_DecRef(obj)
    }

    @Test
    fun testStringMarshalling() {
        if (!interpreterAvailable) return

        val lengths = listOf(8, 256, 8192)
        for (len in lengths) {
            val str = "A".repeat(len)
            
            Benchmark.run("PyUnicode_FromString ($len chars)", warmupIterations = 100, iterations = 10_000) {
                val pyStr = PyUnicode_FromString(str)
                if (pyStr != null) {
                    Py_DecRef(pyStr)
                }
            }

            val pyStr = PyUnicode_FromString(str)
            if (pyStr != null) {
                Benchmark.run("PyUnicode_AsUTF8 ($len chars)", warmupIterations = 100, iterations = 10_000) {
                    PyUnicode_AsUTF8(pyStr)
                }
                Py_DecRef(pyStr)
            }
        }
    }

    @Test
    fun testIntegerMarshalling() {
        if (!interpreterAvailable) return

        Benchmark.run("PyLong_FromLongLong", iterations = 100_000) {
            val obj = PyLong_FromLongLong(123456789L)
            if (obj != null) {
                Py_DecRef(obj)
            }
        }

        val obj = PyLong_FromLongLong(123456789L)
        if (obj != null) {
            Benchmark.run("PyLong_AsLongLong", iterations = 100_000) {
                PyLong_AsLongLong(obj)
            }
            Py_DecRef(obj)
        }
    }

    @Test
    fun testReferenceCountingChurn() {
        if (!interpreterAvailable) return

        // Without machinery (just pointer)
        val rawObj = PyLong_FromLongLong(42L)
        if (rawObj != null) {
            Benchmark.run("Manual IncRef/DecRef", iterations = 100_000) {
                Py_IncRef(rawObj)
                Py_DecRef(rawObj)
            }
            
            Benchmark.run("PyObject wrapper creation", iterations = 100_000) {
                val obj = PyObject(rawObj, borrowed = true)
                obj.clean()
            }
            
            Py_DecRef(rawObj)
        }
    }

    @Test
    fun testAttributeAccess() {
        if (!interpreterAvailable) return
        
        // Try to get a module, e.g. sys
        val moduleName = PyUnicode_FromString("sys")
        if (moduleName == null) return
        
        val sysModule = python.native.ffi.PyImport_Import(moduleName)
        Py_DecRef(moduleName)
        
        if (sysModule == null) {
            python.native.ffi.PyErr_Clear()
            println("SKIPPED testAttributeAccess: Could not import sys")
            return
        }

        Benchmark.run("PyObject_GetAttrString", iterations = 10_000) {
            val attr = PyObject_GetAttrString(sysModule, "version")
            if (attr != null) {
                Py_DecRef(attr)
            }
        }
        
        Py_DecRef(sysModule)
    }
}
