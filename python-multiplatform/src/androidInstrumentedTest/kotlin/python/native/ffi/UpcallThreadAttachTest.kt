package python.native.ffi

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Android's half of `UpcallEntryTest`'s (`commonTest`) contract resolves through the same
 * `_pm_resolve`/`_pm_bind`/`_pm_release` `PyMethodDef` bootstrap as `nativeTest` -- see
 * [UpcallEntry.publish] -- but with `androidMain`'s JNI inversion underneath: the entry points are
 * C functions in `artMain/cinterop/jni_onload.def` and *they* call *Kotlin*, through
 * [UpcallCallbacks].
 *
 * The one case only this target has -- an upcall arriving on a thread ART has never seen -- has no
 * `commonTest` counterpart (desktop, Kotlin/Native and wasmJs all reach Kotlin without a runtime
 * boundary at all, so a `threading.Thread` there is just another thread) and stays here.
 */
actual fun bindUpcallOrNull(name: String): Boolean {
    val globals: PyObject = PythonTestFixture.mainGlobals()
    check(UpcallEntry.publish(globals.pointer)) { "the upcall bootstrap could not be published" }
    Python3.exec("_pm_h = _pm_resolve('$name')")
    val resolved = PythonTestFixture.eval("_pm_h").toString() != "-1"
    Python3.exec(if (resolved) "_pm_bound = _pm_bind(_pm_h)" else "_pm_bound = None")
    return resolved
}

/** Releases through `_pm_release`, the same `PyMethodDef` a proxy's `tp_dealloc` would call. */
actual fun releaseUpcallHandle(handle: Long): Int =
    PythonTestFixture.eval("_pm_release($handle)").toString().toInt()

class UpcallThreadAttachTest {

    @BeforeTest
    fun install() {
        PythonOnDevice.ensureInitialised()
        UpcallTable.install(listOf(ThreadProbeFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** Evaluates [expression] in `__main__` and returns its `str()`. */
    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

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
            assertTrue(bindUpcallOrNull("probe.whichThread"))
            val instrumentationThread = Thread.currentThread().id
            ThreadProbeFragment.lastThreadId = -1L
            Python3.exec(
                """
                import threading
                _pm_worker_result = None

                def _pm_worker():
                    global _pm_worker_result
                    _pm_worker_result = _pm_bound(0)

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
