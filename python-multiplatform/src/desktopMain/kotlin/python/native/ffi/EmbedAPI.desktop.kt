package python.native.ffi

import jdk.incubator.foreign.MemoryAddress
import jdk.incubator.foreign.ResourceScope


actual inline fun <R : Any> memScoped(block: () -> R): R = ResourceScope.newSharedScope().run {
    return block()
}


@JvmInline
internal value class NativeAddressValue(val ptr: MemoryAddress): AddressValue {
    override fun toString(): String = ptr
        .toString()
        .replace(": ", "=")
        .replace(" offset=", ", offset=")
        .replace("{ ", "(").replace(" }", ")")

}
@HighOverheadNativeCall
actual fun NativePointer.toAddressValue(): AddressValue = NativeAddressValue(toPlatformPointer())
actual inline fun NativePointer.toRawValue(): Long = toPlatformPointer().toRawLongValue()
inline fun NativePointer.toPlatformPointer(): MemoryAddress = this.address as MemoryAddress
@HighOverheadNativeCall
actual fun AddressValue.toNativePointer(): NativePointer = NativePointer((this as NativeAddressValue).ptr)
@HighOverheadNativeCall
actual fun Long.toNativePointer(): NativePointer? = MemoryAddress.ofLong(this).toNativePointer()
internal inline fun MemoryAddress?.toNativePointer(): NativePointer? = this?.let { NativePointer(it) }


// Section 1
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual inline fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
actual inline fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()

// Section 2
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toNativePointer()





actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toNativePointer()
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toPlatformPointer())
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toPlatformPointer())


actual inline fun PyRun_SimpleString(code: String): Int = python.native.ffi.bindings.PyRun_SimpleString(code)


actual fun PyUnicode_FromString(str: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_FromString(str).toNativePointer()
actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? =
    python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer())
