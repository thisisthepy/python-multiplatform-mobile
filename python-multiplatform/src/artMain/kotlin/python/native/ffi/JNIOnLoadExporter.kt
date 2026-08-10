package python.native.ffi

import kotlinx.cinterop.*
import python.native.ffi.jni.*
import platform.android.*

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("JNI_OnLoad")
fun JNI_OnLoad(vm: CPointer<out CPointed>?, reserved: COpaquePointer?): Int {
    return CustomJNI_OnLoad(vm?.reinterpret(), reserved)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_ffiAllocUtf8")
fun Java_python_native_ffi_bindings_ffiAllocUtf8(
    env: CPointer<JNIEnvVar>?,
    clazz: jclass?,
    str: jstring?
): jlong {
    if (env == null || str == null) return 0L
    val getChars = env.pointed.pointed!!.GetStringUTFChars ?: return 0L
    val cStr = getChars(env, str, null) ?: return 0L
    val kString = cStr.toKString()
    val releaseChars = env.pointed.pointed!!.ReleaseStringUTFChars ?: return 0L
    releaseChars(env, str, cStr)
    return ffiAllocUtf8(kString)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_ffiFreeUtf8")
fun Java_python_native_ffi_bindings_ffiFreeUtf8(
    env: CPointer<JNIEnvVar>?,
    clazz: jclass?,
    ptr: jlong
) {
    ffiFreeUtf8(ptr)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_ffiReadUtf8")
fun Java_python_native_ffi_bindings_ffiReadUtf8(
    env: CPointer<JNIEnvVar>?,
    clazz: jclass?,
    ptr: jlong
): jstring? {
    if (env == null) return null
    val kString = ffiReadUtf8(ptr) ?: return null
    val newStringUTF = env.pointed.pointed!!.NewStringUTF ?: return null
    val scope = Arena()
    val cstr = kString.cstr.getPointer(scope)
    val result = newStringUTF(env, cstr)
    scope.clear()
    return result
}
