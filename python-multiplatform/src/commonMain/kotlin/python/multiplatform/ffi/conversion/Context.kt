package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PyObject

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
        TODO("Not yet implemented")
    }

    /** Converts [obj] according to [activeStrategy], dispatching to [autoConvert], [proxyConvert] or [naiveConvert]. */
    override fun convertValue(obj: PyObject): Any? {
        TODO("Not yet implemented")
    }

    /** Picks the best strategy for [obj]'s Python type and converts accordingly ([ConversionStrategy.DEFAULT]). */
    private fun autoConvert(obj: PyObject): Any? {
        TODO("Not yet implemented")
    }

    /** Wraps [obj] in a lazily-converting [PyValue] ([ConversionStrategy.TYPED]). */
    private fun <T> proxyConvert(obj: PyObject): PyValue<T> {
        TODO("Not yet implemented")
    }

    /** Passes [obj] through unchanged ([ConversionStrategy.UNMANAGED] / [ConversionStrategy.RAW]). */
    private fun naiveConvert(obj: PyObject): PyObject {
        TODO("Not yet implemented")
    }
}
