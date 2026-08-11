package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop half of the JNI/Panama overhead comparison.
 *
 * Mirrors `JniOverheadBenchmark` on Android as closely as the two platforms allow: same
 * warmup, same iteration count, same best-of-rounds, same subtraction of a pure-Kotlin
 * identity call so loop and timing cost do not get counted as FFI cost, and the same real
 * C API call -- `PyList_Size` on `sys.path`.
 *
 * CAVEAT, and it is a large one. This runs on the host CPU while the Android figures come
 * from a phone-class core, so the absolute nanoseconds are not comparable and no honest
 * statement of the form "Android costs N times desktop" can be built from them. What IS
 * comparable is each platform's transition measured against its own floor, which is what
 * this reports.
 *
 * The desktop path currently reaches Panama through `MethodHandle.invoke` rather than
 * `invokeExact`, which forces an asType adaptation and boxes arguments and results on every
 * call. This benchmark is also the first measurement of what that costs.
 */
class DesktopOverheadBenchmark {

    private companion object {
        const val WARMUP = 400_000
        const val ITERS = 2_000_000
        const val ROUNDS = 7
    }

    private var sink = 0L

    private fun kotlinEcho(x: Long): Long = x

    private inline fun timeOnce(body: (Long) -> Long): Double {
        var local = 0L
        val t0 = System.nanoTime()
        for (i in 0 until ITERS) local += body(i.toLong())
        val elapsed = System.nanoTime() - t0
        sink += local
        return elapsed.toDouble() / ITERS
    }

    @Test
    fun panamaTransitionCost() {
        if (Py_IsInitialized() == 0) Py_Initialize()

        val sys = PyImport_ImportModule("sys")
        assertTrue(sys != null, "could not import sys")
        val path = PyObject_GetAttrString(sys!!, "path")
        assertTrue(path != null, "could not read sys.path")

        val len = PyList_Size(path!!)
        assertTrue(len > 0, "sys.path should be a non-empty list, got $len")

        var w = 0L
        repeat(WARMUP) { i -> w += kotlinEcho(i.toLong()) + PyList_Size(path) }
        sink += w

        var bestKotlin = Double.MAX_VALUE
        var bestPanama = Double.MAX_VALUE
        repeat(ROUNDS) {
            timeOnce { kotlinEcho(it) }.let { if (it < bestKotlin) bestKotlin = it }
            timeOnce { PyList_Size(path) }.let { if (it < bestPanama) bestPanama = it }
        }

        val net = bestPanama - bestKotlin
        println("=== desktop / ${System.getProperty("os.arch")} / JDK ${System.getProperty("java.version")} ===")
        println(String.format("kotlin floor              %8.2f ns/call", bestKotlin))
        println(String.format("PyList_Size via Panama    %8.2f ns/call   net %7.2f ns", bestPanama, net))
        println("sink=$sink")

        assertTrue(bestPanama > 0, "measurement produced no elapsed time")
    }


    /**
     * How much of a realistic desktop call is string marshalling.
     *
     * On Android, composing `Python3.exec` nearly halved it -- 17.8 us to 10.1 us -- and the
     * cost turned out not to be boundary crossings at all: the per-call figure was identical on
     * devices whose per-crossing cost differs by 20x. It was the three allocate/copy/free round
     * trips for the C strings. Desktop marshals strings too, through `withUtf8`, so the same
     * question applies here even though a crossing costs only 2.65 ns.
     *
     * This measures it without building a composed path. Both variants run exactly the same
     * three C calls; the only difference is whether the C strings are allocated per iteration
     * or once up front. The gap is what composing could remove.
     */
    @Test
    fun stringMarshallingShareOfARealisticCall() {
        if (Py_IsInitialized() == 0) Py_Initialize()

        val code = "x = 1 + 1"
        val fileInput = 257
        val iters = 20_000

        fun timeBest(body: () -> Long): Double {
            var best = Double.MAX_VALUE
            repeat(5) {
                var local = 0L
                val t0 = System.nanoTime()
                for (i in 0 until iters) local += body()
                val ns = (System.nanoTime() - t0).toDouble() / iters
                sink += local
                if (ns < best) best = ns
            }
            return best
        }

        // Allocates "__main__", "__dict__" and the source on every iteration, which is what
        // Python3.exec does today.
        val perCall = timeBest {
            val mod = bindings.PyImport_AddModuleRef("__main__")
            var rc = 0L
            if (mod != 0L) {
                val globals = bindings.PyObject_GetAttrString(mod, "__dict__")
                if (globals != 0L) {
                    val r = bindings.PyRun_String(code, fileInput, globals, globals)
                    if (r != 0L) { bindings.Py_DecRef(r); rc = 1L }
                    bindings.Py_DecRef(globals)
                }
                bindings.Py_DecRef(mod)
            }
            rc
        }

        // Same three calls, same work, but the C strings are allocated once.
        val mainAddr = PanamaBackend.allocateUtf8Freeable("__main__")
        val dictAddr = PanamaBackend.allocateUtf8Freeable("__dict__")
        val codeAddr = PanamaBackend.allocateUtf8Freeable(code)
        val hoisted = try {
            timeBest {
                val mod = bindings.PyImport_AddModuleRefHandle.invoke(mainAddr) as Long
                var rc = 0L
                if (mod != 0L) {
                    val globals = bindings.PyObject_GetAttrStringHandle.invoke(mod, dictAddr) as Long
                    if (globals != 0L) {
                        val r = bindings.PyRun_StringHandle.invoke(codeAddr, fileInput, globals, globals) as Long
                        if (r != 0L) { bindings.Py_DecRef(r); rc = 1L }
                        bindings.Py_DecRef(globals)
                    }
                    bindings.Py_DecRef(mod)
                }
                rc
            }
        } finally {
            PanamaBackend.freeUtf8Address(mainAddr)
            PanamaBackend.freeUtf8Address(dictAddr)
            PanamaBackend.freeUtf8Address(codeAddr)
        }

        println("=== desktop: string marshalling share of exec(\"$code\") ===")
        println(String.format("allocating per call   %9.2f ns", perCall))
        println(String.format("strings hoisted       %9.2f ns", hoisted))
        println(String.format("marshalling share     %9.2f ns  (%.1f%% of the call, %.2fx)",
            perCall - hoisted, 100.0 * (perCall - hoisted) / perCall, perCall / hoisted))
        println("sink=$sink")

        assertTrue(perCall > 0 && hoisted > 0, "measurement produced no elapsed time")
    }
}
