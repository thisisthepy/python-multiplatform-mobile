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
}
