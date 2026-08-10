package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `float` object (a C `double`).
 *
 * Note: the original stub had this implement `PyProxy<Boolean>` (presumably
 * copy-pasted from [PyBool]); corrected to `PyProxy<Double>` here since a
 * Python float's natural Kotlin projection is a [Double].
 */
open class PyFloat(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<Double> {
    companion object {
        /** The `PyType` for `float` (`builtins.float`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Wraps [value] as a new Python `float` object (`PyFloat_FromDouble`). */
        fun from(value: Double): PyFloat {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: Double?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}

    operator fun plus(other: PyFloat): PyFloat = TODO("Not yet implemented")
    operator fun minus(other: PyFloat): PyFloat = TODO("Not yet implemented")
    operator fun times(other: PyFloat): PyFloat = TODO("Not yet implemented")
    operator fun compareTo(other: PyFloat): Int = TODO("Not yet implemented")
}

fun Double.asPyObject(): PyFloat = PyFloat.from(this)
