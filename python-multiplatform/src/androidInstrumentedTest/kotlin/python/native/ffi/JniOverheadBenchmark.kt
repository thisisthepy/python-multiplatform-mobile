package python.native.ffi

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what the JNI calling convention actually costs on this device.
 *
 * `echo0`, `echoFast` and `echoNormal` are the SAME one-line C body -- `jlong f(jlong x)
 * { return x; }` -- registered three times under the three conventions (see
 * artMain/cinterop/jni_onload.def). The callee work is identical and negligible, so the
 * difference between them is the transition cost and nothing else.
 *
 *   ordinary JNI    receives JNIEnv and jclass, and ART performs the GC thread-state
 *                   transition plus a local-reference frame around the call
 *   @FastNative     still receives JNIEnv and jclass, but skips the thread-state transition
 *   @CriticalNative receives neither, and skips the transition -- arguments go straight
 *                   through in registers
 *
 * A pure-Kotlin identity call is measured alongside as the floor, so loop and timing
 * overhead get subtracted rather than silently counted as native cost.
 *
 * METHODOLOGY. An earlier version warmed up and measured one variant at a time, and produced
 * results that contradicted each other across devices -- whichever native variant was measured
 * first looked anomalous on API 34. ART compiles asynchronously, so a variant measured while
 * the runtime is still settling is charged for that. This version therefore warms up ALL
 * variants before measuring any, and then interleaves: every round measures all four, and each
 * variant keeps its own best round. Drift that affects one round affects all four equally.
 *
 * These runs happen on an emulator, so absolute nanoseconds say nothing about real hardware.
 * The ratios between rows are the output worth reading.
 */
@RunWith(AndroidJUnit4::class)
class JniOverheadBenchmark {

    private companion object {
        const val TAG = "JniOverheadBenchmark"
        const val WARMUP = 400_000
        const val ITERS = 2_000_000
        const val ROUNDS = 7
    }

    /**
     * Whether it is safe to CALL the name-linked @CriticalNative variant at all.
     *
     * Measured, not assumed: on API 26 that call aborts the ART runtime outright --
     * "zygote64: runtime.cc:492] Runtime aborting..." -- and takes the instrumentation
     * process with it. This is exactly the failure Google's guidance implies when it says to
     * bind @CriticalNative through RegisterNatives before Android 12. Above 12 the call is
     * merely slow, not fatal.
     */
    private val nameLinkedIsSafe: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Kept live across the whole test so the JIT cannot fold any call away. */
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
    fun transitionCostByCallingConvention() {
        Log.i(TAG, "=== ${Build.MODEL} / API ${Build.VERSION.SDK_INT} / ${Build.SUPPORTED_ABIS[0]} ===")

        // Warm every variant before timing any of them.
        var w = 0L
        repeat(WARMUP) { i ->
            val x = i.toLong()
            w += kotlinEcho(x) + bindings.echo0(x) + bindings.echoFast(x) + bindings.echoNormal(x)
            if (nameLinkedIsSafe) w += bindings.echoCriticalNamed(x)
        }
        sink += w

        var bestKotlin = Double.MAX_VALUE
        var bestCritical = Double.MAX_VALUE
        var bestFast = Double.MAX_VALUE
        var bestNormal = Double.MAX_VALUE
        var bestCriticalNamed = Double.MAX_VALUE

        repeat(ROUNDS) {
            timeOnce { kotlinEcho(it) }.let { if (it < bestKotlin) bestKotlin = it }
            timeOnce { bindings.echo0(it) }.let { if (it < bestCritical) bestCritical = it }
            if (nameLinkedIsSafe) {
                timeOnce { bindings.echoCriticalNamed(it) }.let { if (it < bestCriticalNamed) bestCriticalNamed = it }
            }
            timeOnce { bindings.echoFast(it) }.let { if (it < bestFast) bestFast = it }
            timeOnce { bindings.echoNormal(it) }.let { if (it < bestNormal) bestNormal = it }
        }

        val critical = bestCritical - bestKotlin
        val criticalNamed = bestCriticalNamed - bestKotlin
        val fast = bestFast - bestKotlin
        val normal = bestNormal - bestKotlin

        Log.i(TAG, String.format("kotlin floor            %8.2f ns/call", bestKotlin))
        Log.i(TAG, String.format("@CriticalNative  (reg)  %8.2f ns/call   net %7.2f ns", bestCritical, critical))
        if (nameLinkedIsSafe) {
            Log.i(TAG, String.format("@CriticalNative  (name) %8.2f ns/call   net %7.2f ns", bestCriticalNamed, criticalNamed))
        } else {
            Log.i(TAG, "@CriticalNative  (name)   SKIPPED -- aborts the ART runtime below Android 12")
        }
        Log.i(TAG, String.format("@FastNative      (reg)  %8.2f ns/call   net %7.2f ns", bestFast, fast))
        Log.i(TAG, String.format("ordinary JNI     (reg)  %8.2f ns/call   net %7.2f ns", bestNormal, normal))
        Log.i(TAG, "sink=$sink")

        // This test originally asserted that @CriticalNative must not be slower than ordinary
        // JNI. Measurement disproved that on API 34 (critical ~24ns net, ordinary ~8ns), while
        // API 26 shows the opposite (critical ~2ns, ordinary ~45ns). The assumption was wrong,
        // so asserting it would only encode the wrong belief. See docs/downcall-design.md.
        //
        // What is still worth asserting is correctness: all three conventions must return the
        // value they were handed. If a registration silently stopped taking effect, arguments
        // would shift and these would return a pointer instead.
        val probe = 0x0123_4567_89ABL
        assertTrue("@CriticalNative echo returned ${bindings.echo0(probe)}", bindings.echo0(probe) == probe)
        assertTrue("@FastNative echo returned ${bindings.echoFast(probe)}", bindings.echoFast(probe) == probe)
        assertTrue("ordinary JNI echo returned ${bindings.echoNormal(probe)}", bindings.echoNormal(probe) == probe)
        if (nameLinkedIsSafe) {
            assertTrue("name-linked critical echo returned ${bindings.echoCriticalNamed(probe)}", bindings.echoCriticalNamed(probe) == probe)
        }
    }

    /**
     * Same comparison at a much smaller batch size.
     *
     * A @CriticalNative call blocks GC for its duration, so a tight loop of millions of them
     * can keep the collector waiting and have that delay charged back to the loop. If the
     * anomaly seen at 2,000,000 iterations is that effect, the per-call cost should fall
     * sharply here; if the cost is unchanged, the batch size is not the explanation.
     */
    @Test
    fun transitionCostAtSmallBatchSize() {
        val small = 20_000
        var s = 0L
        repeat(WARMUP) { i ->
            val x = i.toLong()
            s += kotlinEcho(x) + bindings.echo0(x) + bindings.echoFast(x) + bindings.echoNormal(x)
        }
        sink += s

        fun timeSmall(body: (Long) -> Long): Double {
            var best = Double.MAX_VALUE
            repeat(50) {
                var local = 0L
                val t0 = System.nanoTime()
                for (i in 0 until small) local += body(i.toLong())
                val ns = (System.nanoTime() - t0).toDouble() / small
                sink += local
                if (ns < best) best = ns
            }
            return best
        }

        val k = timeSmall { kotlinEcho(it) }
        val c = timeSmall { bindings.echo0(it) }
        val f = timeSmall { bindings.echoFast(it) }
        val n = timeSmall { bindings.echoNormal(it) }

        Log.i(TAG, "--- batch=$small (vs $ITERS above) ---")
        Log.i(TAG, String.format("kotlin floor     %8.2f ns/call", k))
        Log.i(TAG, String.format("@CriticalNative  %8.2f ns/call   net %7.2f ns", c, c - k))
        Log.i(TAG, String.format("@FastNative      %8.2f ns/call   net %7.2f ns", f, f - k))
        Log.i(TAG, String.format("ordinary JNI     %8.2f ns/call   net %7.2f ns", n, n - k))
        Log.i(TAG, "sink=$sink")
    }
}
