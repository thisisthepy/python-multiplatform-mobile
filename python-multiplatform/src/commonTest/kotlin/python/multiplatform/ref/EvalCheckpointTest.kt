package python.multiplatform.ref

import python.multiplatform.BuildConfig
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.ReleaseCounter
import python.multiplatform.ffi.withGIL
import python.native.ffi.PyGC_Collect
import python.native.ffi.Py_MakePendingCalls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * What an embedder owes CPython that a script never has to think about (ROADMAP §9).
 *
 * CPython defers work to a checkpoint that only the evaluation loop reaches — `_Py_HandlePending`,
 * called from the `_CHECK_PERIODIC` uop that opens every Python-level frame. Nothing in the C API
 * reaches it on its own. A program that drives the interpreter entirely through `PyObject_Call`
 * and friends therefore never merges the free-threaded build's biased reference-counting queue,
 * never processes QSBR-deferred frees and never runs a scheduled cyclic collection.
 *
 * The consequence measured in §9 is that references handed back by the cleaner *stay* handed back
 * and never freed: the counts are correct, the memory is not returned. These tests pin the shape
 * of that, the fact that the obvious Stable ABI candidate does not fix it, and the cost of the one
 * that does.
 */
class EvalCheckpointTest {

    private companion object {
        const val OUTER = 1000
    }

    private fun refCount(target: PyObject): Long {
        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        try {
            val n = getrefcount(target)
            try {
                return n.toString().toLong()
            } finally {
                n.close()
            }
        } finally {
            getrefcount.close()
            sys.close()
        }
    }

    /**
     * Drives the §9 scenario by hand, with automatic checkpoints switched off, so that each step
     * is attributable: the cleaner runs, the count does not move, `Py_MakePendingCalls` does not
     * move it either, and [Python3.drainPendingReleases] does.
     *
     * The free-threaded and default builds assert different things because they *are* different:
     * with the global lock there is no deferred-release queue at all and the cleaner's `Py_DecRef`
     * frees on the spot, which is its own invariant worth holding on to.
     */
    @Test
    fun testDrainPendingReleasesReclaimsWhatTheCleanerGaveBack(): CollectorTestResult {
        // No subject on a platform without a cleaner: nothing releases the wrappers, so the
        // question this test asks -- what the checkpoint does with what the cleaner gave back --
        // has nothing to act on. GCLeakTest fails there and names the real cause.
        if (!cleanerReleasesAutomatically) return collectorTest(0, { true }, { })
        if (!Python3.isInitialized) Python3.initialize()

        val previousInterval = Python3.autoDrainInterval
        Python3.autoDrainInterval = 0
        try {
            val builtins = Python3.import("builtins")
            val listType = builtins.getAttr("list")
            val target = listType()

            val refBefore = refCount(target)

            // Each outer list holds one reference to `target`. The wrappers are held until the
            // count has been sampled so that the build-up is deterministic; see the note on
            // GCLeakTest.testCascadingReleaseOnGC for why letting them die inside the loop races
            // the very mechanism under test.
            var held: MutableList<PyObject>? = ArrayList(OUTER)
            repeat(OUTER) {
                val outer = listType()
                val append = outer.getAttr("append")
                append(target)
                append.close()
                held!!.add(outer)
            }

            val refAfterBuild = refCount(target)
            assertTrue(
                refAfterBuild >= refBefore + OUTER,
                "the outer lists should have raised the target's count by $OUTER " +
                    "(before: $refBefore, after: $refAfterBuild)"
            )

            // Cost of the probe itself, in releases, so the wait loop below can tell the cleaner's
            // work apart from its own.
            val probeMark = ReleaseCounter.released
            refCount(target)
            val releasesPerProbe = ReleaseCounter.released - probeMark

            held!!.clear()
            held = null

            val releasedBefore = ReleaseCounter.released
            var attempts = 0
            var cleanerReleases = 0

            // Driven rather than spun. On wasmJs the cleaner is a host finalisation callback that
            // arrives on a task, so a `while` loop here would ask 200 times inside one job and see
            // nothing; see `commonTest`'s CollectorTestResult.
            return collectorTest(maxAttempts = 200, attempt = {
                refCount(target)
                attempts++
                cleanerReleases = ReleaseCounter.released - releasedBefore - attempts * releasesPerProbe
                cleanerReleases >= OUTER
            }, finish = {
                try {
                    assertTrue(
                        cleanerReleases >= OUTER,
                        "the cleaner has to have released the $OUTER outer wrappers before this test can " +
                            "say anything about what happened to the memory " +
                            "(released by the cleaner: $cleanerReleases, attempts: $attempts). " +
                            "If this fires, the failure is in GC-driven release, not in checkpointing."
                    )

                    val refAfterCleaner = refCount(target)

                    if (BuildConfig.pythonFreeThreaded) {
                        assertEquals(
                            refAfterBuild,
                            refAfterCleaner,
                            "free-threaded: a Py_DecRef from a thread that does not own the object is " +
                                "queued to its owner rather than run, so $OUTER completed decrefs must " +
                                "leave the count exactly where it was. If this now differs, the runtime " +
                                "has started merging somewhere else and the rest of this test is stale."
                        )

                        // Py_MakePendingCalls is the Stable ABI function that looks like it should do
                        // this. It does not: it handles _PY_CALLS_TO_DO_BIT and _PY_SIGNALS_PENDING_BIT
                        // and returns, while the merge hangs off _PY_EVAL_EXPLICIT_MERGE_BIT, which only
                        // _Py_HandlePending clears.
                        val pendingResult = withGIL { Py_MakePendingCalls() }
                        assertEquals(0, pendingResult, "Py_MakePendingCalls should not have failed")
                        assertEquals(
                            refAfterBuild,
                            refCount(target),
                            "Py_MakePendingCalls must not be mistaken for a checkpoint: it does not merge " +
                                "the biased reference-counting queue"
                        )

                        Python3.drainPendingReleases()
                        val refAfterDrain = refCount(target)
                        assertTrue(
                            refAfterDrain < refAfterBuild,
                            "reaching an eval-loop checkpoint should have merged all $OUTER queued " +
                                "releases (before: $refAfterBuild, after: $refAfterDrain)"
                        )
                    } else {
                        assertTrue(
                            refAfterCleaner < refAfterBuild,
                            "with the global lock there is no deferred-release queue, so the cleaner's " +
                                "Py_DecRef should already have freed the outer lists " +
                                "(before: $refAfterBuild, after: $refAfterCleaner)"
                        )
                    }

                    target.close()
                    listType.close()
                    builtins.close()
                } finally {
                    Python3.autoDrainInterval = previousInterval
                }
            })
        } catch (t: Throwable) {
            // Only reached if the *setup* above threw; the happy path restores the interval in
            // `finish`, which runs after the collector has been given its chances.
            Python3.autoDrainInterval = previousInterval
            throw t
        }
    }

    /**
     * A checkpoint must be cheap enough to ride on ordinary calls, which is the whole reason it is
     * a cached function object and not `exec("pass")` — the latter recompiles a module every time.
     *
     * The assertion is the ordering, not an absolute number: absolute nanoseconds are a property
     * of the machine, but "a cached call beats recompiling" is a property of the design and would
     * be violated the moment the checkpoint started compiling, importing or allocating.
     */
    @Test
    fun testCheckpointCostAgainstTheAlternatives() {
        if (!Python3.isInitialized) Python3.initialize()

        val previousInterval = Python3.autoDrainInterval
        Python3.autoDrainInterval = 0
        try {
            // Build the cached callable before timing anything.
            Python3.drainPendingReleases()

            val emptyScopeNs = timeNs(20_000) { withGIL { } }
            val drainNs = timeNs(20_000) { Python3.drainPendingReleases() }
            val execNs = timeNs(2_000) { Python3.exec("pass") }
            val makePendingNs = timeNs(20_000) { withGIL { Py_MakePendingCalls() } }
            val gcCollectNs = timeNs(100) { withGIL { PyGC_Collect() } }

            println(
                "\n--- eval-loop checkpoint cost (freeThreaded=${BuildConfig.pythonFreeThreaded}) ---\n" +
                    "withGIL { }                     ${emptyScopeNs.format()} ns/op\n" +
                    "Python3.drainPendingReleases()  ${drainNs.format()} ns/op\n" +
                    "withGIL { Py_MakePendingCalls } ${makePendingNs.format()} ns/op   (does NOT merge)\n" +
                    "Python3.exec(\"pass\")            ${execNs.format()} ns/op\n" +
                    "withGIL { PyGC_Collect() }      ${gcCollectNs.format()} ns/op   (merges every thread)\n"
            )

            assertTrue(
                drainNs < execNs,
                "a cached, already-compiled checkpoint should cost less than recompiling a module " +
                    "(drain: ${drainNs.format()} ns, exec(\"pass\"): ${execNs.format()} ns)"
            )
            assertTrue(
                drainNs < gcCollectNs,
                "a checkpoint should cost less than a full cyclic collection " +
                    "(drain: ${drainNs.format()} ns, PyGC_Collect: ${gcCollectNs.format()} ns)"
            )
        } finally {
            Python3.autoDrainInterval = previousInterval
        }
    }

    /**
     * The automatic path must not fire from a cleaner. It is suppressed there for two reasons —
     * the queue that needs merging belongs to the owning thread, and running Python on a cleaner is
     * what ROADMAP §1 turned into a deadlock — and neither is visible from the outside, so it is
     * pinned here by counting checkpoints while only cleaners are running.
     */
    @Test
    fun testCleanerActivityAloneTakesNoCheckpoint(): CollectorTestResult {
        // No subject on a platform without a cleaner: nothing releases the wrappers, so the
        // question this test asks -- what the checkpoint does with what the cleaner gave back --
        // has nothing to act on. GCLeakTest fails there and names the real cause.
        if (!cleanerReleasesAutomatically) return collectorTest(0, { true }, { })
        if (!Python3.isInitialized) Python3.initialize()

        val previousInterval = Python3.autoDrainInterval
        // Force the automatic path fully on: every outermost scope is a candidate.
        Python3.autoDrainInterval = 1
        try {
            val builtins = Python3.import("builtins")
            val listType = builtins.getAttr("list")
            val target = listType()

            // Drop 500 wrappers on the floor for the cleaner to pick up, then let it work without
            // this thread entering withGIL at all.
            repeat(500) { PyObject(target.pointer, borrowed = true) }

            val ranBefore = ReleaseCounter.ran
            val checkpointsBefore = Python3.CheckpointCounter.reached
            var attempts = 0

            return collectorTest(maxAttempts = 200, attempt = {
                attempts++
                ReleaseCounter.ran - ranBefore >= 100
            }, finish = {
                try {
                    val cleanerRuns = ReleaseCounter.ran - ranBefore
                    val checkpointsDuring = Python3.CheckpointCounter.reached - checkpointsBefore

                    assertTrue(
                        cleanerRuns > 0,
                        "no cleaner ran, so this test proved nothing (attempts: $attempts). " +
                            "That is the GCLeakTest.testCleanerMechanismRuns failure, not this one."
                    )
                    assertEquals(
                        0L,
                        checkpointsDuring,
                        "cleaner threads released $cleanerRuns references and must not have taken a " +
                            "single checkpoint while doing it (took: $checkpointsDuring)"
                    )

                    // And the same activity, seen from a thread that does enter Python, does take one.
                    val checkpointsBeforeUse = Python3.CheckpointCounter.reached
                    repeat(4) { withGIL { } }
                    assertTrue(
                        Python3.CheckpointCounter.reached > checkpointsBeforeUse,
                        "an ordinary caller entering withGIL after the cleaner has released something " +
                            "should take the checkpoint the cleaner declined"
                    )

                    target.close()
                    listType.close()
                    builtins.close()
                } finally {
                    Python3.autoDrainInterval = previousInterval
                }
            })
        } catch (t: Throwable) {
            Python3.autoDrainInterval = previousInterval
            throw t
        }
    }

    private fun timeNs(iterations: Int, block: () -> Unit): Double {
        repeat(iterations / 10 + 1) { block() }
        val elapsed = measureTime { repeat(iterations) { block() } }
        return elapsed.inWholeNanoseconds.toDouble() / iterations
    }

    private fun Double.format(): String {
        val scaled = (this * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}
