package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.types.collections.PyDict
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyModule_GetDict
import python.native.ffi.PyModule_GetFilenameObject
import python.native.ffi.PyModule_GetName

/**
 * Wrapper around a Python module object (whatever
 * [python.multiplatform.ffi.Python3.import] / `PyImport_ImportModule`
 * returns).
 *
 * Attribute access (`module.attr`) is already covered by the inherited
 * [PyObject.getAttr]/[PyObject.setAttr]; `name`/`dict`/`file` are instead
 * backed directly by `PyModule_GetName`/`PyModule_GetDict`/
 * `PyModule_GetFilenameObject` -- one FFI crossing each (`PyModule_GetName`
 * needs none at all beyond that, since it hands back an already-decoded
 * `const char*` rather than a `PyObject*` requiring a further
 * `PyUnicode_AsUTF8` call) instead of the two-crossing generic attribute
 * path (`PyObject_GetAttrString` + a `PyUnicode_AsUTF8`/`toString()` decode).
 * `doc` still goes through `getAttrOrNull("__doc__")`: there is no
 * `PyModule_GetDoc`-equivalent direct accessor in this ABI subset.
 */
open class PyModule(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {

    /** `module.__name__`, backed directly by `PyModule_GetName`. */
    val name: String
        get() = PyModule_GetName(pointer)
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the module's __name__")

    /** `module.__doc__`, or `null` if the module has none. */
    val doc: String?
        get() {
            val docObj = getAttrOrNull("__doc__") ?: return null
            if (PyNone.isNone(docObj)) {
                return null
            }
            return docObj.toString()
        }

    /** `module.__file__`, or `null` for built-in/frozen modules. */
    val file: String?
        get() {
            // PyModule_GetFilenameObject: new reference on success; NULL + AttributeError set
            // if the module has no __file__ (e.g. built-in/frozen modules) -- that failure mode
            // is expected here, so clear the indicator rather than propagate it as an exception.
            val filePtr = PyModule_GetFilenameObject(pointer) ?: run {
                PyErr_Clear()
                return null
            }
            val fileObj = PyObject(filePtr, false)
            if (PyNone.isNone(fileObj)) {
                return null
            }
            return fileObj.toString()
        }

    /** `module.__dict__`: the module's namespace, backed directly by `PyModule_GetDict`. */
    val dict: PyDict
        get() {
            // PyModule_GetDict returns a *borrowed* reference; borrowed = true here makes this
            // PyDict wrapper take its own, independent, incref'd reference to it.
            val dictPtr = PyModule_GetDict(pointer)
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the module's __dict__")
            return PyDict(dictPtr, true)
        }

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
