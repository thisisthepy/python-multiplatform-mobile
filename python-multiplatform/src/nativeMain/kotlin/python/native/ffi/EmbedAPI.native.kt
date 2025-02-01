package python.native.ffi

import kotlinx.cinterop.*
import kotlinx.cinterop.memScoped
import python.native.ffi.bindings.PyObject
import kotlin.experimental.ExperimentalNativeApi


@OptIn(ExperimentalForeignApi::class)
actual inline fun <R : Any> memScoped(block: () -> R): R = memScoped {
    return block()
}


@OptIn(ExperimentalForeignApi::class)
internal value class NativeAddressValue<T : CPointed> @OptIn(ExperimentalForeignApi::class) constructor(val ptr: CPointer<T>): AddressValue {
    @OptIn(ExperimentalForeignApi::class)
    override fun toString(): String = "${this::class.simpleName}@${ptr.toLong().toString(16)}"
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
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
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
actual inline fun Py_RunSimpleString(code: String): Int = python.native.ffi.bindings.PyRun_SimpleString(code)
