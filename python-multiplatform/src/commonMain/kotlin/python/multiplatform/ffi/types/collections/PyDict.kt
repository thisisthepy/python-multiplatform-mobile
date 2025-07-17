package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.conversion.PyProxy
import python.native.ffi.NativePointer


open class PyDict(pointer: NativePointer, borrowed: Boolean): PyObject(pointer, borrowed), PyProxy<Boolean>, MutableMap<PyObject, PyObject> {
    override var cachedNativeValue: Boolean?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = TODO("Not yet implemented")
        set(value) {}
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

    fun asNative() {

    }

    fun asPyObject() {

    }

    companion object {
        //fun from(value: Boolean): PyBool {
        //    return if (value) True else False
        //}
    }
}

//fun Boolean.asPyObject(): PyBool = PyBool.from(this)
