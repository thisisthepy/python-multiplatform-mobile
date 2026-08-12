package fixture.library

/**
 * Every shape a `suspend` function can take in a scanned module, so that what the generator does
 * with it is a *measured* fact rather than a reading of [python.multiplatform.ksp.BindingPolicy].
 *
 * `docs/upcall-async-design.md` explains why none of these can be in the table as they stand: a
 * CPython callback slot is a C frame that must hand back a `PyObject *` before it returns, and a
 * suspension has nothing to hand back. The fixtures exist so that the day an async convention
 * lands, the change in behaviour shows up as these tests failing rather than as silence.
 *
 * Each suspending declaration is paired with a non-suspending control in the same scope, so a
 * test asserting the suspending one is absent cannot pass by the whole scope having been dropped.
 */

// ------------------------------------------------------------------------------------ top level

/** Nothing about the body suspends; the modifier alone is the subject. */
suspend fun suspendingTopLevel(x: Long): Long = x * 2

/** The control for [suspendingTopLevel], in the same file and package. */
fun blockingTopLevel(x: Long): Long = x * 2

// -------------------------------------------------------------------------------------- members

class SuspendingService(val id: Long) {

    suspend fun fetch(key: String): String = "async:$key"

    /** The control: the class must stay exposed and constructible with a suspending member. */
    fun fetchBlocking(key: String): String = "sync:$key"

    /** A `val` whose *type* is suspending, as opposed to a suspending accessor. */
    val handler: suspend (Long) -> Long = { it + 1 }

    companion object {
        suspend fun create(id: Long): SuspendingService = SuspendingService(id)

        /** The control for [create]. */
        fun createBlocking(id: Long): SuspendingService = SuspendingService(id)
    }
}

interface SuspendingSource {
    suspend fun load(): String

    /** The control: an interface with a suspending member is still exposed for the rest. */
    fun describe(): String = "source"
}

object SuspendingRegistry {
    suspend fun ping(): String = "async-pong"

    /** The control. */
    fun pingBlocking(): String = "pong"
}

// ------------------------------------------------------- suspending *types*, not suspending funs

/**
 * A top-level property whose type is a suspending function type.
 *
 * This is a different question from a `suspend fun`: [python.multiplatform.ksp.BindingPolicy]
 * reads the *modifier* on a declaration, and there is no modifier here -- the suspension is in
 * the type. Whether the generated cast for it compiles is the thing being pinned.
 */
val suspendingHandler: suspend (Long) -> Long = { it + 1 }

/** The same question on the parameter side. */
fun runsHandler(handler: suspend (Long) -> Long): String = handler.toString().substringBefore('@')

/** And on the return side. */
fun makeHandler(): suspend (Long) -> Long = suspendingHandler
