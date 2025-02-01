package python.native.ffi

import jdk.incubator.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.lang.Long as LongLong
import java.lang.String as Str


object bindings {
    val Py_InitializeHandle: MethodHandle
    inline fun Py_Initialize() = Py_InitializeHandle.invoke() as Unit
    val Py_InitializeExHandle: MethodHandle
    inline fun Py_InitializeEx(sigint: Int) = Py_InitializeExHandle.invoke(sigint) as Unit
    //val Py_InitializeFromConfigHandle: MethodHandle
    //inline fun Py_InitializeFromConfig() = Py_InitializeFromConfigHandle.invoke()
    val Py_IsInitializedHandle: MethodHandle
    inline fun Py_IsInitialized() = Py_IsInitializedHandle.invoke() as Int
    val Py_FinalizeHandle: MethodHandle
    inline fun Py_Finalize() = Py_FinalizeHandle.invoke() as Unit
    val Py_FinalizeExHandle: MethodHandle
    inline fun Py_FinalizeEx() = Py_FinalizeExHandle.invoke() as Int

    val PyErr_OccurredHandle: MethodHandle
    inline fun PyErr_Occurred(): MemoryAddress? = PyErr_OccurredHandle.invoke() as MemoryAddress?

    val PyLong_FromLongLongHandle: MethodHandle
    inline fun PyLong_FromLongLong(v: Long): MemoryAddress? = PyLong_FromLongLongHandle.invoke(v) as MemoryAddress?
    val PyLong_AsLongLongHandle: MethodHandle
    inline fun PyLong_AsLongLong(p: MemoryAddress): Long = PyLong_AsLongLongHandle.invoke(p) as Long
    val PyLong_AsIntHandle: MethodHandle
    inline fun PyLong_AsInt(p: MemoryAddress): Int = PyLong_AsIntHandle.invoke(p) as Int

    val PyRun_SimpleStringHandle: MethodHandle
    inline fun PyRun_SimpleString(code: String): Int = PyRun_SimpleStringHandle.invoke(CLinker.toCString(code, ResourceScope.newConfinedScope()).address()) as Int


    init {
        ResourceScope.newConfinedScope().run {
            val lookup = MethodLookup(manager::loadLibPython)

            Py_InitializeHandle = lookup.find("Py_Initialize", Void.TYPE)
            Py_InitializeExHandle = lookup.find("Py_InitializeEx", Void.TYPE, Integer.TYPE)
            //Py_InitializeFromConfigHandle = lookup.find("Py_InitializeFromConfig", Void.TYPE)
            Py_IsInitializedHandle = lookup.find("Py_IsInitialized", Integer.TYPE)
            Py_FinalizeHandle = lookup.find("Py_Finalize", Void.TYPE)
            Py_FinalizeExHandle = lookup.find("Py_FinalizeEx", Integer.TYPE)

            PyErr_OccurredHandle = lookup.find("PyErr_Occurred", MemoryAddress::class.java)


            PyLong_FromLongLongHandle = lookup.find("PyLong_FromLongLong", MemoryAddress::class.java, LongLong.TYPE)
            PyLong_AsLongLongHandle = lookup.find("PyLong_AsLongLong", LongLong.TYPE, MemoryAddress::class.java)
            PyLong_AsIntHandle = lookup.find("PyLong_AsInt", Integer.TYPE, MemoryAddress::class.java)

            PyRun_SimpleStringHandle = lookup.find("PyRun_SimpleString", Integer.TYPE, MemoryAddress::class.java)
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
            MemoryAddress::class.java -> CLinker.C_POINTER
            else -> throw IllegalArgumentException("Unsupported type for C ValueLayout conversion: $this")
        }
    }
}
