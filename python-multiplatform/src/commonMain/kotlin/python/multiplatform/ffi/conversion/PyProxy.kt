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
