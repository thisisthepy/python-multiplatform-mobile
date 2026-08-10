package python.native.ffi

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * The three CPython Stable ABI return kinds relevant to the shape vocabulary: no value,
 * a `long`-sized integer/pointer, or a `double`. See `docs/downcall-design.md`.
 */
internal enum class ReturnKind { VOID, LONG, DOUBLE }

/**
 * JDK-version-agnostic Panama FFI abstraction.
 *
 * Reaches `java.lang.foreign` (JDK 19+) or `jdk.incubator.foreign` (JDK 16-18)
 * entirely through reflection, so this file compiles on any JDK >= 16 without
 * needing either module at compile time.
 *
 * Every reflective lookup is cached once at init; hot-path calls go through
 * cached MethodHandles with zero reflective overhead.
 */
@PublishedApi
internal object PanamaBackend {

    // ---- public surface used by bindings.kt ----

    /** Pointer type carrier for MethodType signatures — always Long. */
    val POINTER_TYPE: Class<*> = Long::class.javaPrimitiveType!!

    /** The null-pointer sentinel as a Long. */
    const val NULL: Long = 0L

    /** Create a native C string from a Kotlin String and return its address as Long. */
    val allocateUtf8String: (String) -> Long

    /** Read a null-terminated C string at the given address and return as Kotlin String?. */
    val readUtf8String: (Long) -> String?

    /** Look up a symbol in the loaded libraries and create a downcall handle. */
    val findSymbol: (String, Class<*>, Array<Class<*>>) -> MethodHandle

    /** Resolve [name] to its raw process address without building a downcall handle around
     *  it. Returns `0L` if the symbol is not found (does not throw). Used for the shape
     *  vocabulary's symbol lookup (`ffiSymbolRaw` in `jvmMain`), where the caller passes
     *  the address as the leading argument to a shape trampoline instead of binding a
     *  dedicated handle per CPython function. */
    val findSymbolAddress: (String) -> Long

    /** Allocate a new null-terminated UTF-8 buffer backed by native `malloc`, independent
     *  of any [java.lang.foreign.Arena]. Unlike [allocateUtf8String] (which allocates from
     *  the global arena and is never freed -- acceptable only because the existing 305
     *  call sites are one-shot, short-lived strings), a buffer from this function **must**
     *  be released with [freeUtf8Address] by the caller. See `ShapeDowncalls.kt` for the
     *  `withUtf8` helper that makes leaking one hard. */
    val allocateUtf8Freeable: (String) -> Long

    /** Release a buffer obtained from [allocateUtf8Freeable]. Passing any other address
     *  (e.g. a CPython-owned `const char*`) is undefined behaviour, same as misusing `free()`. */
    val freeUtf8Address: (Long) -> Unit

    /** One `MethodHandle` per ABI shape, **unbound** to any specific target function: its
     *  leading parameter is the callee's address (as a plain `long`, adapted from
     *  `MemorySegment`/`MemoryAddress` so the handle's static Java type is exactly
     *  `(long, long..., double...) -> long/double/void` with no boxing). Built once per
     *  shape by the caller (`ShapeDowncalls.desktop.kt`) and reused for every CPython
     *  function of that shape -- this is what makes `invokeExact` reachable: the call
     *  site's static signature and the handle's type are fixed and identical. */
    val unboundDowncallHandle: (intArgs: Int, floatArgs: Int, returnKind: ReturnKind) -> MethodHandle

    // ---- internals ----

    private enum class Backend { MODERN, INCUBATOR }

    init {
        val (backend, implData) = detectBackend()
        when (backend) {
            Backend.MODERN -> {
                val data = implData as ModernData
                allocateUtf8String = data.allocateUtf8String
                readUtf8String = data.readUtf8String
                findSymbol = data.findSymbol
                findSymbolAddress = data.findSymbolAddress
                allocateUtf8Freeable = data.allocateUtf8Freeable
                freeUtf8Address = data.freeUtf8Address
                unboundDowncallHandle = data.unboundDowncallHandle
            }
            Backend.INCUBATOR -> {
                val data = implData as IncubatorData
                allocateUtf8String = data.allocateUtf8String
                readUtf8String = data.readUtf8String
                findSymbol = data.findSymbol
                findSymbolAddress = data.findSymbolAddress
                allocateUtf8Freeable = data.allocateUtf8Freeable
                freeUtf8Address = data.freeUtf8Address
                unboundDowncallHandle = data.unboundDowncallHandle
            }
        }
    }

    // ---- Modern backend: java.lang.foreign (JDK 19+ preview, 22+ final) ----

    private class ModernData(
        val allocateUtf8String: (String) -> Long,
        val readUtf8String: (Long) -> String?,
        val findSymbol: (String, Class<*>, Array<Class<*>>) -> MethodHandle,
        val findSymbolAddress: (String) -> Long,
        val allocateUtf8Freeable: (String) -> Long,
        val freeUtf8Address: (Long) -> Unit,
        val unboundDowncallHandle: (Int, Int, ReturnKind) -> MethodHandle
    )

    private fun initModern(): ModernData {
        val lookup = MethodHandles.lookup()

        // Core classes
        val arenaClass = Class.forName("java.lang.foreign.Arena")
        val memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment")
        val linkerClass = Class.forName("java.lang.foreign.Linker")
        val symbolLookupClass = Class.forName("java.lang.foreign.SymbolLookup")
        val functionDescriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor")
        val valueLazyoutClass = Class.forName("java.lang.foreign.ValueLayout")
        val memoryLayoutClass = Class.forName("java.lang.foreign.MemoryLayout")

        // ValueLayout constants
        val javaInt = valueLazyoutClass.getField("JAVA_INT").get(null)
        val javaLong = valueLazyoutClass.getField("JAVA_LONG").get(null)
        val javaFloat = valueLazyoutClass.getField("JAVA_FLOAT").get(null)
        val javaDouble = valueLazyoutClass.getField("JAVA_DOUBLE").get(null)
        val javaByte = valueLazyoutClass.getField("JAVA_BYTE").get(null)
        val javaShort = valueLazyoutClass.getField("JAVA_SHORT").get(null)
        val address = valueLazyoutClass.getField("ADDRESS").get(null)

        // Arena.global()
        val globalArena = arenaClass.getMethod("global").invoke(null)

        // MemorySegment.NULL
        val nullSegment = memorySegmentClass.getField("NULL").get(null)

        // Arena.allocateFrom(String) (JDK 22+) or allocateUtf8String (JDK 19-21)
        val allocateFromMH = try {
            lookup.findVirtual(
                arenaClass, "allocateFrom",
                MethodType.methodType(memorySegmentClass, String::class.java)
            )
        } catch (e: Exception) {
            lookup.findVirtual(
                arenaClass, "allocateUtf8String",
                MethodType.methodType(memorySegmentClass, String::class.java)
            )
        }

        // MemorySegment.address()
        val segmentAddressMH = lookup.findVirtual(
            memorySegmentClass, "address",
            MethodType.methodType(Long::class.javaPrimitiveType)
        )

        // MemorySegment.reinterpret(long)
        val reinterpretMH = lookup.findVirtual(
            memorySegmentClass, "reinterpret",
            MethodType.methodType(memorySegmentClass, Long::class.javaPrimitiveType)
        )

        // MemorySegment.getString(long) (JDK 22+) or getUtf8String (JDK 19-21)
        val getStringMH = try {
            lookup.findVirtual(
                memorySegmentClass, "getString",
                MethodType.methodType(String::class.java, Long::class.javaPrimitiveType)
            )
        } catch (e: Exception) {
            lookup.findVirtual(
                memorySegmentClass, "getUtf8String",
                MethodType.methodType(String::class.java, Long::class.javaPrimitiveType)
            )
        }

        // MemorySegment.ofAddress(long)
        val ofAddressMH = memorySegmentClass.getMethod("ofAddress", Long::class.javaPrimitiveType!!)

        // Linker.nativeLinker()
        val linker = linkerClass.getMethod("nativeLinker").invoke(null)

        // Linker.defaultLookup()
        val defaultLookup = linkerClass.getMethod("defaultLookup").invoke(linker)

        // SymbolLookup.loaderLookup()
        val loaderLookup = symbolLookupClass.getMethod("loaderLookup").invoke(null)

        // Chain: loaderLookup.or(defaultLookup)
        val orMethod = symbolLookupClass.getMethod("or", symbolLookupClass)
        val combinedLookup = orMethod.invoke(loaderLookup, defaultLookup)

        // SymbolLookup.find(String) -> Optional<MemorySegment>
        val lookupFindMH = lookup.findVirtual(
            symbolLookupClass, "find",
            MethodType.methodType(java.util.Optional::class.java, String::class.java)
        )

        // Linker.downcallHandle(MemorySegment, FunctionDescriptor, Linker.Option...)
        val optionArrayClass = Class.forName("java.lang.foreign.Linker\$Option")
        val optionArrayType = java.lang.reflect.Array.newInstance(optionArrayClass, 0).javaClass
        val downcallHandleMethod = linkerClass.getMethod(
            "downcallHandle", memorySegmentClass, functionDescriptorClass, optionArrayType
        )
        val emptyOptions = java.lang.reflect.Array.newInstance(optionArrayClass, 0)

        val layoutArrayClass = java.lang.reflect.Array.newInstance(memoryLayoutClass, 0).javaClass
        val fdOfMethod = functionDescriptorClass.getMethod(
            "of", memoryLayoutClass, layoutArrayClass
        )
        val fdOfVoidMethod = functionDescriptorClass.getMethod(
            "ofVoid", java.lang.reflect.Array.newInstance(memoryLayoutClass, 0).javaClass
        )

        fun classToLayout(cls: Class<*>): Any = when (cls) {
            Byte::class.javaPrimitiveType -> javaByte
            Short::class.javaPrimitiveType -> javaShort
            Int::class.javaPrimitiveType -> javaInt
            Long::class.javaPrimitiveType -> javaLong
            Float::class.javaPrimitiveType -> javaFloat
            Double::class.javaPrimitiveType -> javaDouble
            else -> throw IllegalArgumentException("Unsupported type: $cls")
        }

        val allocStr: (String) -> Long = { str ->
            val seg = allocateFromMH.invoke(globalArena, str)
            segmentAddressMH.invoke(seg) as Long
        }

        val readStr: (Long) -> String? = { addr ->
            if (addr == 0L) null
            else {
                val seg = ofAddressMH.invoke(null, addr) as Any
                val bigSeg = reinterpretMH.invoke(seg, Long.MAX_VALUE)
                getStringMH.invoke(bigSeg, 0L) as String
            }
        }

        val findSym: (String, Class<*>, Array<Class<*>>) -> MethodHandle = { name, retType, paramTypes ->
            val optSeg = lookupFindMH.invoke(combinedLookup, name) as java.util.Optional<*>
            val seg = optSeg.orElseThrow {
                UnsatisfiedLinkError("Symbol not found: $name")
            }

            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, paramTypes.size)
            val mtParams = mutableListOf<Class<*>>()
            for (i in paramTypes.indices) {
                val p = paramTypes[i]
                if (p == POINTER_TYPE) {
                    java.lang.reflect.Array.set(layoutParams, i, address)
                    mtParams.add(memorySegmentClass)
                } else {
                    java.lang.reflect.Array.set(layoutParams, i, classToLayout(p))
                    mtParams.add(p)
                }
            }

            val fd = if (retType == Void.TYPE) {
                fdOfVoidMethod.invoke(null, layoutParams)
            } else if (retType == POINTER_TYPE) {
                fdOfMethod.invoke(null, address, layoutParams)
            } else {
                fdOfMethod.invoke(null, classToLayout(retType), layoutParams)
            }

            val mtRet = if (retType == POINTER_TYPE) memorySegmentClass
                        else if (retType == Void.TYPE) Void.TYPE
                        else retType

            val rawHandle = downcallHandleMethod.invoke(
                linker, seg, fd, emptyOptions
            ) as MethodHandle

            // Adapt: replace MemorySegment params with long, MemorySegment returns with long
            adaptModernHandle(rawHandle, retType, paramTypes, memorySegmentClass,
                ofAddressMH, segmentAddressMH, nullSegment)
        }

        // ---- Symbol address lookup (no downcall handle built) ----

        val findAddr: (String) -> Long = { name ->
            val optSeg = lookupFindMH.invoke(combinedLookup, name) as java.util.Optional<*>
            if (optSeg.isPresent) segmentAddressMH.invoke(optSeg.get()) as Long else 0L
        }

        // ---- Freeable UTF-8 strings, backed by native malloc/free ----

        val mallocHandle = findSym("malloc", POINTER_TYPE, arrayOf(POINTER_TYPE))
        val freeHandle = findSym("free", Void.TYPE, arrayOf(POINTER_TYPE))
        val ofArrayMethod = memorySegmentClass.getMethod("ofArray", ByteArray::class.java)
        val copyMethod = memorySegmentClass.getMethod(
            "copy", memorySegmentClass, Long::class.javaPrimitiveType, memorySegmentClass,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )

        val allocFreeable: (String) -> Long = { str ->
            val bytes = str.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val len = bytes.size.toLong()
            val addr = mallocHandle.invoke(len) as Long
            if (addr == 0L) throw OutOfMemoryError("native malloc failed for UTF-8 string of length $len")
            val srcSeg = ofArrayMethod.invoke(null, bytes)
            val dstSeg = reinterpretMH.invoke(ofAddressMH.invoke(null, addr), len)
            copyMethod.invoke(null, srcSeg, 0L, dstSeg, 0L, len)
            addr
        }

        val freeAddr: (Long) -> Unit = { addr ->
            if (addr != 0L) {
                freeHandle.invoke(addr)
                Unit
            }
        }

        // ---- Shape vocabulary: unbound downcall handles, one per (intArgs, floatArgs, returnKind) ----

        val downcallHandle2Method = linkerClass.getMethod("downcallHandle", functionDescriptorClass, optionArrayType)

        // Filter that converts an incoming `long` into the `MemorySegment` a raw downcall
        // handle's leading (target-address) parameter requires. filterArguments requires the
        // filter's return type to be identical to the target's parameter type at that
        // position, so asType() is used to give the (Any-returning) static adapter method the
        // exact reflectively-obtained MemorySegment return type.
        val longToSegmentExact = lookup.findStatic(
            PanamaBackend::class.java, "modernLongToSegment",
            MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
        ).asType(MethodType.methodType(memorySegmentClass, Long::class.javaPrimitiveType))

        val buildShape: (Int, Int, ReturnKind) -> MethodHandle = { intArgs, floatArgs, returnKind ->
            val total = intArgs + floatArgs
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, total)
            for (i in 0 until intArgs) java.lang.reflect.Array.set(layoutParams, i, javaLong)
            for (i in 0 until floatArgs) java.lang.reflect.Array.set(layoutParams, intArgs + i, javaDouble)

            val fd = when (returnKind) {
                ReturnKind.VOID -> fdOfVoidMethod.invoke(null, layoutParams)
                ReturnKind.LONG -> fdOfMethod.invoke(null, javaLong, layoutParams)
                ReturnKind.DOUBLE -> fdOfMethod.invoke(null, javaDouble, layoutParams)
            }

            val raw = downcallHandle2Method.invoke(linker, fd, emptyOptions) as MethodHandle
            MethodHandles.filterArguments(raw, 0, longToSegmentExact)
        }

        return ModernData(allocStr, readStr, findSym, findAddr, allocFreeable, freeAddr, buildShape)
    }

    private fun adaptModernHandle(
        raw: MethodHandle, retType: Class<*>, paramTypes: Array<Class<*>>,
        segmentClass: Class<*>,
        ofAddressMH: java.lang.reflect.Method,
        segmentAddressMH: MethodHandle,
        nullSegment: Any
    ): MethodHandle {
        var h = raw
        val lookup = MethodHandles.lookup()

        // Adapt return: MemorySegment -> long
        if (retType == POINTER_TYPE) {
            // raw returns MemorySegment, we want long
            // Insert an adapter: (MemorySegment) -> long via address()
            val segToLong = lookup.findStatic(
                PanamaBackend::class.java, "modernSegmentToLong",
                MethodType.methodType(Long::class.javaPrimitiveType, Any::class.java)
            ).asType(MethodType.methodType(Long::class.javaPrimitiveType, segmentClass))
            h = MethodHandles.filterReturnValue(h, segToLong)
        }

        // Adapt params: long -> MemorySegment for pointer positions
        for (i in paramTypes.indices) {
            if (paramTypes[i] == POINTER_TYPE) {
                val longToSeg = lookup.findStatic(
                    PanamaBackend::class.java, "modernLongToSegment",
                    MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
                ).asType(MethodType.methodType(segmentClass, Long::class.javaPrimitiveType))
                // The parameter index in the adapted handle: account for already-adapted params
                h = MethodHandles.filterArguments(h, i, longToSeg)
            }
        }

        return h
    }

    // Static adapters called via MethodHandle — must use Any to avoid importing MemorySegment
    @JvmStatic
    fun modernSegmentToLong(seg: Any?): Long {
        if (seg == null) return 0L
        return try {
            val cls = Class.forName("java.lang.foreign.MemorySegment")
            val m = cls.getMethod("address")
            m.invoke(seg) as Long
        } catch (e: Exception) { 
            e.printStackTrace()
            0L 
        }
    }

    @JvmStatic
    fun modernLongToSegment(addr: Long): Any {
        val cls = Class.forName("java.lang.foreign.MemorySegment")
        val m = cls.getMethod("ofAddress", Long::class.javaPrimitiveType!!)
        return m.invoke(null, addr)
    }

    // ---- Incubator backend: jdk.incubator.foreign (JDK 16-18) ----

    private class IncubatorData(
        val allocateUtf8String: (String) -> Long,
        val readUtf8String: (Long) -> String?,
        val findSymbol: (String, Class<*>, Array<Class<*>>) -> MethodHandle,
        val findSymbolAddress: (String) -> Long,
        val allocateUtf8Freeable: (String) -> Long,
        val freeUtf8Address: (Long) -> Unit,
        val unboundDowncallHandle: (Int, Int, ReturnKind) -> MethodHandle
    )

    private fun initIncubator(): IncubatorData {
        val lookup = MethodHandles.lookup()

        val clinkerClass = Class.forName("jdk.incubator.foreign.CLinker")
        val memoryAddressClass = Class.forName("jdk.incubator.foreign.MemoryAddress")
        val memoryScopeClass = Class.forName("jdk.incubator.foreign.ResourceScope")
        val symbolLookupClass = Class.forName("jdk.incubator.foreign.SymbolLookup")
        val functionDescriptorClass = Class.forName("jdk.incubator.foreign.FunctionDescriptor")
        val memoryLayoutClass = Class.forName("jdk.incubator.foreign.MemoryLayout")
        val valueLazyoutClass: Class<*>
        try {
            valueLazyoutClass = Class.forName("jdk.incubator.foreign.ValueLayout")
        } catch (_: ClassNotFoundException) {
            // JDK 16 uses CLinker constants directly
            throw UnsupportedOperationException("JDK 16 incubator not yet supported")
        }

        // CLinker.getInstance()
        val clinker = clinkerClass.getMethod("getInstance").invoke(null)

        // CLinker layout constants
        val cInt = clinkerClass.getField("C_INT").get(null)
        val cLongLong = clinkerClass.getField("C_LONG_LONG").get(null)
        val cFloat = clinkerClass.getField("C_FLOAT").get(null)
        val cDouble = clinkerClass.getField("C_DOUBLE").get(null)
        val cPointer = clinkerClass.getField("C_POINTER").get(null)
        val cChar = clinkerClass.getField("C_CHAR").get(null)
        val cShort = clinkerClass.getField("C_SHORT").get(null)

        // ResourceScope.newConfinedScope()
        val newScopeMethod = memoryScopeClass.getMethod("newConfinedScope")

        // CLinker.toCString(String, ResourceScope)
        val toCStringMethod = clinkerClass.getMethod("toCString", String::class.java, memoryScopeClass)

        // CLinker.toJavaString(MemorySegment)
        val memorySegmentClass = Class.forName("jdk.incubator.foreign.MemorySegment")
        val toJavaStringMethod = clinkerClass.getMethod("toJavaString", memorySegmentClass)

        // MemorySegment.address() -> MemoryAddress
        val segAddressMethod = memorySegmentClass.getMethod("address")

        // MemoryAddress.toRawLongValue()
        val toRawLongMethod = memoryAddressClass.getMethod("toRawLongValue")

        // MemoryAddress.ofLong(long)
        val ofLongMethod = memoryAddressClass.getMethod("ofLong", Long::class.javaPrimitiveType!!)

        // SymbolLookup.loaderLookup()
        val loaderLookup = symbolLookupClass.getMethod("loaderLookup").invoke(null)

        // SymbolLookup.lookup(String) -> Optional<MemoryAddress>
        val lookupMethod = symbolLookupClass.getMethod("lookup", String::class.java)

        // CLinker.downcallHandle(MemoryAddress, MethodType, FunctionDescriptor)
        val downcallMethod = clinkerClass.getMethod(
            "downcallHandle", memoryAddressClass,
            MethodType::class.java, functionDescriptorClass
        )

        // FunctionDescriptor.of / ofVoid
        val layoutArrayClass = java.lang.reflect.Array.newInstance(memoryLayoutClass, 0).javaClass
        val fdOfMethod = functionDescriptorClass.getMethod("of", memoryLayoutClass, layoutArrayClass)
        val fdOfVoidMethod = functionDescriptorClass.getMethod("ofVoid", layoutArrayClass)

        fun classToLayout(cls: Class<*>): Any = when (cls) {
            Byte::class.javaPrimitiveType -> cChar
            Short::class.javaPrimitiveType -> cShort
            Int::class.javaPrimitiveType -> cInt
            Long::class.javaPrimitiveType -> cLongLong
            Float::class.javaPrimitiveType -> cFloat
            Double::class.javaPrimitiveType -> cDouble
            else -> throw IllegalArgumentException("Unsupported type: $cls")
        }

        val allocStr: (String) -> Long = { str ->
            val scope = newScopeMethod.invoke(null)
            val seg = toCStringMethod.invoke(null, str, scope)
            val addr = segAddressMethod.invoke(seg)
            toRawLongMethod.invoke(addr) as Long
        }

        val readStr: (Long) -> String? = { addr ->
            if (addr == 0L) null
            else {
                val memAddr = ofLongMethod.invoke(null, addr)
                // MemoryAddress implements MemorySegment in the incubator
                toJavaStringMethod.invoke(null, memAddr) as String
            }
        }

        val findSym: (String, Class<*>, Array<Class<*>>) -> MethodHandle = { name, retType, paramTypes ->
            val optSeg = lookupMethod.invoke(loaderLookup, name) as java.util.Optional<*>
            val symAddr = optSeg.orElseThrow {
                UnsatisfiedLinkError("Symbol not found: $name")
            }

            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, paramTypes.size)
            val mtParams = mutableListOf<Class<*>>()
            for (i in paramTypes.indices) {
                val p = paramTypes[i]
                if (p == POINTER_TYPE) {
                    java.lang.reflect.Array.set(layoutParams, i, cPointer)
                    mtParams.add(memoryAddressClass)
                } else {
                    java.lang.reflect.Array.set(layoutParams, i, classToLayout(p))
                    mtParams.add(p)
                }
            }

            val fd = if (retType == Void.TYPE) {
                fdOfVoidMethod.invoke(null, layoutParams)
            } else if (retType == POINTER_TYPE) {
                fdOfMethod.invoke(null, cPointer, layoutParams)
            } else {
                fdOfMethod.invoke(null, classToLayout(retType), layoutParams)
            }

            val mtRet = if (retType == POINTER_TYPE) memoryAddressClass
                        else if (retType == Void.TYPE) Void.TYPE
                        else retType
            val mt = MethodType.methodType(mtRet, mtParams)

            val rawHandle = downcallMethod.invoke(clinker, symAddr, mt, fd) as MethodHandle

            // Adapt: replace MemoryAddress params with long, MemoryAddress returns with long
            adaptIncubatorHandle(rawHandle, retType, paramTypes, memoryAddressClass,
                ofLongMethod, toRawLongMethod)
        }

        // ---- Symbol address lookup (no downcall handle built) ----

        val findAddr: (String) -> Long = { name ->
            val optSeg = lookupMethod.invoke(loaderLookup, name) as java.util.Optional<*>
            if (optSeg.isPresent) toRawLongMethod.invoke(optSeg.get()) as Long else 0L
        }

        // ---- Freeable UTF-8 strings ----
        //
        // Unlike the modern (malloc/free) implementation, this reuses toCString's
        // ResourceScope: each allocation gets its own confined scope, tracked by address so
        // freeUtf8Address can close it (ResourceScope.close() releases the backing memory).
        // JDK 16-18 is a legacy fallback not exercised by this repo's verification matrix
        // (JDK 21+ always resolves the modern java.lang.foreign backend); this path is a
        // best-effort mirror of the modern one, not independently verified.

        val scopeCloseMethod = memoryScopeClass.getMethod("close")
        val freeableScopes = java.util.concurrent.ConcurrentHashMap<Long, Any>()

        val allocFreeable: (String) -> Long = { str ->
            val scope = newScopeMethod.invoke(null)
            val seg = toCStringMethod.invoke(null, str, scope)
            val addr = segAddressMethod.invoke(seg)
            val raw = toRawLongMethod.invoke(addr) as Long
            freeableScopes[raw] = scope
            raw
        }

        val freeAddr: (Long) -> Unit = { addr ->
            freeableScopes.remove(addr)?.let { scopeCloseMethod.invoke(it) }
            Unit
        }

        // ---- Shape vocabulary: unbound downcall handles ----

        val downcallHandleUnboundMethod = clinkerClass.getMethod(
            "downcallHandle", MethodType::class.java, functionDescriptorClass
        )

        val longToAddrExact = lookup.findStatic(
            PanamaBackend::class.java, "incubatorLongToAddr",
            MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
        ).asType(MethodType.methodType(memoryAddressClass, Long::class.javaPrimitiveType))

        val buildShape: (Int, Int, ReturnKind) -> MethodHandle = { intArgs, floatArgs, returnKind ->
            val total = intArgs + floatArgs
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, total)
            for (i in 0 until intArgs) java.lang.reflect.Array.set(layoutParams, i, cLongLong)
            for (i in 0 until floatArgs) java.lang.reflect.Array.set(layoutParams, intArgs + i, cDouble)

            val fd = when (returnKind) {
                ReturnKind.VOID -> fdOfVoidMethod.invoke(null, layoutParams)
                ReturnKind.LONG -> fdOfMethod.invoke(null, cLongLong, layoutParams)
                ReturnKind.DOUBLE -> fdOfMethod.invoke(null, cDouble, layoutParams)
            }

            val mtParams = mutableListOf<Class<*>>()
            repeat(intArgs) { mtParams.add(Long::class.javaPrimitiveType!!) }
            repeat(floatArgs) { mtParams.add(Double::class.javaPrimitiveType!!) }
            val mtRet: Class<*> = when (returnKind) {
                ReturnKind.VOID -> Void.TYPE
                ReturnKind.LONG -> Long::class.javaPrimitiveType!!
                ReturnKind.DOUBLE -> Double::class.javaPrimitiveType!!
            }
            val mt = MethodType.methodType(mtRet, mtParams)

            val raw = downcallHandleUnboundMethod.invoke(clinker, mt, fd) as MethodHandle
            MethodHandles.filterArguments(raw, 0, longToAddrExact)
        }

        return IncubatorData(allocStr, readStr, findSym, findAddr, allocFreeable, freeAddr, buildShape)
    }

    private fun adaptIncubatorHandle(
        raw: MethodHandle, retType: Class<*>, paramTypes: Array<Class<*>>,
        memoryAddressClass: Class<*>,
        ofLongMethod: java.lang.reflect.Method,
        toRawLongMethod: java.lang.reflect.Method
    ): MethodHandle {
        var h = raw
        val lookup = MethodHandles.lookup()

        // Adapt return: MemoryAddress -> long
        if (retType == POINTER_TYPE) {
            val addrToLong = lookup.findStatic(
                PanamaBackend::class.java, "incubatorAddrToLong",
                MethodType.methodType(Long::class.javaPrimitiveType, Any::class.java)
            )
            h = MethodHandles.filterReturnValue(h, addrToLong)
        }

        // Adapt params: long -> MemoryAddress for pointer positions
        for (i in paramTypes.indices) {
            if (paramTypes[i] == POINTER_TYPE) {
                val longToAddr = lookup.findStatic(
                    PanamaBackend::class.java, "incubatorLongToAddr",
                    MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
                )
                h = MethodHandles.filterArguments(h, i, longToAddr)
            }
        }

        return h
    }

    @JvmStatic
    fun incubatorAddrToLong(addr: Any?): Long {
        if (addr == null) return 0L
        return try {
            val cls = Class.forName("jdk.incubator.foreign.MemoryAddress")
            val m = cls.getMethod("toRawLongValue")
            m.invoke(addr) as Long
        } catch (e: Exception) { 
            e.printStackTrace()
            0L 
        }
    }

    @JvmStatic
    fun incubatorLongToAddr(v: Long): Any {
        val cls = Class.forName("jdk.incubator.foreign.MemoryAddress")
        val m = cls.getMethod("ofLong", Long::class.javaPrimitiveType!!)
        return m.invoke(null, v)
    }

    // ---- Detection ----

    private fun detectBackend(): Pair<Backend, Any> {
        // Try modern first (JDK 19+)
        try {
            Class.forName("java.lang.foreign.Linker")
            return Backend.MODERN to initModern()
        } catch (_: ClassNotFoundException) { }

        // Try incubator (JDK 16-18)
        try {
            Class.forName("jdk.incubator.foreign.CLinker")
            return Backend.INCUBATOR to initIncubator()
        } catch (_: ClassNotFoundException) { }

        val ver = System.getProperty("java.version", "unknown")
        throw UnsupportedOperationException(
            "No usable Panama FFI found on JDK $ver. " +
            "Requires JDK 22+ (java.lang.foreign), " +
            "JDK 19-21 with --enable-preview, " +
            "or JDK 16-18 with --add-modules=jdk.incubator.foreign."
        )
    }
}
