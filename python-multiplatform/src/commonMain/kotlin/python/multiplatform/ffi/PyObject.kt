package python.multiplatform.ffi

import python.native.ffi.NativePointer


expect open class PyObject(pointer: NativePointer, borrowed: Boolean) {
    init {
        if (!borrowed) {
            //PyIncRef(pointer)
            // TODO: PyIncRef을 사용하는게 적절한 선택일까?
        }
    }

    val pointer: NativePointer
    protected val typePointer: NativePointer = lazy { PyType_GetType(this.pointer) }
    val Type: PyType = lazy { PyType(typePointer!!) }

    fun incRef() {
        println("hi")
    }

    fun decRef() {
        println("hi")
    }

    @Throws(PyException::class)
    fun getAttr(name: String): PyObject {

    }

    fun getAttrOrNull(name: String): PyObject? {

    }

    @Throws(PyException::class)
    fun setAttr(name: String, value: PyObject) {

    }

    fun setAttrOrNull(name: String, value: PyObject?) {

    }

    @Throws(PyException::class)
    fun delAttr(name: String) {

    }

    fun delAttrOrNull(name: String) {

    }

    override fun toString(): String {
        return PyObject_GetStr(pointer)
    }

    override fun hashCode(): Int {
        // TODO: 같은 포인터 객체는 한번만 생성하도록 해야 함
        return pointer.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is PyObject) {
            return pointer == other.pointer
        }
        return false
    }

    operator fun invoke(arg0: PyObject): PyObject {
        return PyObject_CallObject(pointer, arg0)
    }

    operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject {
        return PyObject_CallObject(pointer, arg0, arg1)
    }

    ...

    operator fun invoke(vararg args: PyObject): PyObject {
        return PyObject_CallObject(pointer, args)  // TODO: 이거는 변수 하나로 잡히던가? 아님 여러개인가?
                                                    // 리스트로 들어오는 거였던가?
    }
}
