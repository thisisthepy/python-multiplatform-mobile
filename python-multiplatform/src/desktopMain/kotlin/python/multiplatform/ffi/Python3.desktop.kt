package python.multiplatform.ffi

import jdk.incubator.foreign.CLinker
import jdk.incubator.foreign.SymbolLookup
import jdk.incubator.foreign.FunctionDescriptor
import jdk.incubator.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType


actual class MethodLookup actual constructor(libLoader: () -> Any) {
    private val symbolLookup: SymbolLookup
    private val linker: CLinker

    init {
        libLoader()
        symbolLookup = SymbolLookup.loaderLookup()
        linker = CLinker.getInstance()
    }

    actual fun find(symbol: String, returnType: Class<*>, vararg params: Class<*>): MethodHandle {
        val isParamRequired = params.isNotEmpty()
        val descriptor = if (returnType == Void::class.javaPrimitiveType) {
            if (isParamRequired) FunctionDescriptor.ofVoid(*params.map { it.toLayout() }.toTypedArray())
            else FunctionDescriptor.ofVoid()
        } else {
            val returnLayout = returnType.toLayout()
            if (isParamRequired) FunctionDescriptor.of(returnLayout, *params.map { it.toLayout() }.toTypedArray())
            else FunctionDescriptor.of(returnLayout)
        }
        return linker.downcallHandle(
            symbolLookup.lookup(symbol).get(),
            MethodType.methodType(returnType, params),
            descriptor
        )
    }

    private fun Class<*>.toLayout(): ValueLayout {
        return when (this) {
            Byte::class.javaPrimitiveType -> CLinker.C_CHAR
            Short::class.javaPrimitiveType -> CLinker.C_SHORT
            Int::class.javaPrimitiveType -> CLinker.C_INT
            Long::class.javaPrimitiveType -> CLinker.C_LONG
            Float::class.javaPrimitiveType -> CLinker.C_FLOAT
            Double::class.javaPrimitiveType -> CLinker.C_DOUBLE
            else -> throw IllegalArgumentException("Unsupported type for C ValueLayout conversion: $this")
        }
    }
}
