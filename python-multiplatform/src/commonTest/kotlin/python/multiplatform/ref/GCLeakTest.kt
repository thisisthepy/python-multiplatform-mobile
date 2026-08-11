package python.multiplatform.ref

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.ReleaseCounter
import kotlin.test.Test
import kotlin.test.assertTrue

expect fun forceGC()

private const val WRAPPERS = 200

/**
 * Creates [count] borrowed wrappers over [target] and returns without keeping any of them.
 * Each one takes a reference in its constructor, so the count rises by [count]; every wrapper
 * is unreachable the moment this returns, so the cleaner should give all of them back.
 */
private fun makeWrappers(target: PyObject, count: Int) {
    repeat(count) { PyObject(target.pointer, borrowed = true) }
}

private fun getRefCount(target: PyObject): Long {
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

class GCLeakTest {
    
    @Test
    fun testCleanerMechanismRuns() {
        if (!Python3.isInitialized) Python3.initialize()
        
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val testList = listType()
        
        val ranBefore = ReleaseCounter.ran
        val releasedBefore = ReleaseCounter.released
        
        makeWrappers(testList, WRAPPERS)
        
        var ranAfter = ReleaseCounter.ran
        var releasedAfter = ReleaseCounter.released
        var attempts = 0
        
        // Loop to wait for background cleaners.
        while (releasedAfter == releasedBefore && attempts < 50) {
            forceGC()
            ranAfter = ReleaseCounter.ran
            releasedAfter = ReleaseCounter.released
            attempts++
        }
        
        val ranDelta = ranAfter - ranBefore
        val releasedDelta = releasedAfter - releasedBefore
        
        // Deltas, not absolutes. ReleaseCounter is process-wide, so `ran > released` can be true
        // from residue left by earlier tests -- which is precisely the mistake that hid this bug
        // for so long, when a global 101002 was read as "the cleaners for this test ran".
        if (ranDelta > releasedDelta) {
            assertTrue(
                false,
                "Cleaner started but never reached Py_DecRef. (ranDelta: $ranDelta, releasedDelta: $releasedDelta; " +
                "absolute ran: $ranAfter, released: $releasedAfter). " +
                "This implies the cleaner thread is deadlocked in PyGILState_Ensure because Python3.initialize() still holds the GIL (ROADMAP §1)."
            )
        } else {
            assertTrue(
                ranDelta > 0,
                "No cleaner ran for this test's wrappers. (ranBefore: $ranBefore, ranAfter: $ranAfter, " +
                "attempts: $attempts). " +
                "Note this fires on desktop as well as on iOS, so do not read it as an iOS-only symptom. " +
                "Two causes are consistent with it and this test cannot yet tell them apart: the collector " +
                "never considered the wrappers unreachable, or it did and the cleaner runs on a background " +
                "thread that the tight forceGC() loop -- which finishes in microseconds -- gave no chance to " +
                "wake up. " +
                "What it is NOT is the §1 GIL deadlock: that shows up as ranDelta > releasedDelta, handled above. " +
                "The large absolute counts here are the whole process's explicit close() calls from earlier " +
                "tests, which is why only the deltas are trusted."
            )
        }
        
        testList.close()
        listType.close()
        builtins.close()
    }

    @Test
    fun testReferenceCountDecreasesOnGC() {
        if (!Python3.isInitialized) Python3.initialize()
        
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val testList = listType()
        
        val refBefore = getRefCount(testList)
        makeWrappers(testList, WRAPPERS)
        val refAfterLoop = getRefCount(testList)
        
        assertTrue(
            refAfterLoop > refBefore,
            "wrapping should have raised the count (before: $refBefore, after wrapping: $refAfterLoop)"
        )
        
        // getRefCount closes wrappers of its own on every call, and those closes go through the
        // same counter as the cleaner's. Measure that cost once rather than hardcoding it, so the
        // arithmetic below cannot drift silently when the helper changes.
        val probeBefore = ReleaseCounter.released
        getRefCount(testList)
        val closesPerProbe = ReleaseCounter.released - probeBefore

        val releasedBefore = ReleaseCounter.released

        var refAfterGC = refAfterLoop
        var releasedAfter = ReleaseCounter.released
        var attempts = 0
        while (refAfterGC == refAfterLoop && attempts < 50) {
            forceGC()
            refAfterGC = getRefCount(testList)
            releasedAfter = ReleaseCounter.released
            attempts++
        }

        // Subtract the helper's own closes, leaving only what the cleaner contributed.
        val expectedLoopCloses = attempts * closesPerProbe
        val releasedDelta = releasedAfter - releasedBefore - expectedLoopCloses
        
        if (releasedDelta <= 0) {
            assertTrue(
                false,
                "Cannot verify if target's count drops because no GC decrefs actually completed for the target wrappers. " +
                "Either the cleaner thread is deadlocked (Desktop) or the tight loop finished before background cleaners could run (iOS)."
            )
        }
        
        assertTrue(
            refAfterGC < refAfterLoop,
            "Given that a decref ran for the wrappers (releasedDelta: $releasedDelta), the target's count should actually drop. " +
            "(before GC: $refAfterLoop, after GC: $refAfterGC)"
        )
        
        testList.close()
        listType.close()
        builtins.close()
    }
}
