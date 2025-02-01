package python.native.ffi

import jdk.incubator.foreign.MemoryAddress
import jdk.incubator.foreign.ResourceScope


actual inline fun <R : Any> memScoped(block: () -> R): R = ResourceScope.newSharedScope().run {
    return block()
}

@JvmInline
value class NativePlatformPointer(val address: MemoryAddress): NativePointer3 {
    override inline fun toRawValue(): Long = address.toRawLongValue()
}
actual inline fun Long.toNativePointer(): NativePointer3? = NativePlatformPointer(MemoryAddress.ofLong(this))

actual inline fun NativePointer2.toPlatformPointer(): Any? = this.rawValue
inline fun NativePointer2.toTypedPlatformPointer(): MemoryAddress? = MemoryAddress.ofLong(this.rawValue)

internal actual inline fun fromAddress(address: Any?, silent: Boolean, escalateIntoException: Boolean): NativePointer? {
    return when (address) {
        is MemoryAddress -> NativePointer(address)
        is Number -> MemoryAddress.ofLong(address.toLong())?.let { NativePointer(it) }
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
actual inline fun NativePointer.toRawLongValue(): Long = (this.address as MemoryAddress).toRawLongValue()
actual inline fun NativePointer.toPlatformPointer(): Any = this.address
inline fun NativePointer.toTypedPlatformPointer(): MemoryAddress = this.address as MemoryAddress
internal inline fun MemoryAddress?.toPyPointer(): NativePointer? = this?.let { NativePointer(it) }


// Section 1
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual inline fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
actual inline fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()

// Section 2
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toPyPointer()





actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toPyPointer()
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toTypedPlatformPointer())
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toTypedPlatformPointer())


actual inline fun Py_RunSimpleString(code: String): Int = python.native.ffi.bindings.PyRun_SimpleString(code)
