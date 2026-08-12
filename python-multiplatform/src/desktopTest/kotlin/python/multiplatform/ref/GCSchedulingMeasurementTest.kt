package python.multiplatform.ref

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test

class GCSchedulingMeasurementTest {

    @Test
    fun measureCyclicGarbageWithoutAutoDrain() = PythonTestFixture.withInterpreter {
        // GIL build or not, setting to 0 explicitly
        val previousInterval = Python3.autoDrainInterval
        try {
            Python3.autoDrainInterval = 0
            
            val builtins = Python3.import("builtins")
            val gc = Python3.import("gc")
            gc.getAttr("collect").invoke().close()
            val initial = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            
            val listType = builtins.getAttr("list")
            for (i in 1..10000) {
                val a = listType()
                val b = listType()
                val aAppend = a.getAttr("append")
                val bAppend = b.getAttr("append")
                aAppend(b).close()
                bAppend(a).close()
                aAppend.close()
                bAppend.close()
                a.close()
                b.close()
            }
            
            val after = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            gc.close()
            listType.close()
            builtins.close()
            
            println("--- MEASUREMENT 1: Interval 0 ---")
            println("Initial objects: $initial")
            println("Objects after 10000 cycles: $after")
            println("Difference: ${after - initial}")
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    @Test
    fun measureCyclicGarbageWithAutoDrain() = PythonTestFixture.withInterpreter {
        val previousInterval = Python3.autoDrainInterval
        try {
            Python3.autoDrainInterval = 32
            
            val builtins = Python3.import("builtins")
            val gc = Python3.import("gc")
            gc.getAttr("collect").invoke().close()
            val initial = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            
            val listType = builtins.getAttr("list")
            for (i in 1..10000) {
                val a = listType()
                val b = listType()
                val aAppend = a.getAttr("append")
                val bAppend = b.getAttr("append")
                aAppend(b).close()
                bAppend(a).close()
                aAppend.close()
                bAppend.close()
                a.close()
                b.close()
            }
            
            val after = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            gc.close()
            listType.close()
            builtins.close()
            
            println("--- MEASUREMENT 2: Interval 32 ---")
            println("Initial objects: $initial")
            println("Objects after 10000 cycles: $after")
            println("Difference: ${after - initial}")
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    private fun installBridge() {
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${python.native.ffi.UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${python.native.ffi.UpcallStub.invokeWithArgsStubAddr}
            )

            class _PmBound:
                __slots__ = ('_handle',)
                def __init__(self, name):
                    self._handle = _pm_resolve(name.encode('utf-8'))
                def __call__(self, *args):
                    return _pm_invoke(self._handle, args)
            """.trimIndent()
        )
    }

    @Test
    fun testReentrancyDuringCheckpoint() = PythonTestFixture.withInterpreter {
        python.multiplatform.reflection.UpcallTable.install(listOf(python.multiplatform.ffi.upcall.TrampolineFragment))
        try {
            installBridge()
            Python3.exec("""
                import sys
                class DyingObject:
                    def __del__(self):
                        print("Python __del__ running!")
                        sys.stdout.flush()
                        _PmBound('trampoline.discard')(1)
                        print("Python __del__ finished!")
                        sys.stdout.flush()
            """.trimIndent())
            
            // Create DyingObject cycle using C API so GC doesn't run yet
            val main = Python3.import("__main__")
            val dyingClass = main.getAttr("DyingObject")
            val a = dyingClass()
            a.setAttr("cycle", a)
            a.close()
            dyingClass.close()
            main.close()
            
            // Allocate enough lists from C API to schedule GC
            val builtins = Python3.import("builtins")
            val listType = builtins.getAttr("list")
            for (i in 1..20000) {
                listType().close()
            }
            listType.close()
            builtins.close()
            
            println("Triggering PyGC_Collect from Kotlin...")
            python.multiplatform.ffi.withGIL {
                python.native.ffi.PyGC_Collect()
            }
            println("PyGC_Collect finished.")
        } finally {
            python.multiplatform.reflection.UpcallTable.clear()
            python.multiplatform.reflection.HandleTable.releaseAll()
        }
    }
}
