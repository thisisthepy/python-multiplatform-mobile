package python.native.ffi

import jdk.incubator.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType


object bindings {
    val Py_Initialize: () -> Unit
    val Py_InitializeEx: (Int) -> Unit
    //val Py_InitializeFromConfig
    val Py_IsInitialized: () -> Int
    val Py_Finalize: () -> Unit
    val Py_FinalizeEx: () -> Int
    val PyErr_Occurred: () -> MemoryAddress?


    init {
        ResourceScope.newConfinedScope().run {
            val lookup = MethodLookup(manager::loadLibPython)

            Py_Initialize = {
                lookup.find("Py_Initialize", Void.TYPE).invokeExact()
            }
            Py_InitializeEx = {
                lookup.find("Py_InitializeEx", Void.TYPE, Integer.TYPE).invokeExact(it)
            }
            Py_IsInitialized = {
                lookup.find("Py_IsInitialized", Integer.TYPE).invokeExact() as Int
            }
            Py_Finalize = {
                lookup.find("Py_Finalize", Void.TYPE).invokeExact()
            }
            Py_FinalizeEx = {
                lookup.find("Py_FinalizeEx", Integer.TYPE).invokeExact() as Int
            }

            PyErr_Occurred = {
                lookup.find("PyErr_Occurred", MemoryAddress::class.java).invokeExact() as MemoryAddress?
            }
        }
    }
}


internal class MethodLookup(libLoader: () -> Any) {
    private val symbolLookup: SymbolLookup
    private val linker: CLinker

    init {
        libLoader()
        symbolLookup = SymbolLookup.loaderLookup()
        linker = CLinker.getInstance()
    }

    fun find(symbol: String, returnType: Class<*>, vararg params: Class<*>): MethodHandle {
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
            Long::class.javaPrimitiveType -> CLinker.C_LONG_LONG
            Float::class.javaPrimitiveType -> CLinker.C_FLOAT
            Double::class.javaPrimitiveType -> CLinker.C_DOUBLE
            else -> throw IllegalArgumentException("Unsupported type for C ValueLayout conversion: $this")
        }
    }
}
