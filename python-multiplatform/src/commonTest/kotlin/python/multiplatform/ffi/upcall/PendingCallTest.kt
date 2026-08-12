package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The half of `docs/upcall-async-design.md` that every candidate shares: starting a Kotlin
 * coroutine from a frame that must return synchronously, and parking its outcome somewhere the
 * boundary can address later.
 *
 * Nothing here touches CPython, which is the point -- this is pure Kotlin and therefore the same
 * on all five targets, so it belongs in `commonTest` rather than in a platform suite. What is
 * *not* here is the delivery half (polling, a Python callback, or an `asyncio.Future`); that is
 * the part the design doc leaves unbuilt, and `AsyncCompletionProbeTest` in `desktopTest` is the
 * measurement standing in for it.
 *
 * The suspension points are `kotlin.coroutines.suspendCoroutine` and the continuations are
 * resumed by hand. That is deliberate: it needs no `kotlinx.coroutines` dependency (the library
 * has none, and `PendingCall` must not introduce one), and it makes "suspended" and "resumed"
 * two separate lines of the test rather than a scheduling race.
 */
class PendingCallTest {

    @AfterTest
    fun cleanup() {
        HandleTable.releaseAll()
    }

    // -------------------------------------------------------------------- the synchronous case

    @Test
    fun aBlockThatNeverSuspendsIsAlreadyCompleteBeforeStartReturns() {
        // The optimisation the whole design turns on: `suspend` is a signature, not a promise to
        // suspend. A suspending function that only awaits things that are already available runs
        // to completion on the calling thread, so the C frame that started it can return the real
        // value and no async convention is needed for that call at all.
        val call = PendingCall.start { 42L }

        assertTrue(call.isDone)
        assertEquals(42L, call.value)
        assertNull(call.failure)
    }

    @Test
    fun aBlockThatThrowsParksTheThrowableInsteadOfUnwindingOutOfStart() {
        // `start` is reached from C. A Kotlin exception crossing that frame terminates the VM on
        // the JVM and the process on Kotlin/Native (`UpcallTrampoline`'s class doc), so the one
        // thing this must never do is rethrow.
        val call = PendingCall.start { throw IllegalStateException("boom") }

        assertTrue(call.isDone)
        assertNull(call.value)
        assertEquals("boom", call.failure?.message)
    }

    // ------------------------------------------------------------------------ the suspended case

    @Test
    fun aBlockThatSuspendsStaysPendingUntilItsContinuationIsResumed() {
        var captured: Continuation<Long>? = null
        val call = PendingCall.start { suspendCoroutine { c -> captured = c } }

        assertFalse(call.isDone, "start must return while the work is still outstanding")
        assertNull(call.value)

        captured!!.resume(7L)

        assertTrue(call.isDone)
        assertEquals(7L, call.value)
    }

    @Test
    fun aFailureAfterSuspensionIsParkedTheSameWayASynchronousOneIs() {
        var captured: Continuation<Long>? = null
        val call = PendingCall.start { suspendCoroutine { c -> captured = c } }

        captured!!.resumeWithException(IllegalArgumentException("late"))

        assertTrue(call.isDone)
        assertEquals("late", call.failure?.message)
    }

    // ------------------------------------------------------------------------------- delivery

    @Test
    fun aListenerRegisteredBeforeCompletionFiresOnceWithTheCall() {
        var captured: Continuation<Long>? = null
        val call = PendingCall.start { suspendCoroutine { c -> captured = c } }

        val seen = mutableListOf<PendingCall>()
        call.onCompleted { seen += it }
        assertEquals(0, seen.size, "nothing has completed yet")

        captured!!.resume(1L)
        assertEquals(listOf(call), seen)

        // A second resume is not possible through a Continuation, but a second *delivery* must be,
        // because the convention that attaches the listener has to be able to reason about it.
        call.onCompleted { seen += it }
        assertEquals(2, seen.size, "a listener attached after completion fires immediately")
    }

    @Test
    fun aListenerAttachedToAnAlreadyCompletedCallFiresInline() {
        val call = PendingCall.start { "done" }
        var seen: PendingCall? = null
        call.onCompleted { seen = it }
        assertSame(call, seen)
    }

    @Test
    fun onlyOneListenerIsHeldSoTheConventionCannotLeakThemByAccident() {
        // A pending call belongs to exactly one Python-side waiter -- one Future, one callback.
        // Allowing a second registration would silently drop it, so it is an error instead.
        var captured: Continuation<Long>? = null
        val call = PendingCall.start { suspendCoroutine { c -> captured = c } }

        call.onCompleted { }
        assertFailsWith<IllegalStateException> { call.onCompleted { } }

        captured!!.resume(0L)
    }

    @Test
    fun aThrowingListenerDoesNotCorruptTheCallOrEscapeTheCompletion() {
        // The listener runs on whatever thread resumed the coroutine, which on the delivery path
        // is inside a GIL scope with C above it. It is the same no-unwinding rule as `start`.
        var captured: Continuation<Long>? = null
        val call = PendingCall.start { suspendCoroutine { c -> captured = c } }
        call.onCompleted { throw IllegalStateException("listener blew up") }

        captured!!.resume(5L)

        assertTrue(call.isDone)
        assertEquals(5L, call.value)
        assertEquals("listener blew up", call.deliveryFailure?.message)
    }

    // ------------------------------------------------------------- addressing it from Python

    @Test
    fun aPendingCallIsAddressedByAnOrdinaryObjectHandleAndGoesStaleOnRelease() {
        // No new handle machinery: a PendingCall is a Kotlin object like any other, so
        // `HandleTable` already gives it a generational handle and `UpcallTrampoline.releaseObject`
        // already gives Python the way to drop it. This is the reason `start` returns the object
        // rather than a `Long` of its own kind.
        val call = PendingCall.start { 1L }
        val ref = HandleTable.register(call)

        assertSame(call, HandleTable.resolve(ref))
        assertTrue(HandleTable.release(ref))
        assertNull(HandleTable.resolve(ref), "a released handle must not resolve to the next occupant")
        assertFalse(HandleTable.release(ref), "a double release is a no-op")
        assertFalse(ObjectReference.NONE.isValid)
    }
}
