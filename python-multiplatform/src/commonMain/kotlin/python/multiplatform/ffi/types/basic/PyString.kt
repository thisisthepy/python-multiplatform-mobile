package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/** Wrapper around a Python `str` object (`PyUnicode_*` family). */
open class PyString(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<String> {
    companion object {
        /** The `PyType` for `str` (`builtins.str`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Wraps [value] as a new Python `str` object (`PyUnicode_FromString`). */
        fun from(value: String): PyString {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: String?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}

    /** `len(self)`. */
    val length: Int
        get() = TODO("Not yet implemented")

    operator fun plus(other: PyString): PyString = TODO("Not yet implemented")
    operator fun get(index: Int): PyString = TODO("Not yet implemented")
    operator fun contains(substring: String): Boolean = TODO("Not yet implemented")
}

fun String.asPyObject(): PyString = PyString.from(this)
