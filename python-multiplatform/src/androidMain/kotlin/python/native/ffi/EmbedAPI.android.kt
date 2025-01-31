package python.native.ffi


actual inline fun <R : Any> memScoped(block: () -> R): R = block()

actual typealias NativePointer = Long

actual fun NativePointer.toAddress(): Long = this


// Section 1
actual fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
actual fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
actual fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
actual fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
actual fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()

// Section 2
//actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred()
