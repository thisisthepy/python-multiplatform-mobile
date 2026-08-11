package python.native.ffi

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object UpcallTarget {
    @JvmStatic
    fun upcallPrimitive(x: Long): Long {
        return x
    }

    @JvmStatic
    fun upcallString(addr: Long): Long {
        val s = Panama.readUtf8String(addr) ?: return 0L
        return s.length.toLong()
    }
}

object UpcallStub {
    val primitiveStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallPrimitive" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubLongToLong(handle)
    }

    val stringStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallString" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubLongToLong(handle)
    }
}
