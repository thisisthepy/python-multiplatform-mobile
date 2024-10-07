package python.multiplatform.ffi

import jdk.incubator.foreign.CLinker
import jdk.incubator.foreign.SymbolLookup
import jdk.incubator.foreign.FunctionDescriptor
import jdk.incubator.foreign.ResourceScope
import jdk.incubator.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType


actual object Python3 {
    private val scope
        get() = ResourceScope.newSharedScope()

    // 초기화 및 종료
    private val pyInitializeHandle: MethodHandle
    //private val pyInitializeExHandle: MethodHandle
    //private val pyInitializeFromConfigHandle: MethodHandle
    private val pyFinalizeHandle: MethodHandle
    //private val pyFinalizeExHandle: MethodHandle
    private val pyIsInitializedHandle: MethodHandle

//    // 예외 처리
//    private val pyErrOccurredHandle: MethodHandle
//    private val pyErrPrintHandle: MethodHandle
//    private val pyErrClearHandle: MethodHandle
//
//    // 기타
//    private val pyRunSimpleStringHandle: MethodHandle
//    private val pyEvalGetBuiltinsHandle: MethodHandle
//
//    // 모듈 및 객체 관리
//    private val pyImportImportModuleHandle: MethodHandle
//    private val pyObjectGetAttrStringHandle: MethodHandle
//    private val pyObjectHasAttrStringHandle: MethodHandle
//    private val pyObjectCallObjectHandle: MethodHandle
//    private val pyObjectCallFunctionObjArgsHandle: MethodHandle
//    private val pyIncRefHandle: MethodHandle
//    private val pyDecRefHandle: MethodHandle
//
//    // 타입 변환
//    private val pyLongFromLongHandle: MethodHandle
//    private val pyLongAsLongHandle: MethodHandle
//    private val pyFloatFromDoubleHandle: MethodHandle
//    private val pyFloatAsDoubleHandle: MethodHandle
//    private val pyUnicodeFromStringHandle: MethodHandle
//    private val pyUnicodeAsUTF8Handle: MethodHandle
//
//    // 컬렉션
//    private val pyListNewHandle: MethodHandle
//    private val pyListSizeHandle: MethodHandle
//    private val pyListGetItemHandle: MethodHandle
//    private val pyListSetItemHandle: MethodHandle
//    private val pyTupleNewHandle: MethodHandle
//    private val pyTupleSizeHandle: MethodHandle
//    private val pyTupleGetItemHandle: MethodHandle
//    private val pyTupleSetItemHandle: MethodHandle


    actual var isInitialized: Boolean = false
        private set

    actual fun initialize() {
        if (isInitialized) return
        scope.run {
            pyInitializeHandle.invokeExact()
            if (pyIsInitializedHandle.invokeExact() as Int == 0) {
                // TODO: Add error handling
                throw IllegalStateException("Python initialization failed")
            }
            println("INFO: Python initialized successfully!")
            isInitialized = true
        }
    }

    actual fun finalize() {
        if (!isInitialized) return
        scope.run {
            pyFinalizeHandle.invokeExact()
            // TODO: print error message if exists
            if (pyIsInitializedHandle.invokeExact() as Int != 0) {
                throw IllegalStateException("Python finalization failed")
            }
            println("INFO: Python finalized successfully!")
            isInitialized = false
        }
    }

    init {
        scope.run {
            val lookup = MethodLookup(::loadLibPython)

            pyInitializeHandle = lookup.find("Py_Initialize", Void.TYPE)
            pyFinalizeHandle = lookup.find("Py_Finalize", Void.TYPE)
            pyIsInitializedHandle = lookup.find("Py_IsInitialized", Integer.TYPE)
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
            Long::class.javaPrimitiveType -> CLinker.C_LONG
            Float::class.javaPrimitiveType -> CLinker.C_FLOAT
            Double::class.javaPrimitiveType -> CLinker.C_DOUBLE
            else -> throw IllegalArgumentException("Unsupported type for C ValueLayout conversion: $this")
        }
    }
}
