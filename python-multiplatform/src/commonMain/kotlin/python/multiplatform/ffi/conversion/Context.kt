package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyBool
import python.multiplatform.ffi.types.basic.PyFloat
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.types.basic.PyString
import python.multiplatform.ffi.types.collections.PyDict
import python.multiplatform.ffi.types.collections.PyFrozenSet
import python.multiplatform.ffi.types.collections.PyList
import python.multiplatform.ffi.types.collections.PySet
import python.multiplatform.ffi.types.collections.PyTuple
import python.multiplatform.ffi.types.collections.pyObjectToNative
import python.native.ffi.NativePointer

/**
 * Marker/behavioural contract for a conversion context: something that can
 * take a live [PyObject] and hand back a Kotlin-side representation of it.
 */
interface Context {
    /** Converts [obj] to whatever this context's active strategy dictates. */
    fun convertValue(obj: PyObject): Any?
}

/**
 * Default [Context] implementation. Bridges Python objects to Kotlin values
 * according to the active [ConversionStrategy], caching conversions where
 * the strategy allows it (see [PyValue]).
 */
class PyContext(private var strategy: ConversionStrategy = ConversionStrategy.DEFAULT) : Context {

    /** The [ConversionStrategy] this context currently applies. */
    val activeStrategy: ConversionStrategy get() = strategy

    /**
     * Runs [block] with the conversion strategy temporarily switched to
     * [strategy], restoring the previous strategy afterwards -- even if
     * [block] throws.
     */
    fun <T> withContext(strategy: ConversionStrategy, block: () -> T): T {
        val previous = this.strategy
        this.strategy = strategy
        try {
            return block()
        } finally {
            this.strategy = previous
        }
    }

    /**
     * Converts [obj] according to [activeStrategy], dispatching to
     * [autoConvert], [proxyConvert] or [naiveConvert] -- plus two strategies
     * ([ConversionStrategy.RAW] and [ConversionStrategy.NATIVE]) that are
     * cheap/leaf enough to not warrant their own private helper (see the
     * class doc on [ConversionStrategy] for what each variant means).
     */
    override fun convertValue(obj: PyObject): Any? {
        return when (strategy) {
            ConversionStrategy.DEFAULT -> autoConvert(obj)
            ConversionStrategy.TYPED -> proxyConvert<Any?>(obj)
            ConversionStrategy.UNMANAGED -> naiveConvert(obj)
            // RAW is deliberately *not* routed through naiveConvert: per the
            // design note on ConversionStrategy, RAW means "no wrapper object
            // materialised at all" -- UNMANAGED is the variant that still
            // wraps in a PyObject. Handing back the bare NativePointer here is
            // what actually keeps the two strategies distinct.
            ConversionStrategy.RAW -> obj.pointer
            ConversionStrategy.NATIVE -> pyObjectToNative(obj)
        }
    }

    /**
     * Picks the best strategy for [obj]'s Python type and converts
     * accordingly ([ConversionStrategy.DEFAULT]).
     *
     * Per the design note on [ConversionStrategy], DEFAULT defers to TYPED as
     * the sane general-purpose choice: a typed wrapper that converts to a
     * native value lazily/on demand, rather than NATIVE's always-eager,
     * fully-recursive walk (expensive, and not even always possible -- e.g.
     * for a user-defined object with no native Kotlin counterpart) or RAW's
     * total loss of type safety and refcount bookkeeping.
     */
    private fun autoConvert(obj: PyObject): Any? {
        return proxyConvert<Any?>(obj)
    }

    /**
     * Wraps [obj] in a lazily-converting [PyValue] ([ConversionStrategy.TYPED]),
     * first re-wrapping [obj] as the most specific [PyObject] subclass its
     * Python-level type has a dedicated wrapper for (via [typedWrap]) so that
     * subclass's own `cachedNativeValue` accessor is what eventually performs
     * the native conversion.
     */
    private fun <T> proxyConvert(obj: PyObject): PyValue<T> {
        return PyValue(typedWrap(obj))
    }

    /**
     * Passes [obj] through unchanged, still wrapped as a plain [PyObject]
     * under Python's own refcount management -- no defensive copy, no typed
     * re-wrap, no caching ([ConversionStrategy.UNMANAGED]).
     */
    private fun naiveConvert(obj: PyObject): PyObject {
        return obj
    }

    /**
     * Re-wraps [obj] as the most specific [PyObject] subclass its Python-level
     * type corresponds to (e.g. a Python `int` becomes a [PyInt]), matching
     * what [python.multiplatform.ffi.types.collections.pyObjectToNative] does
     * for [ConversionStrategy.NATIVE] one level up (recursively, all the way
     * to native Kotlin values) but stopping one level short here, at the typed
     * wrapper itself. Falls back to [obj] unchanged for any Python type this
     * library has no dedicated wrapper for (arbitrary user-defined objects) --
     * TYPED cannot go further than that (see the [ConversionStrategy] doc).
     *
     * Dispatches on [python.multiplatform.ffi.PyType.name] for the same
     * reason `CollectionSupport.kt`'s helpers do: this ABI subset has no
     * `PyLong_Check`/`PyDict_Check`/... family to type-switch on directly.
     * New reference ownership: [PyObject.pointer] is still owned by [obj]
     * afterwards, so each typed wrapper below is built with `borrowed = true`
     * to take its own independent, incref'd reference to the same pointer.
     */
    private fun typedWrap(obj: PyObject): PyObject {
        return when (obj.getType().name) {
            "NoneType" -> PyNone.get()
            "bool" -> PyBool(obj.pointer, true)
            "int" -> PyInt(obj.pointer, true)
            "float" -> PyFloat(obj.pointer, true)
            "str" -> PyString(obj.pointer, true)
            "list" -> PyList(obj.pointer, true)
            "tuple" -> PyTuple(obj.pointer, true)
            "dict" -> PyDict(obj.pointer, true)
            "set" -> PySet(obj.pointer, true)
            "frozenset" -> PyFrozenSet(obj.pointer, true)
            else -> obj
        }
    }
}
