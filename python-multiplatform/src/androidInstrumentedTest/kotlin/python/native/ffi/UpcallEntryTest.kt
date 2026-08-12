package python.native.ffi

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Android's half of the upcall boundary: Python builds a real argument tuple, calls a C function
 * pointer, and reads back what Kotlin returned -- with the whole of ART's JNI boundary in the
 * middle.
 *
 * The desktop counterpart is `UpcallArgumentsTest` (a Panama upcall stub) and the Kotlin/Native
 * one is `nativeTest`'s `UpcallEntryTest` (a `staticCFunction`). Android has neither mechanism:
 * `androidMain` is Kotlin/JVM on ART, which has no Panama, and the `PyMethodDef` has to be filled
 * with a C function pointer that no JVM can produce. So the direction of the boundary is inverted
 * exactly as it already is for the proxy type's `tp_traverse`/`tp_clear` -- the entry points are C
 * functions in `artMain/cinterop/jni_onload.def`, and *they* call *Kotlin*, through
 * [UpcallCallbacks].
 *
 * Everything between the two ends is `commonMain`'s `UpcallTrampoline`, already covered by
 * `UpcallTrampolineTest`, which drives it from Kotlin on this very device. What is only testable
 * here is the step that test cannot reach: **Python doing the calling**, across JNI.
 */
class UpcallEntryTest {

    @BeforeTest
    fun install() {
        PythonOnDevice.ensureInitialised()
        UpcallTable.install(listOf(TrampolineFragment, ThreadProbeFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        if (PythonTestFixture.available) Python3.exec("_pm_results = None")
    }

    /**
     * Publishes the three-function bootstrap and wraps it the way a proxy type would.
     *
     * `_pm_bind` is called once per name and the callable it returns is kept, which is the whole
     * point of the handle: the string is hashed once, and every call after that carries an integer
     * inside the function object's `self`.
     */
    private fun installBridge() {
        val globals: PyObject = PythonTestFixture.mainGlobals()
        assertTrue(UpcallEntry.publish(globals.pointer), "the upcall bootstrap could not be published")
        Python3.exec(
            """
            class _PmBound:
                '''What a proxy method is: a name resolved once, a bound C function from then on.'''
                __slots__ = ('_handle', '_call')

                def __init__(self, name):
                    self._handle = _pm_resolve(name)
                    if self._handle == -1:
                        raise AttributeError(name)
                    self._call = _pm_bind(self._handle)

                def __call__(self, *args):
                    return self._call(*args)
            """.trimIndent(),
        )
    }

    /** Evaluates [expression] in `__main__` and returns its `str()`. */
    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    @Test
    fun pythonCallsKotlinWithArgumentsOfEveryMarshalledTagAndReadsTheResultBack() =
        PythonTestFixture.withInterpreter {
            installBridge()
            Python3.exec(
                """
                _pm_results = [
                    _PmBound('trampoline.add')(3, 4),
                    _PmBound('trampoline.scale')(2.5, 3.0),
                    _PmBound('trampoline.negate')(False),
                    _PmBound('trampoline.greet')('파이썬'),
                    _PmBound('trampoline.reverseBytes')(b'\x01\x00\xff'),
                    _PmBound('trampoline.discard')(1),
                ]
                """.trimIndent(),
            )

            assertEquals("7", py("_pm_results[0]"))
            assertEquals("7.5", py("_pm_results[1]"))
            assertEquals("True", py("_pm_results[2]"))
            assertEquals("Hello, 파이썬!", py("_pm_results[3]"))
            // Round-tripped through ByteArray, so the 0x00 and 0xff bytes had to survive a
            // marshaller that never decoded them as text.
            assertEquals("b'\\xff\\x00\\x01'", py("_pm_results[4]"))
            assertEquals("None", py("_pm_results[5]"))

            // The types are Python's own, not stand-ins: `True` must not arrive as `1`.
            assertEquals("<class 'bool'>", py("type(_pm_results[2])"))
            assertEquals("<class 'bytes'>", py("type(_pm_results[4])"))
            assertEquals("<class 'float'>", py("type(_pm_results[1])"))
        }

    @Test
    fun pythonConstructsAKotlinObjectAndCallsAMethodOnItThroughTheSameEntryPoint() =
        PythonTestFixture.withInterpreter {
            installBridge()
            val rootedBefore = HandleTable.liveCount
            Python3.exec(
                """
                _pm_obj = _PmBound('trampoline.Target.<init>')('kotlin')
                _pm_combined = _PmBound('trampoline.Target.combine')(_pm_obj, 3, '!')
                """.trimIndent(),
            )

            // Python holds the Kotlin object only as an ObjectReference integer -- it cannot hold
            // a JVM reference any more than Kotlin/Native could hold one.
            assertEquals("<class 'int'>", py("type(_pm_obj)"))
            assertEquals("kotlin*3!", py("_pm_combined"))
            assertEquals(
                rootedBefore + 1, HandleTable.liveCount,
                "the constructor must root exactly one object, and the method call none",
            )

            // What a proxy's tp_dealloc is for. Without it the root outlives every Python
            // reference to it, which HandleTable's own doc calls leaking by construction.
            assertEquals("1", py("_pm_release(_pm_obj)"))
            assertEquals(rootedBefore, HandleTable.liveCount)
            assertEquals("0", py("_pm_release(_pm_obj)"), "a double release must be a no-op")
        }

    @Test
    fun aKotlinExceptionArrivesInPythonAsARaiseRatherThanKillingTheProcess() =
        PythonTestFixture.withInterpreter {
            installBridge()
            // A Throwable escaping into C has nowhere to unwind to. On ART it would also leave a
            // pending JVM exception across a JNI return, which aborts the runtime. If this test
            // reports at all, it did not escape.
            Python3.exec(
                """
                try:
                    _PmBound('trampoline.explode')()
                    _pm_raised = 'nothing was raised'
                except Exception as e:
                    _pm_raised = str(e)
                """.trimIndent(),
            )
            assertTrue(
                py("_pm_raised").contains("deliberate Kotlin failure"),
                "the Kotlin failure did not reach Python: ${py("_pm_raised")}",
            )
        }

    @Test
    fun aWrongArgumentCountIsRejectedAsAPythonExceptionRatherThanCallingTheTarget() =
        PythonTestFixture.withInterpreter {
            installBridge()
            Python3.exec(
                """
                try:
                    _pm_arity = _PmBound('trampoline.add')(1)
                except Exception as e:
                    _pm_arity = str(e)
                """.trimIndent(),
            )
            assertTrue(
                py("_pm_arity").contains("2 argument"),
                "expected an arity complaint naming the declared count, got: ${py("_pm_arity")}",
            )
        }

    @Test
    fun anUnknownNameResolvesToTheNoneHandleRatherThanCallingSomethingElse() =
        PythonTestFixture.withInterpreter {
            installBridge()
            assertEquals("-1", py("_pm_resolve('trampoline.nothingIsCalledThis')"))
        }

    @Test
    fun theReferenceReturnedToPythonIsTakenOverExactlyOnce() = PythonTestFixture.withInterpreter {
        installBridge()
        // `trampoline.shared` hands back one fixed object every time, so anything the count does
        // across a hundred calls is the boundary's doing. Both failure directions are fatal and
        // both are visible here: one reference too few per call drives it to zero and frees an
        // object Kotlin still holds, one too many leaks it for the life of the interpreter.
        TrampolineFragment.shared = PythonTestFixture.eval("{'k': 'v'}")
        try {
            Python3.exec("_pm_shared_fn = _PmBound('trampoline.shared')")
            Python3.exec("_pm_base = __import__('sys').getrefcount(_pm_shared_fn())")
            Python3.exec(
                """
                for _ in range(100):
                    _pm_shared_fn()
                _pm_end = __import__('sys').getrefcount(_pm_shared_fn())
                """.trimIndent(),
            )

            assertEquals(
                py("_pm_base"), py("_pm_end"),
                "100 calls moved the refcount of the returned object; the result's ownership is unbalanced",
            )
            assertEquals("{'k': 'v'}", py("_pm_shared_fn()"))
        } finally {
            TrampolineFragment.shared = null
        }
    }

    @Test
    fun theArgumentsPythonPassesAreBorrowedAndTheEntryPointMustNotConsumeThem() =
        PythonTestFixture.withInterpreter {
            installBridge()
            // The bug this pins is the one that crashed this repo twice: wrapping a *borrowed*
            // tuple item with `borrowed = false` gives one reference back per call that was never
            // taken. Over 200 calls the object would be freed while `_pm_holder` still names it,
            // so the count would fall (or the process would die), never rise.
            Python3.exec("_pm_holder = [1, 2, 3]")
            Python3.exec(
                """
                _pm_describe = _PmBound('trampoline.describe')
                _pm_before = __import__('sys').getrefcount(_pm_holder)
                for _ in range(200):
                    _pm_describe(_pm_holder)
                _pm_after = __import__('sys').getrefcount(_pm_holder)
                """.trimIndent(),
            )

            val before = py("_pm_before").toLong()
            val after = py("_pm_after").toLong()
            // Upwards is legitimate and unbounded here: each call's `PyObject(_, borrowed = true)`
            // wrapper holds its own reference until a cleaner reclaims it, and nothing forces one.
            // Downwards is the bug, and it is one per call.
            assertTrue(after >= before, "200 calls dropped the refcount of a borrowed argument by ${before - after}")
            // ...and the object survived intact, which a run of over-releases would not have left it.
            assertEquals("[1, 2, 3]", py("_pm_describe(_pm_holder)"))
        }

    /**
     * The case Android has and no other platform does: an upcall arriving on a thread ART has
     * never seen.
     *
     * Desktop and Kotlin/Native reach Kotlin without a runtime boundary at all, so a
     * `threading.Thread` is just another thread there. On ART the entry point has to obtain a
     * `JNIEnv` before it can call anything, and `GetEnv` fails on a bare pthread -- which is what
     * every `threading.Thread` is. That is the `AttachCurrentThreadAsDaemon` branch of
     * `pmp_attach`, and without it every upcall from a Python worker thread would silently return
     * `NULL`.
     *
     * The thread identity is recorded from *inside* the Kotlin the upcall lands in, so a pass
     * cannot come from the work having been re-dispatched back onto the instrumentation thread.
     */
    @Test
    fun anUpcallArrivesOnAThreadCPythonCreatedRatherThanFailingToFindTheJvm() =
        PythonTestFixture.withInterpreter {
            installBridge()
            val instrumentationThread = Thread.currentThread().id
            ThreadProbeFragment.lastThreadId = -1L
            Python3.exec(
                """
                import threading
                _pm_worker_result = None

                def _pm_worker():
                    global _pm_worker_result
                    _pm_worker_result = _PmBound('probe.whichThread')(0)

                _pm_t = threading.Thread(target=_pm_worker)
                _pm_t.start()
                _pm_t.join()
                """.trimIndent(),
            )

            val reported = py("_pm_worker_result").toLong()
            assertNotEquals(
                -1L, reported,
                "the upcall never reached Kotlin from the Python worker thread",
            )
            assertEquals(
                reported, ThreadProbeFragment.lastThreadId,
                "the value Python read back is not the one the upcall recorded",
            )
            assertNotEquals(
                instrumentationThread, reported,
                "the upcall was served on the instrumentation thread, so the attach branch was never taken",
            )
        }
}

/**
 * An exposed callable that reports the JVM thread it was executed on.
 *
 * Test-owned, and that is the point: nothing in production code needs a hook for this, because the
 * lambda *is* where the upcall lands. `CycleCollectionTest.TraverseThreadProbe` records the same
 * fact the same way for `tp_traverse`.
 *
 * Without it, "the worker's result came back" would only show the call completed, not that it was
 * served on a thread ART had never seen -- which is the whole claim `pmp_attach`'s
 * `AttachCurrentThreadAsDaemon` branch exists to support on this path.
 */
object ThreadProbeFragment : python.multiplatform.reflection.FunctionTableFragment {
    override val moduleName: String = "test_android_upcall_probe"

    @Volatile
    var lastThreadId: Long = -1L

    override fun entries(): List<python.multiplatform.reflection.ExposedCallable> = listOf(
        python.multiplatform.reflection.ExposedCallable(
            name = "probe.whichThread",
            arity = 1,
            paramTypes = listOf(python.multiplatform.reflection.TypeTag.INT),
            returnType = python.multiplatform.reflection.TypeTag.INT,
        ) {
            Thread.currentThread().id.also { lastThreadId = it }
        },
    )
}
