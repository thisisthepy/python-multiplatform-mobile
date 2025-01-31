package python.native.ffi

import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toLong


@OptIn(ExperimentalForeignApi::class)
actual inline fun <R : Any> memScoped(block: () -> R): R = memScoped {
    return block()
}

@OptIn(ExperimentalForeignApi::class)
actual typealias NativePointer = CPointer<python.native.ffi.bindings.PyObject>

@OptIn(ExperimentalForeignApi::class)
fun NativePointer.toAddress(): Long = this.toLong()


// Android JNI export settings
private const val packageName = "python_native_ffi"
private const val exportClassName = "bindings"
private const val namePrefix = "Java_${packageName}_${exportClassName}_"

// Section 1
@CName("${namePrefix}Py_1Initialize")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
@CName("${namePrefix}Py_1InitializeEx")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
@CName("${namePrefix}Py_1IsInitialized")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
@CName("${namePrefix}Py_1Finalize")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
@CName("${namePrefix}Py_1FinalizeEx")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun Py_FinalizeEx() = python.native.ffi.bindings.Py_FinalizeEx()

// Section 2
//@CName("${namePrefix}PyErr_1Occurred")
//@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
//actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred()
