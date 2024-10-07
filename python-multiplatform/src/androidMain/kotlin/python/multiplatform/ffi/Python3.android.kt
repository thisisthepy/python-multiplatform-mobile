package python.multiplatform.ffi

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType


actual class MethodLookup actual constructor(libLoader: () -> Any) {
    private val lookup: MethodHandles.Lookup

    init {
        libLoader()
        lookup = MethodHandles.lookup()
    }

    actual fun find(symbol: String, returnType: Class<*>, vararg params: Class<*>): MethodHandle {
        val allocateType = MethodType.methodType(returnType, params)
        return lookup.findStatic(this::class.java, symbol, allocateType)
    }
}
