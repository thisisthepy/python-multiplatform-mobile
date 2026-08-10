package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.native.ffi.NativePointer

/**
 * Wrapper around a plain Python function object (`def f(...): ...` or a
 * `lambda`). Calling it is already covered by the inherited
 * [PyObject.invoke]; this adds the function-specific introspection bits.
 */
open class PyFunction(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `function.__name__`. */
    val name: String
        get() = TODO("Not yet implemented")

    /** `function.__doc__`, or `null` if undocumented. */
    val doc: String?
        get() = TODO("Not yet implemented")

    /** `function.__qualname__`. */
    val qualifiedName: String
        get() = TODO("Not yet implemented")
}
