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

    override fun toString(): String = "ExposedCallable($kind $name/$arity -> $returnType)"
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
