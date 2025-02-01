package python.native.ffi

import kotlinx.cinterop.*
import kotlinx.cinterop.memScoped
import python.native.ffi.bindings.PyObject
import kotlin.experimental.ExperimentalNativeApi


@OptIn(ExperimentalForeignApi::class)
actual inline fun <R : Any> memScoped(block: () -> R): R = memScoped {
    return block()
}

value class NativePlatformPointer @OptIn(ExperimentalForeignApi::class) constructor(val address: CPointer<*>): NativePointer3 {
    @OptIn(ExperimentalForeignApi::class)
    override inline fun toRawValue(): Long = address.toLong()
}
@OptIn(ExperimentalForeignApi::class)
actual inline fun Long.toNativePointer(): NativePointer3? = this.toCPointer<PyObject>() ?.let { NativePlatformPointer(it) }
@OptIn(ExperimentalForeignApi::class)
inline fun <T : CPointed> Long.toNativePointer(): NativePointer3? = this.toCPointer<T>() ?.let { NativePlatformPointer(it) }

@OptIn(ExperimentalForeignApi::class)
actual inline fun NativePointer2.toPlatformPointer(): Any? = this.rawValue.toCPointer<PyObject>()
@OptIn(ExperimentalForeignApi::class)
inline fun NativePointer2.toTypedPlatformPointer(): CPointer<*>? = this.rawValue.toCPointer<PyObject>()
@OptIn(ExperimentalForeignApi::class)
inline fun <T : CPointed> NativePointer2.toTypedPlatformPointer(): CPointer<*>? = this.rawValue.toCPointer<T>()
actual inline fun nativePointer2Of(address: Any): NativePointer2? = address.toLong().let { NativePointer2(it) }

@OptIn(ExperimentalForeignApi::class)
internal actual inline fun fromAddress(address: Any?, silent: Boolean, escalateIntoException: Boolean): NativePointer? {
    return when (address) {
        is CPointer<*> -> NativePointer(address)
        is Number -> address.toLong().toCPointer<PyObject>()?.let { NativePointer(it) }
        else -> {
            if (address != null) {
                val warningObject = IncompatiblePointerConversionException(address, escalated = false)
                if (!silent) {
                    warningObject.printStackTrace()
                }
                if (escalateIntoException) {
                    warningObject.escalate()
                    throw warningObject
                }
            }

            null
        }
    }
}
@OptIn(ExperimentalForeignApi::class)
actual inline fun NativePointer.toRawLongValue(): Long = (this.address as CPointer<PyObject>).toLong()
actual inline fun NativePointer.toPlatformPointer(): Any = this.address
@OptIn(ExperimentalForeignApi::class)
inline fun NativePointer.toTypedPlatformPointer(): CPointer<PyObject> = this.address as CPointer<PyObject>
@OptIn(ExperimentalForeignApi::class)
internal inline fun CPointer<PyObject>?.toPyPointer(): NativePointer? = this?.let { NativePointer(it) }


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
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toPyPointer()



@OptIn(ExperimentalForeignApi::class)
actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toPyPointer()
@OptIn(ExperimentalForeignApi::class)
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toTypedPlatformPointer())
@OptIn(ExperimentalForeignApi::class)
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toTypedPlatformPointer())



@OptIn(ExperimentalForeignApi::class)
actual inline fun Py_RunSimpleString(code: String): Int = python.native.ffi.bindings.PyRun_SimpleString(code)
