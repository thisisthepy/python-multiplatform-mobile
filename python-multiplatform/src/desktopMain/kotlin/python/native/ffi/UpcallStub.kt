package python.native.ffi

import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.UpcallTable
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object UpcallTarget {
    @JvmStatic
    fun upcallPrimitive(x: Long): Long {
        return x
    }

    @JvmStatic
    fun upcallString(addr: Long): Long {
        val s = PanamaBackend.readUtf8String(addr) ?: return 0L
        return s.length.toLong()
    }

    /**
     * Verification entry point for ROADMAP §7's "does the manual registration path survive
     * GraalVM native-image" question: Python passes a `const char*`, this resolves it against
     * [UpcallTable] the same way a generated trampoline would, and hands back the raw
     * [CallableHandle] as a plain long. `CallableHandle.NONE.raw` (-1) means "no such name" --
     * the boundary carries that back to Python rather than throwing, same as
     * [UpcallTable.resolve] itself.
     */
    @JvmStatic
    fun upcallResolveHandle(nameAddr: Long): Long {
        val name = PanamaBackend.readUtf8String(nameAddr) ?: return CallableHandle.NONE.raw
        return UpcallTable.resolve(name).raw
    }

    /**
     * The fast path this whole design exists for: a handle already resolved once, invoked with
     * no further name lookup. Restricted to zero-argument, `Long`-returning entries because the
     * shape a Panama upcall stub can target here is `(long) -> long`; a real trampoline would
     * carry a richer arg-marshalling convention (see `docs/upcall-design.md`), which is not
     * needed to answer the closed-world question this entry point is for.
     */
    @JvmStatic
    fun upcallInvokeHandle(handleRaw: Long): Long {
        return UpcallTable.invoke(CallableHandle(handleRaw), emptyArray()) as Long
    }
}

object UpcallStub {
    val primitiveStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallPrimitive" }
        val handle = MethodHandles.lookup().unreflect(method)
        PanamaBackend.createUpcallStubLongToLong(handle)
    }

    val stringStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallString" }
        val handle = MethodHandles.lookup().unreflect(method)
        PanamaBackend.createUpcallStubLongToLong(handle)
    }

    /** `Python's ctypes.CFUNCTYPE(c_long, c_char_p)` target: name -> handle. */
    val resolveHandleStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallResolveHandle" }
        val handle = MethodHandles.lookup().unreflect(method)
        PanamaBackend.createUpcallStubLongToLong(handle)
    }

    /** `Python's ctypes.CFUNCTYPE(c_long, c_long)` target: handle -> result. */
    val invokeHandleStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallInvokeHandle" }
        val handle = MethodHandles.lookup().unreflect(method)
        PanamaBackend.createUpcallStubLongToLong(handle)
    }
}
