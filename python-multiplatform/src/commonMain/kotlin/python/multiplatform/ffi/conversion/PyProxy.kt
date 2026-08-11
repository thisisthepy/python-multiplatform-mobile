package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.types.basic.asPyObject
import python.multiplatform.ffi.types.collections.pyObjectToNative


/**
 * A two-sided view of one value: the live Python object, and its native
 * Kotlin projection of type [T], each materialised on demand and cached.
 *
 * ## What "lazy" means here
 *
 * Laziness is about *when*, not about *how far*. Nothing is converted until
 * [toKotlin]/[toKotlinOrNull] is called; once it is, the value is converted
 * all the way down to native Kotlin types by
 * [python.multiplatform.ffi.types.collections.pyObjectToNative] -- the same
 * recursive walk [ConversionStrategy.NATIVE] performs eagerly. That is the
 * whole difference between the two strategies: TYPED pays for the walk only
 * if someone actually asks, and pays for it once.
 *
 * ## Values with no native Kotlin counterpart
 *
 * An arbitrary user-defined Python object (or any type this library has no
 * dedicated wrapper for) cannot be projected onto a Kotlin value at all --
 * TYPED is as far as it goes, as [ConversionStrategy]'s doc says. Of the
 * three defensible answers (throw / return the [PyObject] unchanged / return
 * null) this interface offers the two honest ones and rejects the third:
 *
 * - [toKotlinOrNull] returns `null`. Nullability is already in the signature,
 *   so the caller is told about the possibility at the type level and can
 *   handle it.
 * - [toKotlin] throws [PyTypeError]. It promises a [T] and cannot deliver
 *   one; failing loudly at the boundary keeps the blame at the conversion.
 * - Returning the [PyObject] unchanged from [toKotlin] is *not* offered. `T`
 *   is erased on every target here, so the unchecked cast would succeed and
 *   the mismatch would only surface at some unrelated use site far from its
 *   cause -- exactly the failure mode that is hardest to debug. Callers who
 *   want the Python object already have it: [toPython] hands it back.
 *
 * Python's `None` is treated the same way by [toKotlin] (it has a Kotlin
 * counterpart -- `null` -- but not a `T`-typed one), and is reported as
 * `null` by [toKotlinOrNull].
 *
 * ## Lifetime rule
 *
 * A cached native value and the [PyObject] it came from have *different*
 * lifetimes, so the rule is: **nothing cached in [cachedNativeValue] may
 * point into Python-owned memory.** Case by case, for everything
 * [pyObjectToNative] can produce:
 *
 * - `int`/`float`/`bool` -> Kotlin `Long`/`Double`/`Boolean`.
 *   `PyLong_AsLongLong` and friends copy a scalar out of the object; the
 *   result is a plain Kotlin value that shares nothing with CPython. Safe to
 *   cache, and safe to outlive its source.
 * - `str` -> Kotlin `String`. `PyUnicode_AsUTF8` returns a `const char*`
 *   *owned by CPython*, valid only while the `str` object lives. That
 *   pointer is never cached here and never escapes: each platform binding
 *   decodes it into a Kotlin `String` at the boundary (`toKString()` on
 *   Kotlin/Native, an equivalent copy through Panama on desktop), and the
 *   copy is independent. Cache the `String`; never the pointer.
 * - `list`/`tuple`/`dict`/`set`/`frozenset` -> Kotlin `List`/`Map`/`Set`.
 *   [pyObjectToNative] recurses, so every element is itself already one of
 *   the independent cases above -- no element holds a `PyObject`, a
 *   `NativePointer` or a borrowed C pointer. The container is therefore
 *   independent too. It is, however, a **snapshot**: mutating the Python
 *   container afterwards does not update it. Call [invalidateNativeCache] to
 *   force the next read to re-convert.
 * - anything else -> nothing is cached; [toKotlin] throws.
 *
 * The other direction has the mirror-image rule: [cachedPyObjectValue] must
 * hold an *owned* reference, never a borrowed one, because the proxy can
 * outlive whatever handed the object over. Everything that populates it does
 * so: [PyContext]'s `typedWrap` builds its wrappers with `borrowed = true`
 * (which increfs), and [toPython]'s native-to-Python path builds new objects
 * whose sole reference the returned wrapper owns.
 */
interface PyProxy<T> {
    /**
     * The cached native projection, or `null` if it has not been computed.
     *
     * Implementors that can compute their value in one FFI call (e.g.
     * [python.multiplatform.ffi.types.basic.PyInt]) override this as a
     * computing getter and never store anything; for those, "the cache" is
     * always warm and the default [toKotlin] below returns on its first line.
     */
    var cachedNativeValue: T?

    /** The cached Python side. See the lifetime rule in the class doc: this must be an owned reference. */
    var cachedPyObjectValue: PyObject?

    /**
     * The live Python object this proxy converts from, if there is one.
     *
     * A proxy is either a wrapper that *is* a [PyObject] (`PyInt`, `PyList`,
     * ... -- note their [cachedPyObjectValue] field starts out `null`, so the
     * `this` branch is the one that answers for them) or a separate holder
     * such as [PyValue] that points at one. Only a [PyValue] built from a
     * Kotlin value alone has neither.
     */
    fun sourcePyObject(): PyObject? = cachedPyObjectValue ?: (this as? PyObject)

    /**
     * The native Kotlin projection of this value, or `null` when there is
     * none: Python `None`, or a Python type with no native Kotlin
     * counterpart (see the class doc).
     *
     * Converts on first call and caches the result; later calls make no FFI
     * call at all. `None` is the one case that is re-derived every time --
     * `null` is indistinguishable from "not yet computed" in the cache field
     * -- which costs one pointer comparison and no FFI crossing.
     */
    @Suppress("UNCHECKED_CAST")
    fun toKotlinOrNull(): T? {
        cachedNativeValue?.let { return it }

        val source = sourcePyObject() ?: return null

        // If the source is one of this library's typed wrappers, its own
        // accessor is the cheapest route -- PyInt/PyFloat/PyBool/PyString
        // convert in a single FFI call there, which is exactly what
        // PyContext.typedWrap re-wraps for. Collection wrappers store a plain
        // null here instead, so they fall through to the generic walk below.
        if (source !== this && source is PyProxy<*>) {
            (source.cachedNativeValue as T?)?.let {
                cachedNativeValue = it
                return it
            }
        }

        // None has a Kotlin counterpart (null) but no non-null one. Checked
        // first, and by pointer identity, so it costs no FFI call.
        if (PyNone.isNone(source)) return null

        if (!hasNativeCounterpart(source)) return null

        val converted = pyObjectToNative(source) as T?
        // Safe to cache and to outlive `source`: see the lifetime rule above --
        // every branch pyObjectToNative can reach here produces an independent
        // Kotlin value, never a view into Python-owned memory.
        cachedNativeValue = converted
        return converted
    }

    /**
     * The native Kotlin projection of this value.
     *
     * @throws PyTypeError if this value has no native Kotlin counterpart, or
     * is Python's `None` (whose counterpart, `null`, is not a `T`). Use
     * [toKotlinOrNull] when either is expected.
     */
    fun toKotlin(): T {
        val value = toKotlinOrNull()
        if (value != null) return value

        val source = sourcePyObject()
            ?: throw PyTypeError("This proxy holds neither a native value nor a Python object to convert from")
        throw if (PyNone.isNone(source)) {
            PyTypeError("Python None has no non-null Kotlin counterpart; use toKotlinOrNull()", value = source)
        } else {
            PyTypeError(
                "No native Kotlin counterpart for Python type '${source.Type.name}'; " +
                    "TYPED conversion stops at the PyObject itself -- use toPython()",
                value = source,
            )
        }
    }

    /**
     * The Python side of this value, materialising it from the cached native
     * value if this proxy was built from a Kotlin value alone.
     *
     * Only the scalar cases are materialised, because those have exactly one
     * unambiguous Python spelling and an existing constructor to build it
     * with. A Kotlin `List`/`Map`/`Set` does not: whether it should become a
     * `list` or a `tuple`, a `set` or a `frozenset`, and who then owns the
     * references to the converted elements, are decisions that belong to the
     * collection wrappers (`PyList.fromList` and friends), not to a generic
     * proxy. Those raise [PyTypeError] here rather than guessing.
     */
    fun toPython(): PyObject {
        cachedPyObjectValue?.let { return it }

        // Wrappers that are themselves PyObjects are their own Python side.
        (this as? PyObject)?.let {
            cachedPyObjectValue = it
            return it
        }

        val built = nativeToPyObject(cachedNativeValue)
        // Owned, not borrowed: every constructor below takes ownership of a
        // brand-new reference (or, for None, of the immortal singleton), so
        // the proxy can outlive its caller safely.
        cachedPyObjectValue = built
        return built
    }

    /**
     * Drops the cached native value so the next [toKotlin]/[toKotlinOrNull]
     * re-converts from Python.
     *
     * The reason this exists is the snapshot rule in the class doc: a cached
     * `List`/`Map`/`Set` is independent of Python-owned memory precisely
     * *because* it is a copy, which also means it stops tracking a container
     * that is still being mutated on the Python side. Implementors with a
     * computing [cachedNativeValue] accessor never cache anything, so this is
     * a no-op for them.
     */
    fun invalidateNativeCache() {
        cachedNativeValue = null
    }
}

/**
 * Whether [obj]'s Python type has a native Kotlin counterpart at all.
 *
 * Answered by [typedWrap] rather than by a table of its own: `typedWrap`
 * already *is* the list of types this library has a dedicated wrapper for,
 * and returns [obj] unchanged for everything else, so asking it keeps the two
 * from drifting apart when a wrapper is added. The wrapper it builds is
 * thrown away immediately -- it exists only to answer the question -- and is
 * closed rather than left to the cleaner, so the incref `typedWrap` performed
 * is undone at once instead of at some arbitrary later GC.
 *
 * Cost: one extra `PyObject_Type` crossing plus an incref/decref pair, paid
 * once per proxy (the result is then cached), and only for a source that is
 * not already a typed wrapper.
 */
private fun hasNativeCounterpart(obj: PyObject): Boolean {
    if (obj is PyProxy<*>) return true // already a typed wrapper: it exists precisely because it has one
    val typed = typedWrap(obj)
    if (typed === obj) return false
    typed.close()
    return true
}

/**
 * Builds a Python object for a native Kotlin scalar. See [PyProxy.toPython]
 * for why containers are deliberately not handled here.
 */
private fun nativeToPyObject(value: Any?): PyObject = when (value) {
    null -> PyNone.get()
    is Boolean -> value.asPyObject()
    is Long -> value.asPyObject()
    is Int -> value.asPyObject()
    is Short -> value.toLong().asPyObject()
    is Byte -> value.toLong().asPyObject()
    is Double -> value.asPyObject()
    is Float -> value.toDouble().asPyObject()
    is String -> value.asPyObject()
    else -> throw PyTypeError("No unambiguous Python counterpart for a cached value of type '${value::class.simpleName}'")
}

/**
 * Concrete [PyProxy] holding both sides of a conversion: the originating
 * [PyObject] and, once computed, its native Kotlin projection of type [T].
 *
 * This is the `PyValue` from the design sketch -- [asNative]/[asPyObject]
 * are thin aliases over [toKotlin]/[toPython] using the sketch's naming.
 *
 * At least one side must be supplied. A [PyValue] with neither would have
 * nothing to convert from in either direction, and would only be able to
 * report that fact from whichever accessor happened to be called first.
 */
class PyValue<T>(
    pyObj: PyObject? = null,
    initialNativeValue: T? = null,
) : PyProxy<T> {
    init {
        require(pyObj != null || initialNativeValue != null) {
            "A PyValue needs a source PyObject, a native value, or both -- it cannot convert from nothing"
        }
    }

    override var cachedNativeValue: T? = initialNativeValue
    override var cachedPyObjectValue: PyObject? = pyObj

    fun asNative(): T = toKotlin()

    /** [asNative]'s null-returning form; see [toKotlinOrNull]. */
    fun asNativeOrNull(): T? = toKotlinOrNull()

    fun asPyObject(): PyObject = toPython()
}
