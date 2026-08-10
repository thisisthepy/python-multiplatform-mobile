package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `int` object.
 *
 * CPython ints are arbitrary precision, but the Stable ABI surface available
 * here (`PyLong_As/FromLongLong`) only bridges the 64-bit range. Values
 * outside `Long` range will not round-trip through [toKotlin] -- this is a
 * known limitation to revisit (e.g. a `BigInteger`-backed path) rather than
 * an oversight.
 */
open class PyInt(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<Long> {
    companion object {
        /** The `PyType` for `int` (`builtins.int`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Wraps [value] as a new Python `int` object (`PyLong_FromLongLong`). */
        fun from(value: Long): PyInt {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: Long?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}

    operator fun plus(other: PyInt): PyInt = TODO("Not yet implemented")
    operator fun minus(other: PyInt): PyInt = TODO("Not yet implemented")
    operator fun times(other: PyInt): PyInt = TODO("Not yet implemented")
    operator fun compareTo(other: PyInt): Int = TODO("Not yet implemented")
}

fun Long.asPyObject(): PyInt = PyInt.from(this)
fun Int.asPyObject(): PyInt = PyInt.from(this.toLong())
