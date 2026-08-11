package python.multiplatform.reflection


/**
 * A build-time descriptor for one exposed Kotlin class: what to call it from Python, which
 * [UpcallTable] entries are its members, and how to walk the Python references it holds for
 * cycle collection.
 *
 * KSP emits one of these per exposed class alongside the [ExposedCallable] entries for its
 * constructor, methods and accessors -- see `docs/upcall-table-design.md`, and for why
 * [traverse] exists at all, `docs/object-lifetime.md`'s "Cycle collection is part of the
 * table's job".
 */
class ReflectedClass(
    /** The Python-visible qualified name, and the prefix every entry in [memberNames] shares. */
    val name: String,
    /** [UpcallTable]-resolvable names of this class's constructor, methods and accessors. */
    val memberNames: List<String>,
    traverse: ((Any, (Long) -> Unit) -> Unit)? = null,
) {
    private val traverseImpl = traverse

    /**
     * Whether the proxy type needs a `tp_traverse` slot at all. A class with no `PyObject`-typed
     * fields cannot be part of a cycle through this object, so the generator leaves [traverse]
     * unset here rather than emitting an always-present no-op.
     */
    val hasTraverse: Boolean get() = traverseImpl != null

    /**
     * Visits the raw pointer of every live `PyObject`-typed field [obj] holds. CPython runs
     * `tp_traverse` during collection, which is why the generated [traverse] is restricted to
     * field reads and [visit] calls -- no allocation, no other user code. A no-op when
     * [hasTraverse] is false.
     */
    fun traverse(obj: Any, visit: (Long) -> Unit) {
        traverseImpl?.invoke(obj, visit)
    }

    override fun toString(): String = "ReflectedClass($name, ${memberNames.size} members)"
}
