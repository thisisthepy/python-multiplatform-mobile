package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.multiplatform.reflection.TypeTag

/**
 * The other direction: **a Python callable arriving at a Kotlin function-typed parameter.**
 *
 * `docs/pythonx-adapter-design.md` §6 opens by saying the mechanism is half present already --
 * `UpcallTrampoline.toKotlinObject` wraps anything Python sends that is not an integer handle as a
 * `PyObject`, so a `lambda:` does reach Kotlin with a reference of its own. What it reaches is a
 * `PyObject`, and every container composable's `content` slot wants a `kotlin.jvm.functions.FunctionN`.
 * That gap is the whole of why `Column`, `Row`, `Box` and `Button` were reachable only with `content`
 * defaulted -- and `Column`'s `content` declares no default at all, so it was not reachable.
 *
 * ### Why the wrapper can be built without Compose on the classpath
 *
 * A lowered `@Composable ColumnScope.() -> Unit` is, in the compiled form the thunk calls,
 * `Function3<ColumnScope, Composer, Integer, Unit>` -- and generics erase. The thunk's `CHECKCAST` is
 * to `kotlin/jvm/functions/Function3`, and Compose's own call is
 * `Function3.invoke(Object, Object, Object)`. So a Kotlin lambda of type `(Any?, Any?, Any?) -> Unit`
 * written in `commonMain` **is** an admissible `content`, and nothing here has to name `Composer`,
 * `ColumnScope` or any other Compose type. That is what keeps this file in `commonMain`, where the
 * `pythonx` layer it serves already lives, rather than in a Compose-aware module.
 *
 * ### What separates a `Function0` from a composable lambda, and how it is known
 *
 * Not by guessing at the arity. `ArtifactScanner.functionSlotTypeName` compares the two arities the
 * walker already has -- the declared `kotlin.FunctionN` from `@Metadata` against the
 * `Lkotlin/jvm/functions/FunctionM;` in the JVM descriptor -- and a composable lambda is exactly the
 * case `M == N + 2`, because the Compose plugin appends a `Composer` and one `$changed` to the
 * *function type* just as it does to a function. `Button`'s `onClick` is `Function0` in both, so it
 * is plain; `Column`'s `content` is `Function1` declared and `Function3` compiled, so it is lowered.
 * The walker writes the answer into the type name it already carries, and `pythonx` reads it there.
 *
 * The consequence for a caller is the one that matters: a **composable** lambda is invoked with the
 * composer its container established, which is threaded back into `pythonx`'s composer stack for the
 * duration of the call, so a `content` may itself call composables. A **plain** one is not, and
 * receives nothing.
 *
 * ### Lifetime: the scope is the holder, and it is the only one that can be
 *
 * Compose stores a `content` in the slot table and calls it again on later recompositions, so the
 * Python callable has to outlive the call that passed it -- `Column(content=lambda: ...)` drops
 * Python's last reference at the end of the statement. Something on the Kotlin side must hold one.
 *
 * Four candidates, and three of them do not work:
 *
 * | holder | why not |
 * |---|---|
 * | the `PyObject`'s own cleaner | it fires when the *Kotlin* wrapper is collected, which is whenever the collector gets round to it and never on a target without one. `docs/object-lifetime.md`'s point exactly |
 * | `HandleTable` | a strong root nothing gives back: the handle reaches Kotlin as a slot value and is never handed to Python, so there is no `__del__` to hang the release off (`HandleTable`'s own KDoc: "this table leaks by construction") |
 * | the composition **pass** | ends long before Compose stops calling the content |
 * | **[PythonCallableScope]** | lives exactly as long as the composition, because a Compose-side `RememberObserver` closes it in `onForgotten` |
 *
 * So this file owns the first three quarters of that -- creating, holding and releasing -- and the
 * one Compose type involved, `RememberObserver`, stays in the module that has Compose
 * (`:ksp-fixtures:compose`'s `PythonComposition`). `docs/pythonx-adapter-design.md` §6 item 1 names
 * `onForgotten` as "the one that fails silently and should be tested first"; the seam is here and the
 * test is there.
 *
 * ### Double release
 *
 * [PythonCallableScope.close] reports how many wrappers it released and answers `0` for every call
 * after the first, which is what `PythonxCallableTest` asserts against rather than only asserting
 * that the count came back. `agent-rules` §14 is the reason the two are separate assertions: a
 * reference released twice does not fail where it happens.
 *
 * A wrapper invoked after its scope closed **refuses**. Calling through a released `PyObject` would
 * not raise -- it would decrement whatever now occupies that address.
 */
object PythonCallables {

    /** The scopes currently open, innermost last. Not synchronised, for [HandleTable]'s reason:
     * every mutation happens on a thread that holds the GIL. */
    private val open = ArrayList<PythonCallableScope>()

    /** The scope a callable crossing right now belongs to, or `null` if none is open. */
    val current: PythonCallableScope? get() = open.lastOrNull()

    /** How many scopes are open. A composition that leaked one leaves this non-zero. */
    val openScopeCount: Int get() = open.size

    /** A new, empty scope. Nothing is ambient until it is passed to [withScope]. */
    fun newScope(): PythonCallableScope = PythonCallableScope()

    /**
     * Makes [scope] the ambient one for [block].
     *
     * Nested rather than replaced, because a `content` that is itself a container establishes an
     * inner composition-shaped region while an outer one is still running. Innermost wins, which is
     * the same rule `pythonx`'s composer stack uses and for the same reason.
     */
    inline fun <T> withScope(scope: PythonCallableScope, block: () -> T): T {
        push(scope)
        try {
            return block()
        } finally {
            pop(scope)
        }
    }

    /** @suppress internal to [withScope], which is the only supported way to nest a scope. */
    @PublishedApi
    internal fun push(scope: PythonCallableScope) {
        open.add(scope)
    }

    /** @suppress see [push]. */
    @PublishedApi
    internal fun pop(scope: PythonCallableScope) {
        val last = open.removeAt(open.size - 1)
        check(last === scope) { "pythonx callable scopes were closed out of order" }
    }

    /**
     * What `pythonx._make_function` calls: wrap [body] as a Kotlin `FunctionN` of [jvmArity].
     *
     * @param body the Python callable, already holding a reference of its own -- the trampoline's
     *   `PyObject(value, borrowed = true)`. The scope takes ownership of that reference and is what
     *   gives it back.
     * @param jvmArity how many arguments the *compiled* function type takes: 0 for `() -> Unit`, 3
     *   for a lowered `@Composable ColumnScope.() -> Unit`.
     * @param composable whether the last two of those are `$composer` and `$changed`.
     * @return the raw [ObjectReference] the wrapper is rooted under, which Python puts straight into
     *   the OBJECT slot as a bare handle.
     */
    internal fun newFunction(body: PyObject, jvmArity: Int, composable: Boolean): Long {
        val scope = current ?: run {
            // Closed here rather than leaked: the trampoline already took a reference for this
            // wrapper and nothing downstream exists to give it back.
            body.close()
            throw IllegalStateException(
                "no pythonx callable scope is open, so a Python callable has nowhere to live: a " +
                    "callable can only cross into Kotlin from inside a composition (or an explicit " +
                    "PythonCallables.withScope), because Compose keeps calling it after the call " +
                    "that passed it has returned",
            )
        }
        if (composable && jvmArity < 2) {
            body.close()
            throw IllegalArgumentException(
                "a lowered composable lambda takes at least a \$composer and a \$changed, so arity " +
                    "$jvmArity cannot be one",
            )
        }
        if (!composable && jvmArity != 0) {
            body.close()
            // Deliberately a refusal rather than dropping the arguments. A `(Float) -> Unit` whose
            // argument silently never arrived would render as a slider that never moved, and nothing
            // would say why. Forwarding them needs a decision about how a Kotlin value reaches a
            // Python parameter that has no declared type here, which is not made yet.
            throw IllegalArgumentException(
                "a plain Kotlin function type of arity $jvmArity is not bound yet: only () -> Unit " +
                    "crosses today, because forwarding an argument to Python needs a marshalling " +
                    "rule this slot does not carry",
            )
        }
        val function = PythonFunction(scope, body, composable)
        val erased = try {
            function.erasedAs(jvmArity)
        } catch (t: Throwable) {
            // Nothing has taken ownership yet, so the reference the trampoline took for this
            // wrapper is this frame's to give back.
            body.close()
            throw t
        }
        return scope.add(body, erased)
    }

    /**
     * The table entry [newFunction] is reached through.
     *
     * A fragment rather than a new `_pm_*` entry point on five bootstraps: this is one ordinary
     * Kotlin function called from Python, which is precisely what the upcall table is, and adding a
     * fourth C shape to `UpcallStub`, `bindings.kt` and three native bootstraps to carry it would be
     * platform work for no new capability. [PythonxAdapter.install] registers it, so the layer that
     * needs the service is the layer that declares it.
     *
     * It is a `pythonx.*` name on purpose. The walker only ever emits `androidx.*`/`kotlin.*` names
     * and KSP only ever emits the consumer's own, so nothing can collide with it, and a reader of
     * `pythonx._TABLE` can see that the adapter has exactly one Kotlin service of its own.
     */
    internal object Fragment : FunctionTableFragment {

        override val moduleName: String = "pythonx_runtime"

        override fun entries(): List<ExposedCallable> = listOf(
            ExposedCallable(
                name = NEW_FUNCTION,
                arity = 3,
                paramTypes = listOf(TypeTag.OBJECT, TypeTag.INT, TypeTag.BOOLEAN),
                returnType = TypeTag.INT,
                paramNames = listOf("body", "jvmArity", "composable"),
                paramTypeNames = listOf("kotlin.Any", "kotlin.Int", "kotlin.Boolean"),
                paramHasDefault = listOf(false, false, false),
                callable = { args ->
                    newFunction(args[0] as PyObject, (args[1] as Long).toInt(), args[2] as Boolean)
                },
            ),
        )
    }

    /** The name `pythonx` resolves. Shared so the Python source and the entry cannot drift. */
    internal const val NEW_FUNCTION: String = "pythonx.runtime.newFunction"
}

/**
 * Everything one composition handed to Kotlin, and the single point that gives it all back.
 *
 * Created by [PythonCallables.newScope], made ambient by [PythonCallables.withScope], and closed by
 * whoever owns the composition -- on Compose that is a `RememberObserver`, whose `onForgotten` is the
 * only hook that reports a slot being dropped.
 */
class PythonCallableScope internal constructor() {

    private val bodies = ArrayList<PyObject>()
    private val roots = ArrayList<ObjectReference>()
    private var closed = false

    /** How many callables this scope is currently holding a Python reference for. */
    val liveCount: Int get() = bodies.size

    /** Whether [close] has already run. A wrapper checks this before touching its `PyObject`. */
    val isClosed: Boolean get() = closed

    internal fun add(body: PyObject, erased: Any): Long {
        val root = HandleTable.register(erased)
        bodies.add(body)
        roots.add(root)
        return root.raw
    }

    /**
     * Gives every Python reference back and unroots every wrapper.
     *
     * @return how many callables this call actually released -- `0` for every call after the first.
     *   A count rather than a `Boolean` so that a test can assert both "the scope held what it said
     *   it held" and "the second close released nothing", which are different failures.
     */
    fun close(): Int {
        if (closed) return 0
        closed = true
        val released = bodies.size
        for (root in roots) HandleTable.release(root)
        // `PyObject.close()` runs the registered cleanup action, which every platform's cleaner
        // performs at most once -- so the decref here cannot be doubled by a later collection of the
        // same wrapper.
        for (body in bodies) body.close()
        roots.clear()
        bodies.clear()
        return released
    }
}

/**
 * One Python callable, and the invocation convention its slot implied.
 *
 * Kept separate from the erased lambda [erasedAs] produces so that the state -- the scope, the
 * `PyObject`, whether a composer is threaded -- exists once regardless of arity, and the per-arity
 * part is a lambda with nothing in it but a call.
 */
internal class PythonFunction(
    private val scope: PythonCallableScope,
    internal val body: PyObject,
    private val composable: Boolean,
) {

    /**
     * This callable as a `kotlin.FunctionN` of [jvmArity].
     *
     * A `when` over the arities rather than one generic shape, because there is no generic shape: a
     * JVM `Function3` is a different interface from a `Function2`, and the thunk's `CHECKCAST` names
     * one of them. The ceiling is where measurement stops rather than where the technique does --
     * `androidx.compose.foundation.layout` and `material3` declare no lowered content lambda wider
     * than this, and a wider one refuses by name instead of being cast to the wrong interface.
     */
    fun erasedAs(jvmArity: Int): Any {
        // One `return` per arity rather than a `when` expression: the branches of a `when` are
        // unified against each other, and `(Any?) -> Unit` and `(Any?, Any?) -> Unit` are unrelated
        // types. Each `return` is checked against `Any` on its own instead.
        when (jvmArity) {
            0 -> return fun() { invoke(emptyArray()) }
            1 -> return fun(a: Any?) { invoke(arrayOf(a)) }
            2 -> return fun(a: Any?, b: Any?) { invoke(arrayOf(a, b)) }
            3 -> return fun(a: Any?, b: Any?, c: Any?) { invoke(arrayOf(a, b, c)) }
            4 -> return fun(a: Any?, b: Any?, c: Any?, d: Any?) { invoke(arrayOf(a, b, c, d)) }
            5 -> return fun(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?) { invoke(arrayOf(a, b, c, d, e)) }
        }
        throw IllegalArgumentException(
            "a Kotlin function type of arity $jvmArity is not bound: nothing measured declares one, " +
                "and guessing at the interface would be a ClassCastException at the call site",
        )
    }

    private fun invoke(args: Array<Any?>) {
        check(!scope.isClosed) {
            "this Python callable was released with its composition and cannot be invoked: the " +
                "scope that held it is closed"
        }
        PythonCallables.withScope(scope) {
            if (!composable) {
                body().close()
                return@withScope
            }
            // The composer Compose established for *this* invocation, which is not necessarily the
            // one that was ambient when the lambda crossed. `$changed` -- the last argument -- is
            // the caller's claim about argument staticness and is not Python's to read.
            val composer = args[args.size - 2]
                ?: throw IllegalStateException("a composable lambda was invoked with a null \$composer")
            val root = HandleTable.register(composer)
            try {
                // The push and the pop are Python's, in `pythonx._content_thunk`: doing it here would
                // mean two more boundary crossings per invocation to reach `push_composer` and
                // `pop_composer`, and the `finally` that guarantees the pop belongs next to the call
                // it guards.
                val handle = PyInt.from(root.raw)
                try {
                    body(handle).close()
                } finally {
                    handle.close()
                }
            } finally {
                HandleTable.release(root)
            }
        }
    }
}
