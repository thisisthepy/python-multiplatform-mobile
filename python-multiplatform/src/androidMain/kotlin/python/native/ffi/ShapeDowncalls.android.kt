package python.native.ffi

/**
 * Android (ART) actuals for the shape vocabulary declared in `jvmMain/.../ShapeDowncalls.kt`.
 *
 * Each shape is one JNI native-method call into a `@CName`-exported trampoline compiled into
 * `libmultiplatform_python3.13.so` (see `nativeMain/.../EmbedAPI.native.kt`), which casts the
 * `fn` address to a typed `CFunction` pointer and invokes it directly. This is "path 1" from
 * `docs/downcall-design.md`: ordinary JNI -> trampoline -> indirect call. ART cannot synthesize
 * an arbitrary native call at runtime the way Kotlin/Native's cinterop can at compile time, so
 * (unlike desktop, where a single cached `MethodHandle` per shape is built directly in this
 * layer) the actual dispatch mechanism lives in the native trampoline; this file is a thin,
 * zero-logic pass-through to `bindings`. `@CriticalNative`-based ART entry-point patching
 * (path 2, replacing the JNI hop with a direct branch) is out of scope for this change.
 */

internal actual fun downcall_V(fn: Long) = bindings.downcall_V(fn)
internal actual fun downcall_I(fn: Long): Long = bindings.downcall_I(fn)
internal actual fun downcall_F(fn: Long): Double = bindings.downcall_F(fn)
internal actual fun downcallI_V(fn: Long, a0: Long) = bindings.downcallI_V(fn, a0)
internal actual fun downcallI_I(fn: Long, a0: Long): Long = bindings.downcallI_I(fn, a0)
internal actual fun downcallI_F(fn: Long, a0: Long): Double = bindings.downcallI_F(fn, a0)
internal actual fun downcallF_I(fn: Long, a0: Double): Long = bindings.downcallF_I(fn, a0)
internal actual fun downcallII_V(fn: Long, a0: Long, a1: Long) = bindings.downcallII_V(fn, a0, a1)
internal actual fun downcallII_I(fn: Long, a0: Long, a1: Long): Long = bindings.downcallII_I(fn, a0, a1)
internal actual fun downcallIII_V(fn: Long, a0: Long, a1: Long, a2: Long) = bindings.downcallIII_V(fn, a0, a1, a2)
internal actual fun downcallIII_I(fn: Long, a0: Long, a1: Long, a2: Long): Long = bindings.downcallIII_I(fn, a0, a1, a2)
internal actual fun downcallIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long =
    bindings.downcallIIII_I(fn, a0, a1, a2, a3)
internal actual fun downcallIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long): Long =
    bindings.downcallIIIII_I(fn, a0, a1, a2, a3, a4)
internal actual fun downcallIIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long): Long =
    bindings.downcallIIIIII_I(fn, a0, a1, a2, a3, a4, a5)

// ---- Symbol lookup ----

internal actual fun ffiSymbolRaw(name: String): Long = bindings.ffiSymbolRaw(name)

// ---- String marshalling ----

internal actual fun ffiAllocUtf8(str: String): Long = bindings.ffiAllocUtf8(str)

internal actual fun ffiFreeUtf8(ptr: Long) = bindings.ffiFreeUtf8(ptr)

internal actual fun ffiReadUtf8(ptr: Long): String? = bindings.ffiReadUtf8(ptr)
