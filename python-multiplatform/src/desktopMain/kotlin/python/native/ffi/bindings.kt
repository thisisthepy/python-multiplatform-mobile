package python.native.ffi

import jdk.incubator.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.lang.Long as LongLong


object bindings {
    val Py_InitializeHandle: MethodHandle
    inline fun Py_Initialize() = Py_InitializeHandle.invoke() as Unit
    val Py_InitializeExHandle: MethodHandle
    inline fun Py_InitializeEx(sigint: Int) = Py_InitializeExHandle.invoke(sigint) as Unit
    //val Py_InitializeFromConfigHandle: MethodHandle
    //inline fun Py_InitializeFromConfig() = Py_InitializeFromConfigHandle.invoke()
    val Py_IsInitializedHandle: MethodHandle
    inline fun Py_IsInitialized() = Py_IsInitializedHandle.invoke() as Int
    val Py_IsFinalizingHandle: MethodHandle
    inline fun Py_IsFinalizing() = Py_IsFinalizingHandle.invoke() as Int
    val Py_FinalizeHandle: MethodHandle
    inline fun Py_Finalize() = Py_FinalizeHandle.invoke() as Unit
    val Py_FinalizeExHandle: MethodHandle
    inline fun Py_FinalizeEx() = Py_FinalizeExHandle.invoke() as Int
    val Py_RunMainHandle: MethodHandle
    inline fun Py_RunMain(): Int = Py_RunMainHandle.invoke() as Int
    val PyRun_SimpleStringHandle: MethodHandle
    inline fun PyRun_SimpleString(command: String): Int =
        PyRun_SimpleStringHandle.invoke(
            CLinker.toCString(command, ResourceScope.newConfinedScope()).address()
        ) as Int
    val PyRun_StringHandle: MethodHandle
    inline fun PyRun_String(str: String, start: Int, globals: MemoryAddress, locals: MemoryAddress): MemoryAddress? =
        PyRun_StringHandle.invoke(
            CLinker.toCString(str, ResourceScope.newConfinedScope()).address(),
            start,
            globals,
            locals
        ) as MemoryAddress?
    val Py_GetVersionHandle: MethodHandle
    inline fun Py_GetVersion(): String? =
        CLinker.toJavaString(Py_GetVersionHandle.invoke() as MemoryAddress?)
    val Py_GetPlatformHandle: MethodHandle
    inline fun Py_GetPlatform(): String? =
        CLinker.toJavaString(Py_GetPlatformHandle.invoke() as MemoryAddress?)
    val Py_GetCopyrightHandle: MethodHandle
    inline fun Py_GetCopyright(): String? =
        CLinker.toJavaString(Py_GetCopyrightHandle.invoke() as MemoryAddress?)
    val Py_GetCompilerHandle: MethodHandle
    inline fun Py_GetCompiler(): String? =
        CLinker.toJavaString(Py_GetCompilerHandle.invoke() as MemoryAddress?)
    val Py_GetBuildInfoHandle: MethodHandle
    inline fun Py_GetBuildInfo(): String? =
        CLinker.toJavaString(Py_GetBuildInfoHandle.invoke() as MemoryAddress?)





    val PyErr_OccurredHandle: MethodHandle
    inline fun PyErr_Occurred(): MemoryAddress? = PyErr_OccurredHandle.invoke() as MemoryAddress?

    val PyLong_FromLongLongHandle: MethodHandle
    inline fun PyLong_FromLongLong(v: Long): MemoryAddress? = PyLong_FromLongLongHandle.invoke(v) as MemoryAddress?
    val PyLong_AsLongLongHandle: MethodHandle
    inline fun PyLong_AsLongLong(p: MemoryAddress): Long = PyLong_AsLongLongHandle.invoke(p) as Long
    val PyLong_AsIntHandle: MethodHandle
    inline fun PyLong_AsInt(p: MemoryAddress): Int = PyLong_AsIntHandle.invoke(p) as Int


    val PyUnicode_FromStringHandle: MethodHandle
    inline fun PyUnicode_FromString(str: String): MemoryAddress? =
        PyUnicode_FromStringHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_AsUTF8Handle: MethodHandle
    inline fun PyUnicode_AsUTF8(unicode: MemoryAddress): String? =
        CLinker.toJavaString(PyUnicode_AsUTF8Handle.invoke(unicode) as MemoryAddress?)


    init {
        ResourceScope.newConfinedScope().run {
            val lookup = MethodLookup(manager::loadLibPython)

            Py_InitializeHandle = lookup.find("Py_Initialize", Void.TYPE)
            Py_InitializeExHandle = lookup.find("Py_InitializeEx", Void.TYPE, Integer.TYPE)
            //Py_InitializeFromConfigHandle = lookup.find("Py_InitializeFromConfig", Void.TYPE)
            Py_IsInitializedHandle = lookup.find("Py_IsInitialized", Integer.TYPE)
            Py_IsFinalizingHandle = lookup.find("Py_IsFinalizing", Integer.TYPE)
            Py_FinalizeHandle = lookup.find("Py_Finalize", Void.TYPE)
            Py_FinalizeExHandle = lookup.find("Py_FinalizeEx", Integer.TYPE)
            PyRun_SimpleStringHandle = lookup.find("PyRun_SimpleString", Integer.TYPE, MemoryAddress::class.java)
            PyRun_StringHandle = lookup.find(
                "PyRun_String", MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE,
                MemoryAddress::class.java, MemoryAddress::class.java
            )
            Py_GetVersionHandle = lookup.find("Py_GetVersion", MemoryAddress::class.java)
            Py_GetPlatformHandle = lookup.find("Py_GetPlatform", MemoryAddress::class.java)
            Py_GetCopyrightHandle = lookup.find("Py_GetCopyright", MemoryAddress::class.java)
            Py_GetCompilerHandle = lookup.find("Py_GetCompiler", MemoryAddress::class.java)
            Py_GetBuildInfoHandle = lookup.find("Py_GetBuildInfo", MemoryAddress::class.java)


            Py_RunMainHandle = lookup.find("Py_FinalizeEx", Integer.TYPE)  // TODO: Fix this


            PyErr_OccurredHandle = lookup.find("PyErr_Occurred", MemoryAddress::class.java)


            PyLong_FromLongLongHandle = lookup.find("PyLong_FromLongLong", MemoryAddress::class.java, LongLong.TYPE)
            PyLong_AsLongLongHandle = lookup.find("PyLong_AsLongLong", LongLong.TYPE, MemoryAddress::class.java)
            PyLong_AsIntHandle = lookup.find("PyLong_AsInt", Integer.TYPE, MemoryAddress::class.java)

            PyUnicode_FromStringHandle = lookup.find("PyUnicode_FromString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsUTF8Handle = lookup.find("PyUnicode_AsUTF8", MemoryAddress::class.java, MemoryAddress::class.java)
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
