package python.multiplatform.ref

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
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

class GCLeakTest {
    @Test
    fun testReferenceCountDecreasesOnGC() {
        if (!Python3.isInitialized) {
            Python3.initialize()
        }
        
        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val testList = listType() // creates a new list
        
        // Built inside a separate function on purpose. Assigning null to a local does not
        // reliably make an object unreachable -- the frame's slot can still hold it until the
        // method returns, which is enough to keep the cleaner from ever firing and made this
        // test report "no decrease" even with the fix in place. Once makeWrappers() returns,
        // nothing refers to them.
        val refBefore = getrefcount(testList).toString().toLong()
        makeWrappers(testList, WRAPPERS)
        val refAfterLoop = getrefcount(testList).toString().toLong()
        assertTrue(
            refAfterLoop > refBefore,
            "wrapping should have raised the count (before: $refBefore, after wrapping: $refAfterLoop)"
        )
        
        // Force GC and wait for cleaner to run
        var refAfterGC = refAfterLoop
        var attempts = 0
        while (refAfterGC == refAfterLoop && attempts < 50) {
            forceGC()
            refAfterGC = getrefcount(testList).toString().toLong()
            attempts++
        }
        
        assertTrue(
            refAfterGC < refAfterLoop,
            "Reference count should decrease after GC " +
                "(before: $refAfterLoop, after: $refAfterGC, " +
                "cleanup actions started: ${python.multiplatform.ffi.ReleaseCounter.ran}, " +
                "reached Py_DecRef: ${python.multiplatform.ffi.ReleaseCounter.released}). " +
                "started>0 with reached==0 means the cleaner thread is blocked in PyGILState_Ensure " +
                "because Python3.initialize() still holds the GIL -- see the note there."
        )
        
        testList.close()
        getrefcount.close()
        sys.close()
        builtins.close()
        listType.close()
    }
}
