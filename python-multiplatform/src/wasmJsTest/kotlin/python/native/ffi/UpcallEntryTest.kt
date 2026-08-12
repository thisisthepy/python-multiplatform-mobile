package python.native.ffi

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The wasmJs counterpart of `UpcallArgumentsTest` (desktopTest) and `UpcallEntryTest`
 * (nativeTest): Python builds a real argument tuple and calls through the actual published
 * entry point, not through `UpcallTrampoline.invoke` called directly from Kotlin the way
 * `UpcallTrampolineTest` (commonTest) does.
 *
 * "Actual published entry point" here means CPython calling the `@WasmExport`ed `pmp_invoke`
 * through `call_indirect` after `WebAssembly.Table.set` installed it -- `UpcallEntry.bind` builds
 * a real `PyCFunction` over exactly that, so every call in this suite crosses through the table
 * the way `docs/upcall-design.md` and ROADMAP §11 describe, with no shortcut back into Kotlin.
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
        if (PythonTestFixture.available) Python3.exec("_pm_bound = None")
    }

    /**
     * Resolves [name] once, the way a real binder would, and installs the [UpcallEntry.bind]
     * callable under `_pm_bound` in `__main__`'s namespace so Python can call it directly.
     *
     * Every raw C API call here is wrapped in [withGIL] -- not a style preference. `UpcallEntry`'s
     * own `PyGILState_Ensure`/`Release` pairs genuinely detach this thread once their nesting
     * count returns to zero, unlike a build where the main thread stays implicitly attached
     * forever; a bare call made right after one returns segfaults with "memory access out of
     * bounds" on the very next unrelated C API call. Every other test in this codebase either
     * goes through `Python3`/`PythonTestFixture` (which wrap themselves) or through `withGIL`
     * explicitly -- this pins that convention rather than relying on it silently.
     */
    private fun bindInto(name: String) {
        val handle = UpcallTable.resolve(name)
        assertNotEquals(-1L, handle.raw, "trampoline table does not know '$name'")
        val callable = UpcallEntry.bind(handle.raw) ?: error("UpcallEntry.bind returned null for '$name'")
        withGIL {
            val globals = PythonTestFixture.mainGlobals()
            try {
                assertEquals(0, PyDict_SetItemString(globals.pointer, "_pm_bound", callable))
            } finally {
                // PyDict_SetItemString takes its own reference; this one was ours to give up.
                Py_DecRef(callable)
            }
        }
    }

    /** Evaluates [expression] in `__main__` and returns its `str()`. */
    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    @Test
    fun pythonCallsTheRealWasmExportThroughCallIndirectWithArgumentsOfEveryMarshalledTag() =
        PythonTestFixture.withInterpreter {
            bindInto("trampoline.add")
            Python3.exec("_pm_r_int = _pm_bound(3, 4)")
            assertEquals("7", py("_pm_r_int"))

            bindInto("trampoline.scale")
            Python3.exec("_pm_r_float = _pm_bound(2.5, 3.0)")
            assertEquals("7.5", py("_pm_r_float"))
            assertEquals("<class 'float'>", py("type(_pm_r_float)"))

            bindInto("trampoline.negate")
            Python3.exec("_pm_r_bool = _pm_bound(False)")
            assertEquals("True", py("_pm_r_bool"))
            // The types are Python's own, not stand-ins: `True` must not arrive as `1`.
            assertEquals("<class 'bool'>", py("type(_pm_r_bool)"))

            bindInto("trampoline.greet")
            Python3.exec("_pm_r_str = _pm_bound('파이썬')")
            assertEquals("Hello, 파이썬!", py("_pm_r_str"))

            bindInto("trampoline.discard")
            Python3.exec("_pm_r_unit = _pm_bound(1)")
            assertEquals("None", py("_pm_r_unit"))
        }

    @Test
    fun pythonConstructsAKotlinObjectAndCallsAMethodOnItThroughTheSameEntryPoint() =
        PythonTestFixture.withInterpreter {
            val rootedBefore = HandleTable.liveCount

            bindInto("trampoline.Target.<init>")
            Python3.exec("_pm_obj = _pm_bound('kotlin')")
            // Python holds the Kotlin object only as an ObjectReference integer -- it cannot hold
            // a Kotlin reference on this target any more than on any other.
            assertEquals("<class 'int'>", py("type(_pm_obj)"))
            assertEquals(
                rootedBefore + 1, HandleTable.liveCount,
                "the constructor must root exactly one object",
            )

            bindInto("trampoline.Target.combine")
            Python3.exec("_pm_combined = _pm_bound(_pm_obj, 3, '!')")
            assertEquals("kotlin*3!", py("_pm_combined"))

            // What a proxy's tp_dealloc is for. Without it the root outlives every Python
            // reference to it, which HandleTable's own doc calls leaking by construction.
            val objHandle = py("_pm_obj").toLong()
            assertEquals(1, releaseHandle(objHandle))
            assertEquals(rootedBefore, HandleTable.liveCount)
            assertEquals(0, releaseHandle(objHandle), "a double release must be a no-op")
        }

    @Test
    fun aKotlinExceptionArrivesInPythonAsARaiseRatherThanKillingTheProcess() =
        PythonTestFixture.withInterpreter {
            bindInto("trampoline.explode")
            // A Throwable escaping the wasm export terminates the process. If this test reports
            // at all, it did not escape.
            Python3.exec(
                """
                try:
                    _pm_bound()
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
            bindInto("trampoline.add")
            Python3.exec(
                """
                try:
                    _pm_arity = _pm_bound(1)
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
    fun theReferenceReturnedToPythonIsTakenOverExactlyOnce() = PythonTestFixture.withInterpreter {
        // `trampoline.shared` hands back one fixed object every time, so anything the count does
        // across a hundred calls is the boundary's doing. Both failure directions are fatal and
        // both are visible here: one reference too few per call drives it to zero and frees an
        // object Kotlin still holds, one too many leaks it for the life of the interpreter.
        TrampolineFragment.shared = PythonTestFixture.eval("{'k': 'v'}")
        try {
            bindInto("trampoline.shared")
            Python3.exec("_pm_shared_fn = _pm_bound")
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
            // The bug this pins is the one that crashed this repo twice: wrapping a *borrowed*
            // tuple item with `borrowed = false` gives one reference back per call that was never
            // taken. Over 200 calls the object would be freed while `_pm_holder` still names it,
            // so the count would fall (or the process would die), never rise.
            bindInto("trampoline.describe")
            Python3.exec("_pm_holder = [1, 2, 3]")
            Python3.exec(
                """
                _pm_before = __import__('sys').getrefcount(_pm_holder)
                for _ in range(200):
                    _pm_bound(_pm_holder)
                _pm_after = __import__('sys').getrefcount(_pm_holder)
                """.trimIndent(),
            )

            val before = py("_pm_before").toLong()
            val after = py("_pm_after").toLong()
            assertTrue(after >= before, "200 calls dropped the refcount of a borrowed argument by ${before - after}")
            assertEquals("[1, 2, 3]", py("_pm_bound(_pm_holder)"))
        }

    /** Drops the [HandleTable] root behind [objectHandle], reusing the marshaller's own release. */
    private fun releaseHandle(objectHandle: Long): Int =
        python.multiplatform.ffi.upcall.UpcallTrampoline.releaseObject(objectHandle)
}
