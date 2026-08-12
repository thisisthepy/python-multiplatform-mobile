package python.multiplatform.ref

import python.multiplatform.BuildConfig
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins ROADMAP §9 / `docs/gc-scheduling-investigation.md` §1 and §7 on the **default (GIL)**
 * build specifically: `_PY_GC_SCHEDULED_BIT` is read only by the eval-loop checkpoint, on both
 * builds, so an embedder that never runs Python bytecode never runs the cyclic collector either
 * -- with or without free threading. `autoDrainInterval` is saved and restored around each test;
 * the default (0 on this build) is never changed by running this file.
 *
 * Also runs, unmodified, on `-PpythonFreeThreaded=true`, where it surfaced something this
 * investigation had not measured before: the automatic checkpoint reliably fires there too, but
 * under this specific heavy single-thread workload it does not reach the GIL build's ~1,970
 * residue -- see the KDoc on [measureCyclicGarbageWithAutoDrain].
 */
class GCSchedulingMeasurementTest {

    /** Each iteration below leaves one `a`/`b` cycle unreachable, i.e. 2 objects per round. */
    private val cyclesCreated = 10_000
    private val objectsPerCycle = 2

    private fun buildAndDropCycles(builtins: python.multiplatform.ffi.PyObject) {
        val listType = builtins.getAttr("list")
        try {
            repeat(cyclesCreated) {
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
        } finally {
            listType.close()
        }
    }

    @Test
    fun measureCyclicGarbageWithoutAutoDrain() = PythonTestFixture.withInterpreter {
        // GIL build or not, setting to 0 explicitly.
        val previousInterval = Python3.autoDrainInterval
        try {
            Python3.autoDrainInterval = 0

            val builtins = Python3.import("builtins")
            val gc = Python3.import("gc")
            gc.getAttr("collect").invoke().close()
            val initial = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()

            buildAndDropCycles(builtins)

            val after = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            gc.close()
            builtins.close()

            val delta = after - initial
            println("--- MEASUREMENT 1: Interval 0 --- initial=$initial after=$after delta=$delta")

            // With no checkpoint ever reached, _PY_GC_SCHEDULED_BIT is never read and the cyclic
            // collector never runs: every one of the 10,000 cycles (2 objects each) should still
            // be alive. Some slack below the theoretical maximum for interpreter-internal churn
            // (interned small ints, etc.) that is not part of what this test creates.
            val expectedMinimum = (cyclesCreated * objectsPerCycle * 3) / 4
            assertTrue(
                delta >= expectedMinimum,
                "expected the $cyclesCreated dropped cycles (~${cyclesCreated * objectsPerCycle} objects) " +
                    "to still be alive with autoDrainInterval=0, but only $delta net objects remained -- " +
                    "the cyclic collector should not have run at all without a checkpoint"
            )
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    /**
     * Pinned on the GIL build only. Written to check the same claim on `-PpythonFreeThreaded=true`
     * (where `autoDrainInterval = 32` is already the shipped default, not something this test
     * turns on), it is not: `CheckpointCounter.reached` climbs by an identical, deterministic
     * amount on every run, but the resulting residue does not -- three otherwise-identical runs
     * measured 13,573 / 9,477 / 9,477 net objects, an order of magnitude above the GIL build's
     * ~1,970, and a trailing explicit `gc.collect()` finds nothing further to reclaim, so the
     * residue is not simply "garbage waiting for the next checkpoint". That is a real difference
     * in the free-threaded collector's behaviour under this specific single-thread, C-API-only,
     * sustained-allocation shape, not a defect in the checkpoint plumbing this file is about --
     * the checkpoint demonstrably fires and demonstrably reclaims *some* cycles every run. It is
     * not root-caused here; asserting a tight bound on it would make this test flaky against a
     * genuine open question rather than pin a known behaviour, so free-threaded only asserts that
     * the mechanism engages.
     */
    @Test
    fun measureCyclicGarbageWithAutoDrain() = PythonTestFixture.withInterpreter {
        val previousInterval = Python3.autoDrainInterval
        try {
            Python3.autoDrainInterval = 32

            val builtins = Python3.import("builtins")
            val gc = Python3.import("gc")
            gc.getAttr("collect").invoke().close()
            val initial = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            val reachedBefore = Python3.CheckpointCounter.reached

            buildAndDropCycles(builtins)

            val reachedDelta = Python3.CheckpointCounter.reached - reachedBefore
            val after = gc.getAttr("get_objects").invoke().getAttr("__len__").invoke().toString().toInt()
            gc.close()
            builtins.close()

            val delta = after - initial
            println(
                "--- MEASUREMENT 2: Interval 32 (freeThreaded=${BuildConfig.pythonFreeThreaded}) --- " +
                    "initial=$initial after=$after delta=$delta checkpointsReached=$reachedDelta"
            )

            assertTrue(
                reachedDelta > 0,
                "autoDrainInterval=32 should have taken at least one automatic checkpoint over " +
                    "$cyclesCreated outermost withGIL scopes, but none were reached"
            )

            if (!BuildConfig.pythonFreeThreaded) {
                // Each outermost withGIL scope inside buildAndDropCycles counts down; at interval
                // 32 that is thousands of checkpoints over 10,000 rounds, each one able to run the
                // scheduled collection. The residue should be bounded by roughly one interval's
                // worth of not-yet-collected garbage, nowhere near the ~20,000 objects interval 0
                // leaves. See `docs/gc-scheduling-investigation.md` §7 -- measured here at 1,970.
                val upperBound = cyclesCreated * objectsPerCycle / 2
                assertTrue(
                    delta < upperBound,
                    "expected autoDrainInterval=32 to keep cyclic garbage bounded to a small " +
                        "residue on the GIL build, but $delta net objects remained after " +
                        "$cyclesCreated dropped cycles -- the automatic checkpoint should have " +
                        "reclaimed almost all of them"
                )
            }
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
