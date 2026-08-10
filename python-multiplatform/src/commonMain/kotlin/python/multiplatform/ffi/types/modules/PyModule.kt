package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.collections.PyDict
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python module object (whatever
 * [python.multiplatform.ffi.Python3.import] / `PyImport_ImportModule`
 * returns).
 *
 * Attribute access (`module.attr`) is already covered by the inherited
 * [PyObject.getAttr]/[PyObject.setAttr]; what this adds is the
 * module-specific metadata (`__name__`, `__dict__`, `__doc__`, `__file__`).
 */
open class PyModule(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {

    /** `module.__name__`. */
    val name: String
        get() = TODO("Not yet implemented")

    /** `module.__doc__`, or `null` if the module has none. */
    val doc: String?
        get() = TODO("Not yet implemented")

    /** `module.__file__`, or `null` for built-in/frozen modules. */
    val file: String?
        get() = TODO("Not yet implemented")

    /** `module.__dict__`: the module's namespace. */
    val dict: PyDict
        get() = TODO("Not yet implemented")

    /**
     * Looks up [name] in this module's namespace, throwing [PyException] if
     * it is not defined -- equivalent to [PyObject.getAttr] but named to
     * match how one thinks about "a symbol from a module".
     */
    @Throws(PyException::class)
    fun get(name: String): PyObject = getAttr(name)
}

/**
 * Thin, curated view over the `builtins` module (the implicit namespace that
 * backs unqualified names like `len`, `print`, `type` in Python source).
 *
 * Left minimal on purpose: everything it would expose is already reachable
 * generically via [PyModule.get] on the `builtins` module returned by
 * [python.multiplatform.ffi.Python3.import]; this only exists as a
 * convenience surface for the handful of builtins the library itself leans
 * on internally (e.g. `len`, `iter`, `type` when implementing the bodies of
 * the collection/iterator wrappers).
 */
class Builtins(private val module: PyModule) {
    fun len(obj: PyObject): Int = TODO("Not yet implemented")
    fun iter(obj: PyObject): PyObject = TODO("Not yet implemented")
    fun type(obj: PyObject): PyObject = TODO("Not yet implemented")
}
