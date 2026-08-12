package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.basic.PyString
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.TypeTag
import python.native.ffi.Py_IncRef
import python.native.ffi.toNativePointer
import python.native.ffi.toRawValue

/**
 * The delivery half of `docs/upcall-async-design.md`: how the outcome of a Kotlin coroutine that
 * could not finish inside the C frame reaches the Python code waiting for it.
 *
 * [PendingCall] is the half that starts the coroutine and parks its outcome; this is the half that
 * attaches to its `onCompleted` seam. The design doc picks candidate **(C)** -- `asyncio` -- as the
 * surface, implemented by candidate **(B)** -- a downcall from the completing thread -- because
 * `loop.call_soon_threadsafe` *is* a downcall. Nothing here needs a binding that was not already in
 * `EmbedAPI`; `AsyncCompletionProbeTest` measured that before any of this was written.
 *
 * ### Two returns, and the fast one is the common one
 *
 * `suspend` is a signature, not a promise to suspend. A body that never reaches a suspension point
 * is already complete when `PendingCall.start` returns (measured:
 * `PendingCallTest.aBlockThatNeverSuspendsIsAlreadyCompleteBeforeStartReturns`), and that call gets
 * the real value marshalled through the ordinary path -- **no `Future`, no event loop, no
 * `asyncio` import**. Only a call that actually suspended pays for one.
 *
 * The cost of that choice is that the boundary returns two different Python types for one Kotlin
 * declaration, so the Python-side proxy has to be an `async def` that awaits its result only when
 * the result is awaitable. That is what makes `await kotlin_fn(x)` uniform to the caller while
 * leaving the fast path free.
 *
 * ### What requires the application to be async
 *
 * The slow path needs a **running** loop on the calling thread, because the upcall arrives from
 * inside a coroutine and `asyncio.get_running_loop()` is what names the loop that coroutine is on.
 * With nothing running there is nowhere to deliver to, and this fails loudly rather than handing
 * back something that would never resolve. The coroutine has already been started by then -- it had
 * to be, since whether it suspends is only knowable after starting it -- so its eventual resumption
 * is stranded. That is a real cost of candidate (C) and it is not hidden.
 *
 * ### Reference conventions
 *
 * - The `Future` handed to Python is a **new reference**, like every other trampoline result: the
 *   Kotlin wrapper keeps the one `create_future` gave it and Python gets an increment of its own.
 * - The wrapper stays reachable because the completion lambda captures it, the lambda is held by
 *   the [PendingCall], and the [PendingCall] is held by whatever holds the suspended continuation.
 *   When the completion fires, [PendingCall] drops the listener and both wrappers become
 *   collectable, at which point their cleaners return the references.
 * - Nothing here holds a *borrowed* pointer across a GIL release.
 *
 * ### Threading
 *
 * The completion arrives on whatever thread resumed the coroutine, which is generally **not** the
 * thread that took the upcall, so it takes its own GIL scope before touching anything
 * (`commonMain/README.md`: "Every C API call needs the GIL"). [withGIL] is the right scope here
 * rather than [UpcallTrampoline]'s unconditional `PyGILState_Ensure`: this is not an entry point
 * from C, so the thread's nesting depth is its own honest record. A completer thread CPython has
 * never seen has depth 0 and takes a real `PyGILState_Ensure`; a completion delivered from inside a
 * later upcall runs under the depth that upcall's `attached` already set, and correctly skips.
 *
 * Nothing is thrown out of the completion. [PendingCall.deliver] catches into
 * [PendingCall.deliveryFailure], which matters because on the fast-completing path the listener can
 * run inside the C frame with nothing above it to unwind into.
 */
internal object AsyncUpcall {

    /**
     * Turns what a suspending entry's generated body returned into the `PyObject *` the C frame has
     * to hand back.
     *
     * @param entry the table entry, read for [ExposedCallable.returnType] -- the *declared* return
     *   type, which is what both the fast path and `set_result` marshal with.
     * @param result must be the [PendingCall] the generated body produced.
     * @return a **new** reference: either the marshalled value or an `asyncio.Future`.
     * @throws Throwable whatever the coroutine failed with, if it failed before suspending. The
     *   caller ([UpcallTrampoline.invoke]) is already inside the `catch` that turns that into
     *   Python's error indicator, so a fast-path failure raises at the call site exactly as a
     *   synchronous one does.
     */
    fun deliver(entry: ExposedCallable, result: Any?): Long {
        val call = result as? PendingCall
            ?: throw IllegalStateException(
                "${entry.name} is marked suspending but its body returned ${result ?: "null"} " +
                    "instead of a PendingCall"
            )

        // The fast path, checked before anything imports asyncio: no loop is consulted, so an
        // exposed `suspend fun` that never suspends works in a program with no event loop at all.
        if (call.isDone) {
            call.failure?.let { throw it }
            return UpcallTrampoline.marshalResult(entry.returnType, call.value)
        }

        val loop = runningLoop()
        val future = loop.getAttr("create_future").invoke()
        val tag = entry.returnType
        // Registering after the future exists: a completion that lands between the isDone check
        // above and this line fires the listener inline, on this thread, with the GIL already held
        // -- which is exactly what the listener is written to tolerate.
        call.onCompleted { completed -> resolve(loop, future, tag, completed) }

        // The trampoline's contract: the result is a new reference. The wrapper keeps its own,
        // because the completion still needs it.
        Py_IncRef(future.pointer)
        return future.pointer.toRawValue()
    }

    /**
     * `asyncio.get_running_loop()` -- the loop belonging to the coroutine this upcall came out of.
     *
     * Not `get_event_loop()`: that one will happily create or return a loop nobody is running,
     * which would queue the completion somewhere no one ever drains. Not cached either, because the
     * module object would outlive an interpreter that gets finalized and this is the slow path by
     * construction -- a call that genuinely suspended is not counting attribute lookups.
     */
    private fun runningLoop(): PyObject =
        Python3.import("asyncio").getAttr("get_running_loop").invoke()

    /**
     * Hands one completed [call]'s outcome to [future], from whichever thread finished it.
     *
     * `call_soon_threadsafe` rather than `set_result` directly: a `Future` may only be resolved
     * from its own loop's thread, and this is the documented hand-off. It is also the only part of
     * candidate (C) that is not simply candidate (B) -- and it is itself a downcall, which is the
     * design doc's point.
     */
    private fun resolve(loop: PyObject, future: PyObject, tag: TypeTag, call: PendingCall) {
        withGIL {
            // A loop that has been closed cannot be handed anything; `call_soon_threadsafe` would
            // raise, and that exception has nowhere useful to go from here. Checking is not a
            // guarantee -- the loop can close between here and the call -- so the raise is still
            // possible, and PendingCall.deliver parks it in `deliveryFailure`.
            if (loop.getAttr("is_closed").invoke().toString() == "True") return@withGIL

            val callSoonThreadsafe = loop.getAttr("call_soon_threadsafe")
            val failure = call.failure
            if (failure != null) {
                callSoonThreadsafe.invoke(future.getAttr("set_exception"), pythonExceptionFor(failure))
                return@withGIL
            }

            // Marshalling can fail on its own (a tag the value does not match, an allocation that
            // did not come back). Turning that into a rejection rather than letting it escape is
            // what keeps the awaiting coroutine from hanging forever on a Future nobody resolved.
            val marshalled = try {
                val raw = UpcallTrampoline.marshalResult(tag, call.value)
                PyObject(raw.toNativePointer() ?: error("marshalling ${call.value} produced NULL"), borrowed = false)
            } catch (t: Throwable) {
                callSoonThreadsafe.invoke(future.getAttr("set_exception"), pythonExceptionFor(t))
                return@withGIL
            }
            callSoonThreadsafe.invoke(future.getAttr("set_result"), marshalled)
        }
    }

    /**
     * The Python exception instance `set_exception` needs.
     *
     * A [PyException] carries the original Python exception it was built from, so a Python error
     * that crossed into Kotlin and back out again reaches the awaiting coroutine as *itself*,
     * traceback and type intact, rather than as a `RuntimeError` wrapping its message. Anything
     * else becomes a `RuntimeError` with the Kotlin message, which is the same mapping
     * [UpcallTrampoline]'s synchronous failure path uses.
     */
    private fun pythonExceptionFor(failure: Throwable): PyObject {
        if (failure is PyException) failure.value?.let { return it }
        val message = failure.message ?: failure::class.simpleName ?: "the exposed suspending function failed"
        return UpcallTrampoline.runtimeErrorClass().invoke(PyString.from(message))
    }
}
