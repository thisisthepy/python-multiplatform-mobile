package python.multiplatform.ffi.upcall

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * One Kotlin coroutine started from a frame that had to return without it, and the place its
 * outcome waits until something asks for it.
 *
 * This is the half of `docs/upcall-async-design.md` that all three candidate conventions share.
 * The problem the doc states is structural: CPython calls a C function pointer, that C frame has
 * to hand back a `PyObject *` before it returns, and a suspension has nothing to hand back.
 * `runBlocking` "solves" it by re-seizing the very thread that would have been freed -- on Android
 * that thread is one CPython created, so blocking it stops the interpreter that is waiting on the
 * answer. What is left is to return *something else* and deliver the value later, and every way of
 * doing that begins here.
 *
 * ### What this deliberately does not do
 *
 * It does not deliver anything to Python. Whether the value reaches Python by polling, by calling
 * a Python callable, or by resolving an `asyncio.Future` is the part the design doc leaves open,
 * and building one of them before that is settled would be building the wrong one. [onCompleted]
 * is the single seam all three attach to.
 *
 * ### No `kotlinx.coroutines`
 *
 * `startCoroutine` and [Continuation] are `kotlin-stdlib`, common source set, every target. The
 * library has no coroutines dependency and must not grow one for this: the `suspend fun` being
 * exposed is the *user's*, and whatever dispatcher it uses is already on their classpath. Starting
 * it takes nothing more than the stdlib.
 *
 * ### [EmptyCoroutineContext], and the fast path that falls out of it
 *
 * With no dispatcher, the body runs on the calling thread until it actually suspends. So a
 * `suspend fun` that never reaches a real suspension point -- which is most of them, most of the
 * time -- is **already complete when [start] returns**, and the boundary can hand Python the real
 * value with no async convention involved at all. `PendingCallTest` pins that.
 *
 * The converse is the cost: the body's first slice runs inside the C frame, holding the GIL, so an
 * exposed `suspend fun` that does its blocking work *before* its first suspension point blocks the
 * interpreter exactly as a non-suspending one would. `suspend` is a signature, not a promise.
 *
 * ### Threading
 *
 * Not synchronised, and it does not need to be **provided the rule the rest of the boundary
 * already lives under is kept**: every mutation happens on a thread holding the GIL
 * (`commonMain/README.md`, and [python.multiplatform.reflection.HandleTable]'s own threading
 * note). That is not an extra burden here -- a completion that wants to reach Python has to take
 * the GIL to do anything at all, so the scope it needs for that already covers this. What is *not*
 * safe is resuming an exposed call's continuation without one and expecting the fields below to be
 * visible to the next upcall; the GIL is the barrier.
 *
 * Nothing here allocates a handle. A `PendingCall` is an ordinary Kotlin object, so
 * [python.multiplatform.reflection.HandleTable] already gives it a generational handle and
 * [UpcallTrampoline.releaseObject] already gives Python the way to drop it.
 */
class PendingCall private constructor() {

    /** True once the coroutine has finished, whether with a value or with a [failure]. */
    var isDone: Boolean = false
        private set

    /** The result, or `null` -- which is also a legitimate result. Read [isDone] first. */
    var value: Any? = null
        private set

    /** What the coroutine threw, or `null`. Never rethrown from here; see [start]. */
    var failure: Throwable? = null
        private set

    /**
     * What the [onCompleted] listener threw, if it threw.
     *
     * Kept rather than propagated for the same reason [failure] is: the listener runs on the
     * completing thread, which on the delivery path has C above it, and a Kotlin exception
     * crossing that frame terminates the process. A convention that cares can read this.
     */
    var deliveryFailure: Throwable? = null
        private set

    private var listener: ((PendingCall) -> Unit)? = null

    /**
     * Registers the one thing that gets told when this completes.
     *
     * Fires inline if the call is already done, which is the common case for a body that never
     * suspended -- so a caller does not have to branch on [isDone] before attaching.
     *
     * @throws IllegalStateException if a listener is already waiting. A pending call belongs to
     *   exactly one Python-side waiter (one `Future`, one callback); silently replacing the first
     *   would strand it forever.
     */
    fun onCompleted(action: (PendingCall) -> Unit) {
        if (isDone) {
            deliver(action)
            return
        }
        check(listener == null) { "this PendingCall already has a completion listener" }
        listener = action
    }

    private fun complete(result: Result<Any?>) {
        // A Continuation cannot legitimately be resumed twice, but a second completion must not
        // overwrite the first one's outcome if a generator ever gets it wrong.
        if (isDone) return
        result.fold({ value = it }, { failure = it })
        isDone = true
        val waiting = listener
        listener = null
        if (waiting != null) deliver(waiting)
    }

    private fun deliver(action: (PendingCall) -> Unit) {
        try {
            action(this)
        } catch (t: Throwable) {
            deliveryFailure = t
        }
    }

    override fun toString(): String = when {
        !isDone -> "PendingCall(pending)"
        failure != null -> "PendingCall(failed: ${failure?.message})"
        else -> "PendingCall(done: $value)"
    }

    companion object {

        /**
         * Starts [block] and returns immediately, complete or not.
         *
         * Nothing is thrown out of here. [block]'s own failure is parked in [failure] the same way
         * its value would be parked in [value], because the caller is a C frame with no unwinding
         * above it -- the same rule [UpcallTrampoline] states for itself.
         */
        fun start(block: suspend () -> Any?): PendingCall {
            val call = PendingCall()
            val completion = Continuation<Any?>(EmptyCoroutineContext) { result -> call.complete(result) }
            try {
                block.startCoroutine(completion)
            } catch (t: Throwable) {
                // startCoroutine routes the body's exceptions to `completion`, so reaching here
                // means the machinery itself failed. `complete` is a no-op if it already ran.
                call.complete(Result.failure(t))
            }
            return call
        }
    }
}
