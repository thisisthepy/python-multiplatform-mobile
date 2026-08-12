package python.multiplatform.reflection

import kotlin.jvm.JvmInline


/**
 * How a value is marshalled across the boundary. The trampoline reads these, never the Kotlin
 * type, because there is no runtime type to read on Kotlin/Native.
 *
 * `docs/upcall-table-design.md` lists five (`INT`, `FLOAT`, `STRING`, `BYTES`, `OBJECT`);
 * [BOOLEAN] and [UNIT] are here because Python's `bool` is a distinct type and because a
 * function returning `Unit` must marshal to `None` rather than to an object handle.
 */
enum class TypeTag {
    INT,
    FLOAT,
    BOOLEAN,
    STRING,
    BYTES,
    UNIT,

    /** Anything without a dedicated marshaller: crosses as an [ObjectReference] handle. */
    OBJECT,
}


/**
 * Which CPython slot an entry is reached through, and therefore whether `args[0]` is a receiver.
 *
 * Every CPython callback convention passes the relevant object as an argument -- `self` for
 * methods, `type` for `tp_new`, the closure for getters -- which is why a handful of shared
 * trampolines can serve an unbounded number of Kotlin targets and no runtime code generation is
 * needed. See `docs/upcall-design.md`.
 */
enum class CallableKind(val hasReceiver: Boolean) {
    /** Top-level or companion function. `args` is exactly the declared parameters. */
    FUNCTION(false),

    /** `tp_new`/`tp_init`: the receiver does not exist yet, so it is not in `args`. */
    CONSTRUCTOR(false),

    /** `args[0]` is the receiver, resolved from the proxy's handle. */
    METHOD(true),

    /** Property getter. `args[0]` is the receiver and there are no further arguments. */
    GETTER(true),

    /** Property setter, `var` only. `args[0]` is the receiver, `args[1]` the new value. */
    SETTER(true),

    /**
     * A property read that needs no instance: a `companion object` or `object` property, or an
     * enum entry. `args` is empty.
     *
     * Distinct from [FUNCTION] with arity 0 because Python renders it as an attribute on the
     * type rather than as a callable, and distinct from [GETTER] because there is no receiver to
     * put in `args[0]` -- treating it as one shifts every argument by a slot.
     */
    STATIC_GETTER(false),

    /** The `var` counterpart of [STATIC_GETTER]. `args[0]` is the new value, not a receiver. */
    STATIC_SETTER(false),
}


/**
 * One entry in the function table: everything the boundary needs to call a Kotlin target, with
 * no reflection anywhere.
 *
 * [callable] is deliberately a lambda rather than a `KFunction` reference.
 * `KFunction.call()` re-checks arity and types and boxes on every call (50--100 ns, measured in
 * this project) and does not exist at all on Kotlin/Native; a generated
 * `{ args -> greet(args[0] as String) }` compiles to a direct call there. On the JVM a
 * `MethodHandle` can be substituted behind the same shape.
 *
 * The generator emits these; [python.multiplatform.reflection.FunctionTableFragment] is where
 * they arrive from.
 */
class ExposedCallable(
    /** The Python-visible qualified name. The only string on this path, and it is used once. */
    val name: String,
    /** Declared parameter count, **excluding** any receiver. See [expectedArgCount]. */
    val arity: Int,
    val paramTypes: List<TypeTag>,
    val returnType: TypeTag,
    val kind: CallableKind = CallableKind.FUNCTION,
    /**
     * Whether the Kotlin declaration behind this entry is a `suspend fun`, and therefore whether
     * [callable] returns the result or a
     * [python.multiplatform.ffi.upcall.PendingCall] standing in for one.
     *
     * A flag rather than a [CallableKind] of its own, because the two are orthogonal: a suspending
     * *method* still has its receiver in `args[0]` and a suspending companion member still has
     * none, so folding suspension into [CallableKind] would make every [CallableKind.hasReceiver]
     * decision restate itself once per variant. What [kind] answers is *where the arguments are*;
     * what this answers is *what comes back*.
     *
     * [paramTypes] and [returnType] describe the declaration as written -- the continuation
     * parameter the Kotlin compiler adds is not here, and [returnType] is the value the coroutine
     * will eventually produce, not the `PendingCall`. That is what lets
     * `docs/upcall-async-design.md` §5's fast path marshal a body that never suspended exactly as
     * a synchronous entry would.
     */
    val isSuspend: Boolean = false,
    val callable: (Array<Any?>) -> Any?,
) {
    init {
        // A generator bug, caught where the generated code is loaded rather than at the first
        // call from Python with the wrong number of arguments.
        require(paramTypes.size == arity) {
            "$name declares arity $arity but ${paramTypes.size} parameter types"
        }
    }

    /** The size of the `args` array [callable] expects: [arity] plus a receiver slot if any. */
    val expectedArgCount: Int get() = arity + if (kind.hasReceiver) 1 else 0

    override fun toString(): String =
        "ExposedCallable(${if (isSuspend) "suspend " else ""}$kind $name/$arity -> $returnType)"
}


/**
 * A resolved entry in [UpcallTable] -- the thing the Python proxy caches so that no call after
 * the first one passes a string.
 *
 * `docs/upcall-design.md` takes this from ObjC: a selector is fast because it is an interned
 * pointer, not because a table exists. The equivalent here is that the name is resolved once
 * and this integer is what crosses afterwards.
 *
 * The raw value packs the table **epoch** into the high 32 bits and the entry index into the
 * low 32, for the same reason [ObjectReference] carries a generation: a handle cached by a
 * Python proxy across a table reinstall would otherwise index into the new table and invoke a
 * different function.
 */
@JvmInline
value class CallableHandle(val raw: Long) {

    /** False for [NONE]. A valid-looking handle may still belong to a superseded table. */
    val isValid: Boolean get() = raw >= 0

    internal val index: Int get() = (raw and 0xFFFF_FFFFL).toInt()

    internal val epoch: Int get() = (raw ushr 32).toInt()

    override fun toString(): String =
        if (raw < 0) "CallableHandle(NONE)" else "CallableHandle(index=$index, epoch=$epoch)"

    companion object {
        /** "No such name." Crosses the boundary as -1; raising `AttributeError` is Python's job. */
        val NONE: CallableHandle = CallableHandle(-1L)

        internal fun encode(index: Int, epoch: Int): CallableHandle =
            CallableHandle((epoch.toLong() shl 32) or (index.toLong() and 0xFFFF_FFFFL))
    }
}
