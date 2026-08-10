package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PyObject


interface PyProxy<T> {
    var cachedNativeValue: T?
    var cachedPyObjectValue: PyObject?

    fun toKotlin(): T {
        if (cachedNativeValue == null) {
            //cachedNativeValue = toKotlinInternal()
            // TODO: Implement the conversion logic to Kotlin type T
        }
        return cachedNativeValue!!
    }

    fun toPython(): PyObject {
        if (cachedPyObjectValue == null) {
            //cachedPyObjectValue = toPythonInternal()
            // TODO: Implement the conversion logic to PyObject
        }
        return cachedPyObjectValue!!
    }
}

/**
 * Concrete [PyProxy] holding both sides of a conversion: the originating
 * [PyObject] and, once computed, its native Kotlin projection of type [T].
 *
 * This is the `PyValue` from the design sketch -- [asNative]/[asPyObject]
 * are thin aliases over [toKotlin]/[toPython] using the sketch's naming.
 */
class PyValue<T>(
    pyObj: PyObject,
    initialNativeValue: T? = null,
) : PyProxy<T> {
    override var cachedNativeValue: T? = initialNativeValue
    override var cachedPyObjectValue: PyObject? = pyObj

    fun asNative(): T = toKotlin()
    fun asPyObject(): PyObject = toPython()
}
