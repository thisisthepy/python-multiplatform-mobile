package python.native.ffi

import kotlinx.cinterop.*
import kotlinx.cinterop.memScoped
import platform.posix.getenv
import platform.posix.setenv
import python.native.ffi.bindings.PyObject
import kotlin.experimental.ExperimentalNativeApi


@OptIn(ExperimentalForeignApi::class)
actual inline fun <R : Any> memScoped(block: () -> R): R = memScoped {
    return block()
}


@OptIn(ExperimentalForeignApi::class)
internal value class NativeAddressValue<T : CPointed> @OptIn(ExperimentalForeignApi::class) constructor(val ptr: CPointer<T>): AddressValue {
    @OptIn(ExperimentalForeignApi::class)
    override fun toString(): String = ptr.toString()
}
@HighOverheadNativeCall
@OptIn(ExperimentalForeignApi::class)
actual fun NativePointer.toAddressValue(): AddressValue = NativeAddressValue(toPlatformPointer())
@OptIn(ExperimentalForeignApi::class)
actual inline fun NativePointer.toRawValue(): Long = toPlatformPointer<CPointed>().toLong()
@OptIn(ExperimentalForeignApi::class)
inline fun <T : CPointed> NativePointer.toPlatformPointer(): CPointer<T> = this.address as CPointer<T>
@HighOverheadNativeCall
@OptIn(ExperimentalForeignApi::class)
actual fun AddressValue.toNativePointer(): NativePointer = NativePointer((this as NativeAddressValue<*>).ptr)
@HighOverheadNativeCall
@OptIn(ExperimentalForeignApi::class)
actual fun Long.toNativePointer(): NativePointer? = toCPointer<CPointed>()?.let { NativePointer(it) }
@OptIn(ExperimentalForeignApi::class)
internal inline fun CPointer<PyObject>?.toNativePointer(): NativePointer? = this?.let { NativePointer(it) }


// Android JNI export settings
private const val packageName = "python_native_ffi"
private const val exportClassName = "bindings"
private const val namePrefix = "Java_${packageName}_${exportClassName}_"


// Section 1
@CName("${namePrefix}Py_1Initialize")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_Initialize() {
    println("Native Python Home: ${getenv("PYTHONHOME")?.toKString()}")  // TODO: Remove Debugging line
    python.native.ffi.bindings.Py_Initialize()
}
@CName("${namePrefix}Py_1InitializeEx")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
@CName("${namePrefix}Py_1IsInitialized")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
@CName("${namePrefix}Py_1Finalize")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
@CName("${namePrefix}Py_1FinalizeEx")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_FinalizeEx() = python.native.ffi.bindings.Py_FinalizeEx()
@CName("${namePrefix}Py_1IsFinalizing")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_IsFinalizing() = python.native.ffi.bindings.Py_IsFinalizing()
@CName("${namePrefix}Py_1BytesMain")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_BytesMain(args: Array<String>) = memScoped {
    val cArgs = allocArray<CPointerVar<ByteVar>>(args.size + 1)
    args.forEachIndexed { index, arg -> cArgs[index] = arg.cstr.ptr }
    cArgs[args.size] = null
    python.native.ffi.bindings.Py_BytesMain(args.size, cArgs)
}
@CName("${namePrefix}Py_1RunMain")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_RunMain() = python.native.ffi.bindings.Py_RunMain()
@CName("${namePrefix}PyRun_1SimpleString")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun PyRun_SimpleString(command: String): Int = python.native.ffi.bindings.PyRun_SimpleString(command)
@CName("${namePrefix}PyRun_1String")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun PyRun_String(
    str: String, start: Int, globals: NativePointer, locals: NativePointer
): NativePointer? = python.native.ffi.bindings.PyRun_String(
    str, start, globals.toPlatformPointer(), locals.toPlatformPointer()
).toNativePointer()
@CName("${namePrefix}Py_1GetVersion")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_GetVersion(): String? = python.native.ffi.bindings.Py_GetVersion()?.toKString()
@CName("${namePrefix}Py_1GetPlatform")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_GetPlatform(): String? = python.native.ffi.bindings.Py_GetPlatform()?.toKString()
@CName("${namePrefix}Py_1GetCopyright")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_GetCopyright(): String? = python.native.ffi.bindings.Py_GetCopyright()?.toKString()
@CName("${namePrefix}Py_1GetCompiler")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_GetCompiler(): String? = python.native.ffi.bindings.Py_GetCompiler()?.toKString()
@CName("${namePrefix}Py_1GetBuildInfo")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual inline fun Py_GetBuildInfo(): String? = python.native.ffi.bindings.Py_GetBuildInfo()?.toKString()


// Section 2
@CName("${namePrefix}PyErr_1Occurred")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toNativePointer()



@OptIn(ExperimentalForeignApi::class)
actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toNativePointer()
@OptIn(ExperimentalForeignApi::class)
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toPlatformPointer())
@OptIn(ExperimentalForeignApi::class)
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toPlatformPointer())





@OptIn(ExperimentalForeignApi::class)
actual fun PyUnicode_FromString(str: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_FromString(str).toNativePointer()
@OptIn(ExperimentalForeignApi::class)
actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? =
    python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer())?.toKString()

