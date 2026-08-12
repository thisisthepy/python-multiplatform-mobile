package python.native.ffi

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROADMAP §13's "what the sample still cannot show", closed on desktop.
 *
 * Every earlier upcall test called `UpcallTable` from Kotlin. This one goes the whole way round:
 * Python resolves a name to a handle, builds a real argument tuple, calls through a **Panama
 * upcall stub** into Kotlin, and reads the result back -- which until now was impossible because
 * both stubs were `(long) -> long` and could carry nothing but the handle.
 *
 * The stub shape used here, `(long, long) -> long`, is deliberately the same one a
 * `PyCFunction` slot needs (`PyObject *(PyObject *self, PyObject *args)`): when the generated
 * proxy type lands, `self` takes the handle's place and no new stub shape is needed.
 */
class UpcallArgumentsTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(TrampolineFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        if (PythonTestFixture.available) Python3.exec("_pm_results = None")
    }

    /**
     * The `ctypes` stand-in for the proxy type a finished binder installs.
     *
     * `restype=py_object` makes the result readable from Python, and it consumes the trampoline's
     * new reference rather than taking one of its own -- measured, not assumed, by
     * [theReferenceReturnedToPythonIsTakenOverExactlyOnce]. That is the same ownership a real
     * `PyCFunction` slot has, so this shim exercises the contract the generated proxy type will.
     *
     * `CFUNCTYPE` and not `PYFUNCTYPE` on purpose. `CFUNCTYPE` **releases the GIL** around the
     * call, which is the harder case and the one that segfaulted every C API call in the
     * trampoline until it stopped trusting the thread's nesting depth. Keeping it here means that
     * regression cannot come back unnoticed.
     */
    private fun installBridge() {
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${UpcallStub.invokeWithArgsStubAddr}
            )
            # What a proxy's tp_dealloc would call. Reuses the (long) -> int shape Panama already
            # builds for tp_clear, so it costs no stub of its own.
            _pm_release = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)(
                ${UpcallStub.releaseObjectStubAddr}
            )

            class _PmBound:
                '''What a proxy method is: a name resolved once, an integer from then on.'''
                __slots__ = ('_handle',)

                def __init__(self, name):
                    self._handle = _pm_resolve(name.encode('utf-8'))
                    if self._handle == -1:
                        raise AttributeError(name)

                def __call__(self, *args):
                    return _pm_invoke(self._handle, args)
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
    fun pythonConstructsAKotlinObjectAndCallsAMethodOnItThroughTheSameStub() =
        PythonTestFixture.withInterpreter {
            installBridge()
            val rootedBefore = HandleTable.liveCount
            Python3.exec(
                """
                _pm_obj = _PmBound('trampoline.Target.<init>')('kotlin')
                _pm_combined = _PmBound('trampoline.Target.combine')(_pm_obj, 3, '!')
                """.trimIndent(),
            )

            // The constructor's result is the ObjectReference integer -- Python holds the Kotlin
            // object only as a handle, exactly as docs/object-lifetime.md describes.
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
            // A Throwable escaping a Panama upcall stub terminates the VM. If this test reports
            // at all, it did not escape.
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
    fun anUnknownNameResolvesToTheNoneHandleRatherThanCallingSomethingElse() =
        PythonTestFixture.withInterpreter {
            installBridge()
            assertEquals("-1", py("_pm_resolve(b'trampoline.nothingIsCalledThis')"))
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
}
