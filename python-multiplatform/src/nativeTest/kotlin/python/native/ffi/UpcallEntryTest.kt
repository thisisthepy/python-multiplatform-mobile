@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package python.native.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
// The operator that makes a CPointer<CFunction<...>> callable is a top-level declaration, not a
// member, so it has to be imported by name for `resolve(...)` below to mean a C call at all.
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin/Native counterpart of `UpcallArgumentsTest` (desktopTest): Python builds a real
 * argument tuple, calls through a C function pointer into Kotlin, and reads the result back.
 *
 * Everything between those two ends is `commonMain`'s `UpcallTrampoline`, already covered by
 * `UpcallTrampolineTest`, which drives it from Kotlin. What is only testable here is the step
 * that test cannot reach: **Python doing the calling**, through a pointer this platform published.
 *
 * ### Why this is in `nativeTest` and not `commonTest`
 *
 * The marshalling is shared and its tests are shared; the address-publishing step is by
 * definition per-platform, and there is no `expect` for it. Making one would force an `actual` on
 * `androidMain` (which needs a `RegisterNatives` shim -- a separate task) and on `wasmJsMain`
 * (where there is no C function pointer to hand out and no `ctypes` to receive it), so the
 * `expect` would exist only to be unimplementable on two of five targets. `nativeTest` covers
 * both targets that share `nativeMain`: it runs on the iOS simulator and compiles for
 * androidNative.
 *
 * ### Why the bridge is a `PyMethodDef` and not `ctypes`
 *
 * `docs/upcall-design.md` names `ctypes.CDLL(None)` as this platform's route. Neither half of it
 * survives on iOS: the `Python.framework` in this project's distribution has no `_ctypes` and no
 * `lib-dynload` at all, so `import ctypes` fails outright, and the `@CName` symbols are not in
 * the linked binary either. [UpcallEntry] records both measurements. So the bridge here hands
 * Python a genuine built-in function object, which is what the desktop test's `ctypes` shim was
 * standing in for in the first place.
 */
class UpcallEntryTest {

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
     * Publishes the three-function bootstrap and wraps it the way a proxy type would.
     *
     * `_pm_bind` is called once per name and the callable it returns is kept, which is the whole
     * point of the handle: the string is hashed once, and every call after that carries an
     * integer inside the function object's `self`.
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
            // a Kotlin reference on this target any more than on any other.
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
            // A Throwable crossing back into C terminates the process on Kotlin/Native -- there is
            // no unwinding to catch it further out. If this test reports at all, it did not escape.
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
     * The raw `pm_upcall_*` entry points, called **through their published addresses** with the C
     * ABI rather than as Kotlin functions.
     *
     * This is the whole of what a foreign caller does: `ctypes` on Android, or a C host anywhere.
     * Calling `pmUpcallInvoke` directly from Kotlin would prove nothing about the shape -- the
     * compiler would pick the Kotlin calling convention and the `staticCFunction` wrapper, which
     * is where a mismatched signature actually shows up, would never run.
     *
     * `dlsym` is deliberately not how the addresses are obtained; see [UpcallEntry] for the
     * measurement that settled why.
     */
    @Test
    fun theRawEntryPointsAreCallableCFunctionsOfTheDocumentedShape() =
        PythonTestFixture.withInterpreter {
            val resolve = UpcallEntry.resolveAddress
                .toCPointer<CFunction<(CPointer<ByteVar>?) -> Long>>()
            val invoke = UpcallEntry.invokeAddress
                .toCPointer<CFunction<(Long, COpaquePointer?) -> COpaquePointer?>>()
            val release = UpcallEntry.releaseObjectAddress
                .toCPointer<CFunction<(Long) -> Int>>()
            assertNotNull(resolve)
            assertNotNull(invoke)
            assertNotNull(release)

            val handle = memScoped { resolve("trampoline.Target.<init>".cstr.ptr) }
            assertTrue(handle >= 0, "pm_upcall_resolve did not find a name the table holds")
            assertEquals(-1L, memScoped { resolve("trampoline.nothingIsCalledThis".cstr.ptr) })

            // A tuple built by the interpreter, passed as a borrowed PyObject* exactly as CPython
            // would pass it. `keepAlive` is not optional: nothing else names this tuple, and a
            // cleaner firing between these two lines would free what the entry point is walking.
            val keepAlive = PythonTestFixture.eval("('kotlin',)")
            val rootedBefore = HandleTable.liveCount
            val raw = invoke(handle, keepAlive.pointer.toPlatformPointer())
            assertNotNull(raw, "pm_upcall_invoke returned NULL through its published address")

            // The result is a new reference and the wrapper adopts it rather than taking a second.
            val objectHandle = PyObject(raw.toLong().toNativePointer()!!, false).toString().toLong()
            assertEquals(rootedBefore + 1, HandleTable.liveCount)
            assertEquals(1, release(objectHandle))
            assertEquals(rootedBefore, HandleTable.liveCount)
            assertEquals(0, release(objectHandle), "a double release must be a no-op")
        }
}
