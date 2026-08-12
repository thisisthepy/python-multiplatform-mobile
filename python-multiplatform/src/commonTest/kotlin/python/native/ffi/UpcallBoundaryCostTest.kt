package python.native.ffi

import python.multiplatform.currentPlatform
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.ffi.withGIL
import python.multiplatform.overhead.Benchmark
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What one upcall costs on **this** platform, priced against three things measured in the same run.
 *
 * `androidInstrumentedTest`'s `UpcallOverheadTest` did this for Android and answered the question
 * that target had -- whether the `AttachCurrentThreadAsDaemon` a Python worker needs is paid once
 * per thread or once per call. That question does not exist anywhere else: desktop, iOS,
 * androidNative and wasmJs all reach Kotlin without a second runtime to attach to. What *is* missing
 * everywhere else is the plain number, and without it the upcall path cannot be compared across
 * platforms the way `overhead/BenchmarkTest` lets the downcall path be compared.
 *
 * So this is the Android test with the attach half removed and nothing else changed: same loop
 * shape, same warmup counts, same three comparison baselines, same printed-not-asserted policy. It
 * lives in `commonTest` because every seam it needs is already common --
 * [python.native.ffi.bindUpcallOrNull] (`UpcallEntryTest`'s per-platform binding step),
 * [UpcallTrampoline], and the `expect` C API -- so one copy runs on all five targets, Android
 * included, and the Android row can be read next to the others from one source rather than two.
 *
 * ### The three baselines, and why a ratio needs all of them
 *
 * A per-call figure on its own says nothing: an emulator, a wasm host and a JVM do not share a
 * clock, a scheduler or an interpreter build. What travels between platforms is a *ratio* against
 * something measured on the same machine in the same run, and the three chosen here bracket the
 * boundary from both sides:
 *
 * - **`Py_IncRef` + `Py_DecRef`** -- two of the cheapest possible downcalls, so the floor of "cross
 *   the FFI boundary at all" on this platform.
 * - **`PyObject_CallObject` on a Python `def`** -- a downcall of the *same shape* as the upcall
 *   being measured: two integer arguments, one integer result, one callable. This is the
 *   denominator of the headline ratio.
 * - **`UpcallTrampoline.invoke` from Kotlin** -- the identical marshalling with the C shim and the
 *   platform boundary taken out. The gap between it and the Python-driven figure is what the
 *   boundary itself adds, with everything else held constant.
 *
 * The Python-side loop overhead (`_pm_time_loop`) and a pure-Python callee of the same shape
 * (`_pm_py_add`) are measured too, so the Python-driven numbers can be read net of what Python
 * charges for the loop and the call before anything crosses.
 *
 * ### The control the third baseline needs
 *
 * "Upcall minus trampoline = the boundary" is only true if the two differ by the boundary and
 * nothing else, and in the obvious arrangement they do not. [UpcallTrampoline] takes its own
 * `PyGILState_Ensure`/`Release` pair **unconditionally** -- it must, because a C caller may have
 * dropped the GIL inside a Kotlin scope that is still open -- so driving it from a bare Kotlin
 * thread pays a genuine GIL acquisition per call, while Python drives it already holding the GIL.
 * The first run of this test made that concrete: on iOS the trampoline came out *more* expensive
 * than the whole upcall through it, i.e. the "boundary cost" read as negative.
 *
 * So the trampoline is measured twice, differing in exactly that one thing -- caller holding
 * nothing, and caller inside a [withGIL] scope -- and an empty `Python3.withPython { }` is measured
 * beside them for scale. The second is the figure the boundary should be read against; the gap
 * between the two is this platform's GIL round trip, which is a cost of the *design* rather than of
 * the upcall path and is charged to downcalls just as much.
 *
 * ### One caveat that is real and is not measured away
 *
 * The Python-driven figures are timed *inside Python*, and what `_pm_bound` is differs by platform:
 * a `PyCFunction` built by a `PyMethodDef` on iOS/androidNative/Android, and on desktop a Python
 * `lambda *a: _pm_invoke(_pm_h, a)` over a `ctypes.CFUNCTYPE`. Desktop therefore pays one extra
 * Python call and ctypes' own argument conversion inside every figure below. `_pm_py_add` is the
 * scale of that extra call and is printed for exactly this reason; the ctypes conversion is on top
 * of it and is not separated here. Desktop's row is an upper bound on its boundary cost, not a
 * measurement of the boundary alone.
 *
 * ### Nothing here asserts a duration
 *
 * A wall-clock threshold would be a flake generator on a shared build machine, an emulator or a
 * Node host, and this repo has had exactly that failure. What is asserted is structural: the name
 * bound, the loop really called Kotlin (the result is checked), and every figure came back as a
 * usable positive number rather than a zero from a clock with no resolution -- which is the failure
 * mode that would otherwise be reported as "the upcall is free".
 */
class UpcallBoundaryCostTest {

    private companion object {
        /** Calls per timed loop. Large enough to swamp the clock, small enough for the wasm host. */
        const val N = 10_000

        /** Same shape as the measured loop, run first, and the same count the Android test uses. */
        const val WARMUP = 3_000

        const val DOWNCALL_REFCOUNT = "Py_IncRef + Py_DecRef (2 downcalls, a GIL scope each)"
        const val DOWNCALL_CALL = "PyObject_CallObject on a Python def (downcall, same shape)"
        const val TRAMPOLINE_ONLY = "UpcallTrampoline.invoke from Kotlin (no shim, no boundary)"

        /** Diagnostics for the confound below; not part of the headline ratios. */
        const val TRAMPOLINE_GIL_HELD = "  ...the same, with the GIL already held by the caller"
        const val GIL_SCOPE = "  ...Python3.withPython { } with nothing in it, for scale"
    }

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(TrampolineFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        if (PythonTestFixture.available) Python3.exec("_pm_bench = None")
    }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    private fun pyDouble(expression: String): Double = py(expression).toDouble()

    @Test
    fun anUpcallIsPricedAgainstADowncallOfTheSameShapeMeasuredInTheSameRun() =
        PythonTestFixture.withInterpreter {
            // The one per-platform step: whatever this target publishes, installed as `_pm_bound`.
            // Pinned under its own name straight away -- `bindUpcallOrNull` overwrites `_pm_bound`,
            // and on desktop it also rebuilds the ctypes shims, so nothing may be timed through it.
            assertTrue(bindUpcallOrNull("trampoline.add"), "trampoline.add did not bind on this platform")
            Python3.exec("_pm_add = _pm_bound")
            assertEquals(
                "7", py("_pm_add(3, 4)"),
                "the callable about to be timed does not reach trampoline.add",
            )

            Python3.exec(
                """
                import time

                def _pm_py_add(a, b):
                    '''A pure-Python callee of the same shape: the floor a Kotlin one is measured against.'''
                    return a + b

                def _pm_time_calls(fn, n):
                    t0 = time.perf_counter_ns()
                    for _ in range(n):
                        fn(3, 4)
                    return (time.perf_counter_ns() - t0) / n

                def _pm_time_loop(n):
                    '''The same loop with no call in it, so the call cost can be read net of Python's own.'''
                    t0 = time.perf_counter_ns()
                    for _ in range(n):
                        pass
                    return (time.perf_counter_ns() - t0) / n

                _pm_bench = {}
                """.trimIndent(),
            )

            // Warmup first, of the same shape and the same count as every measured loop. An
            // unwarmed first row in this repo once made a subset of the work look cheaper than the
            // whole of it, which is why this is not folded into the timed block.
            Python3.exec(
                """
                _pm_time_calls(_pm_add, $WARMUP)
                _pm_time_calls(_pm_py_add, $WARMUP)
                _pm_time_loop($WARMUP)

                _pm_bench['loop'] = _pm_time_loop($N)
                _pm_bench['py'] = _pm_time_calls(_pm_py_add, $N)
                _pm_bench['upcall'] = _pm_time_calls(_pm_add, $N)
                """.trimIndent(),
            )

            val loop = pyDouble("_pm_bench['loop']")
            val pyCall = pyDouble("_pm_bench['py']")
            val upcall = pyDouble("_pm_bench['upcall']")

            // Same interpreter, same machine, same moment. A ratio against a figure from another
            // run is not a measurement.
            val downcalls = measureDowncalls()

            report(loop, pyCall, upcall, downcalls)

            // ---- What is actually asserted: no durations, only that a duration was obtained ----
            assertTrue(upcall > 0.0, "the upcall loop measured ${upcall}ns/call; the clock has no resolution here")
            assertTrue(pyCall > 0.0, "the pure-Python callee measured ${pyCall}ns/call")
            assertTrue(loop >= 0.0, "the empty Python loop measured ${loop}ns/iteration")
            for ((name, ns) in downcalls) {
                assertTrue(ns > 0.0, "$name measured ${ns}ns/call; nothing can be compared against a zero")
            }
            assertEquals(
                5, downcalls.size,
                "every comparison baseline must come from this run, not from a remembered one",
            )
            // The upcall did not become a no-op somewhere in the loop.
            assertEquals("7", py("_pm_add(3, 4)"))
        }

    /**
     * The comparison basis, measured here so it shares this run's machine, interpreter and thermal
     * state. Deliberately not borrowed from `overhead/BenchmarkTest`, for that reason alone.
     *
     * Two of the three are the symmetric counterparts of the upcall rather than raw FFI: calling a
     * Python function from Kotlin is what a Kotlin -> Python call of the same shape costs, and
     * calling [UpcallTrampoline] directly is the same upcall *minus* the platform boundary, so the
     * difference between it and the Python-driven figure is what the boundary adds.
     */
    private fun measureDowncalls(): Map<String, Double> {
        val pyAdd = PythonTestFixture.eval("_pm_py_add")
        val handle = UpcallTable.resolve("trampoline.add").raw

        // (3, 4), built once. PyTuple_SetItem steals, so the two PyLongs are the tuple's.
        val args = Python3.withPython {
            val tuple = PyTuple_New(2L) ?: error("PyTuple_New failed")
            PyTuple_SetItem(tuple, 0L, PyLong_FromLongLong(3L) ?: error("PyLong_FromLongLong failed"))
            PyTuple_SetItem(tuple, 1L, PyLong_FromLongLong(4L) ?: error("PyLong_FromLongLong failed"))
            tuple
        }

        val results = LinkedHashMap<String, Double>()
        fun record(name: String, block: () -> Unit) {
            results[name] = Benchmark.measure(warmupIterations = N / 4, iterations = N, block = block)
        }

        try {
            // Balanced within one iteration, so the count is back where it started however many
            // times the loop runs -- including the warmup.
            val scratch = Python3.withPython { PyLong_FromLongLong(42L) } ?: error("PyLong_FromLongLong failed")
            try {
                record(DOWNCALL_REFCOUNT) {
                    Python3.withPython { Py_IncRef(scratch) }
                    Python3.withPython { Py_DecRef(scratch) }
                }
            } finally {
                Python3.withPython { Py_DecRef(scratch) }
            }

            record(DOWNCALL_CALL) {
                val r = Python3.withPython { PyObject_CallObject(pyAdd.pointer, args) }
                if (r != null) Python3.withPython { Py_DecRef(r) }
            }

            record(TRAMPOLINE_ONLY) {
                val r = UpcallTrampoline.invoke(handle, args.toRawValue())
                val p = r.toNativePointer()
                if (p != null) Python3.withPython { Py_DecRef(p) }
            }

            // The same body again, with exactly one thing changed: the caller already holds the
            // GIL, as Python does when it drives the upcall. `UpcallTrampoline` takes its own
            // `PyGILState_Ensure`/`Release` pair *unconditionally* -- it has to, see its class doc
            // -- so the row above is paying for a real GIL acquisition on every iteration that the
            // Python-driven figure does not pay. Without this control the difference between the
            // two would be read as "the boundary", and on at least one target that reading comes
            // out negative.
            withGIL {
                record(TRAMPOLINE_GIL_HELD) {
                    val r = UpcallTrampoline.invoke(handle, args.toRawValue())
                    val p = r.toNativePointer()
                    if (p != null) Python3.withPython { Py_DecRef(p) }
                }
            }

            // What one uncontended GIL round trip plus this platform's scope machinery costs, with
            // no C API call inside it at all -- the scale the two rows above differ by.
            record(GIL_SCOPE) {
                Python3.withPython { }
            }
        } finally {
            Python3.withPython { Py_DecRef(args) }
            pyAdd.close()
        }
        return results
    }

    private fun report(loop: Double, pyCall: Double, upcall: Double, downcalls: Map<String, Double>) {
        val downcallBasis = (downcalls[DOWNCALL_CALL] ?: 1.0).coerceAtLeast(1.0)
        val trampolineOnly = (downcalls[TRAMPOLINE_ONLY] ?: 1.0).coerceAtLeast(1.0)
        val trampolineHeld = (downcalls[TRAMPOLINE_GIL_HELD] ?: 1.0).coerceAtLeast(1.0)

        val lines = buildList {
            add("")
            add("--- Upcall boundary cost: ${currentPlatform.name} ---")
            add("iterations per loop: $N, warmup: $WARMUP")
            add("")
            add("Python-driven (timed inside Python)")
            add("  empty Python loop                     ${loop.ns()}")
            add("  pure-Python callee, same shape        ${pyCall.ns()}")
            add("  upcall to Kotlin                      ${upcall.ns()}")
            add("  upcall net of loop                    ${(upcall - loop).ns()}")
            add("  upcall net of a Python call           ${(upcall - pyCall).ns()}")
            add("")
            add("Comparison basis, same run (timed inside Kotlin)")
            for ((name, ns) in downcalls) add("  ${name.padEnd(54)}${ns.ns()}")
            add("")
            add("Ratios")
            add("  upcall / downcall of the same shape                        ${fmt(upcall / downcallBasis)}x")
            add("  upcall / trampoline alone, caller holding nothing          ${fmt(upcall / trampolineOnly)}x")
            add("  upcall / trampoline alone, GIL held (what the shim adds)   ${fmt(upcall / trampolineHeld)}x")
            add("-".repeat(72))
            add("")
        }
        // One println per line: a long single write is truncated by some of these test runners, and
        // these have to survive into the XML the run is read from.
        for (line in lines) println(line)
    }

    /** Sign handled separately: `upcall - pyCall` can legitimately be negative on a slow host. */
    private fun Double.ns(): String = "${if (this < 0) "-" else ""}${fmt(kotlin.math.abs(this))} ns"

    private fun fmt(v: Double): String {
        val whole = v.toLong()
        val hundredths = ((v - whole) * 100).toLong()
        return "$whole.${hundredths.toString().padStart(2, '0')}"
    }
}
