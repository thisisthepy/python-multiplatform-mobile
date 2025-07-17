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
actual inline fun Py_IsFinalizing() = python.native.ffi.bindings.Py_IsFinalizing()
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()
actual inline fun Py_RunMain(): Int = python.native.ffi.bindings.Py_RunMain()
actual inline fun PyRun_SimpleString(command: String): Int = python.native.ffi.bindings.PyRun_SimpleString(command)
actual fun PyRun_String(
    str: String, start: Int, globals: NativePointer, locals: NativePointer
): NativePointer? = python.native.ffi.bindings.PyRun_String(
    str, start, globals.toPlatformPointer(), locals.toPlatformPointer()
).toNativePointer()
actual inline fun Py_GetVersion(): String? = python.native.ffi.bindings.Py_GetVersion()
actual inline fun Py_GetPlatform(): String? = python.native.ffi.bindings.Py_GetPlatform()
actual inline fun Py_GetCopyright(): String? = python.native.ffi.bindings.Py_GetCopyright()
actual inline fun Py_GetCompiler(): String? = python.native.ffi.bindings.Py_GetCompiler()
actual inline fun Py_GetBuildInfo(): String? = python.native.ffi.bindings.Py_GetBuildInfo()


// Section 2
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toNativePointer()





actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toNativePointer()
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toPlatformPointer())
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toPlatformPointer())



actual fun PyUnicode_FromString(str: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_FromString(str).toNativePointer()
actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? =
    python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer())
