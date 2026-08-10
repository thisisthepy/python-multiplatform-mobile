package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `dict` object, adopting `MutableMap<PyObject, PyObject>`
 * per the mermaid sketch (`MutableMap <|.. PyDict`).
 *
 * Backed by `PyDict_New`/`PyDict_GetItem`/`PyDict_SetItem`/`PyDict_DelItem`/
 * `PyDict_Contains`/`PyDict_Keys`/`PyDict_Values`/`PyDict_Items`/`PyDict_Clear`.
 *
 * Note: the previous stub declared `PyProxy<Boolean>`, which was almost
 * certainly copy-pasted from [python.multiplatform.ffi.types.basic.PyBool]
 * and never updated -- corrected here to `PyProxy<Map<Any?, Any?>>`, the
 * natural "fully native" projection of a dict.
 */
open class PyDict(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<Map<Any?, Any?>>, MutableMap<PyObject, PyObject> {

    companion object {
        /** The `PyType` for `dict` (`builtins.dict`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Builds a new Python `dict` from [map], preserving key/value object identity. */
        fun fromMap(map: Map<PyObject, PyObject>): PyDict {
            TODO("Not yet implemented")
        }
    }

    override var cachedNativeValue: Map<Any?, Any?>?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}

    /** Snapshot conversion to a plain Kotlin map, recursively converting keys/values ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]). */
    fun toNativeMap(): Map<Any?, Any?> = TODO("Not yet implemented")

    override val entries: MutableSet<MutableMap.MutableEntry<PyObject, PyObject>>
        get() = TODO("Not yet implemented")
    override val keys: MutableSet<PyObject>
        get() = TODO("Not yet implemented")
    override val size: Int
        get() = TODO("Not yet implemented")
    override val values: MutableCollection<PyObject>
        get() = TODO("Not yet implemented")

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun containsKey(key: PyObject): Boolean {
        TODO("Not yet implemented")
    }

    override fun containsValue(value: PyObject): Boolean {
        TODO("Not yet implemented")
    }

    override fun get(key: PyObject): PyObject? {
        TODO("Not yet implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun put(key: PyObject, value: PyObject): PyObject? {
        TODO("Not yet implemented")
    }

    override fun putAll(from: Map<out PyObject, PyObject>) {
        TODO("Not yet implemented")
    }

    override fun remove(key: PyObject): PyObject? {
        TODO("Not yet implemented")
    }
}
