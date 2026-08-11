@file:OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
package python.multiplatform.ref

actual fun forceGC() {
    kotlin.native.runtime.GC.collect()
}
