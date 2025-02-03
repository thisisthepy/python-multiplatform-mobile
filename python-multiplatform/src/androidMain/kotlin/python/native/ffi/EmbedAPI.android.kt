package python.native.ffi


actual inline fun <R : Any> memScoped(block: () -> R): R = block()


@JvmInline
internal value class NativeAddressValue(val ptr: JNIPointer): AddressValue {
    override fun toString(): String = "JNIPointer(raw=0x${ptr.toString(16)})"
}
@HighOverheadNativeCall
actual fun NativePointer.toAddressValue(): AddressValue = NativeAddressValue(toPlatformPointer())
actual inline fun NativePointer.toRawValue(): Long = toPlatformPointer()
inline fun NativePointer.toPlatformPointer(): JNIPointer = this.address as JNIPointer
@HighOverheadNativeCall
actual fun AddressValue.toNativePointer(): NativePointer = NativePointer((this as NativeAddressValue).ptr)
@HighOverheadNativeCall
actual fun Long.toNativePointer(): NativePointer? = toNativePointer()
internal inline fun JNIPointer?.toNativePointer(): NativePointer? = this?.let { if (it > 0) NativePointer(it) else null }


// Section 1
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
actual inline fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()

// Section 2
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toNativePointer()






actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toNativePointer()
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toPlatformPointer())
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toPlatformPointer())


actual inline fun Py_RunSimpleString(code: String): Int = python.native.ffi.bindings.PyRun_SimpleString(code)
