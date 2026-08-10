package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `bool` object.
 *
 * In Python, `bool` is a subclass of `int` (`PyType.isSubtypeOf` will report
 * that at the Python level). That relationship is deliberately *not* mirrored
 * in the Kotlin class hierarchy here (`PyBool` does not extend [PyInt]):
 * both types implement [PyProxy] with different type arguments (`Boolean` vs
 * `Long`), and Kotlin does not allow a class to implement the same generic
 * interface twice with different arguments. Use [PyType.isSubtypeOf] if the
 * Python-level relationship needs to be observed.
 */
open class PyBool(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<Boolean> {
    companion object {
        /** The `PyType` for `bool` (`builtins.bool`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Returns the (singleton) `True` or `False` Python object for [value] (`PyBool_FromLong`). */
        fun from(value: Boolean): PyBool {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: Boolean?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}
}

fun Boolean.asPyObject(): PyBool = PyBool.from(this)
