package python.native.ffi

import jdk.incubator.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import java.lang.Long as LongLong
import java.lang.Double as Double


object bindings {
    /**
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
    */


    //**************************************************
    // Section 1
    val Py_InitializeHandle: MethodHandle
    inline fun Py_Initialize() = Py_InitializeHandle.invoke() as Unit
    val Py_InitializeExHandle: MethodHandle
    inline fun Py_InitializeEx(initsigs: Int) = Py_InitializeExHandle.invoke(initsigs) as Unit
    val Py_IsInitializedHandle: MethodHandle
    inline fun Py_IsInitialized(): Int = Py_IsInitializedHandle.invoke() as Int
    val Py_IsFinalizingHandle: MethodHandle
    inline fun Py_IsFinalizing(): Int = Py_IsFinalizingHandle.invoke() as Int
    val Py_FinalizeExHandle: MethodHandle
    inline fun Py_FinalizeEx(): Int = Py_FinalizeExHandle.invoke() as Int
    val Py_FinalizeHandle: MethodHandle
    inline fun Py_Finalize() = Py_FinalizeHandle.invoke() as Unit
    val Py_GetVersionHandle: MethodHandle
    val Py_RunMainHandle: MethodHandle // 수동 추가
    inline fun Py_RunMain(): Int = Py_RunMainHandle.invoke() as Int
    inline fun Py_GetVersion(): String? = CLinker.toJavaString(Py_GetVersionHandle.invoke() as MemoryAddress?)
    val Py_GetPlatformHandle: MethodHandle
    inline fun Py_GetPlatform(): String? = CLinker.toJavaString(Py_GetPlatformHandle.invoke() as MemoryAddress?)
    val Py_GetCopyrightHandle: MethodHandle
    inline fun Py_GetCopyright(): String? = CLinker.toJavaString(Py_GetCopyrightHandle.invoke() as MemoryAddress?)
    val Py_GetCompilerHandle: MethodHandle
    inline fun Py_GetCompiler(): String? = CLinker.toJavaString(Py_GetCompilerHandle.invoke() as MemoryAddress?)
    val Py_GetBuildInfoHandle: MethodHandle
    inline fun Py_GetBuildInfo(): String? = CLinker.toJavaString(Py_GetBuildInfoHandle.invoke() as MemoryAddress?)
    val PyEval_InitThreadsHandle: MethodHandle
    inline fun PyEval_InitThreads() = PyEval_InitThreadsHandle.invoke() as Unit
    val PyThreadState_GetDictHandle: MethodHandle
    inline fun PyThreadState_GetDict(): MemoryAddress? = PyThreadState_GetDictHandle.invoke() as MemoryAddress?


    // Section 2
    val PyRun_SimpleStringHandle: MethodHandle // 수동 추가
    inline fun PyRun_SimpleString(command: String): Int = PyRun_SimpleStringHandle.invoke(CLinker.toCString(command, ResourceScope.newConfinedScope()).address()) as Int
    val PyRun_StringHandle: MethodHandle // 수동 추가
    inline fun PyRun_String(str: String, start: Int, globals: MemoryAddress, locals: MemoryAddress): MemoryAddress? = PyRun_StringHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address(),start,globals,locals) as MemoryAddress?
    val Py_CompileStringHandle: MethodHandle
    inline fun Py_CompileString(str: String, filename: String, start: Int): MemoryAddress? = Py_CompileStringHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address(), CLinker.toCString(filename, ResourceScope.newConfinedScope()).address(), start) as MemoryAddress?
    val PyEval_EvalCodeHandle: MethodHandle
    inline fun PyEval_EvalCode(co: MemoryAddress, globals: MemoryAddress, locals: MemoryAddress): MemoryAddress? = PyEval_EvalCodeHandle.invoke(co, globals, locals) as MemoryAddress?


    // Section 3
    val PyErr_ClearHandle: MethodHandle
    inline fun PyErr_Clear() = PyErr_ClearHandle.invoke() as Unit
    val PyErr_PrintExHandle: MethodHandle
    inline fun PyErr_PrintEx(set_sys_last_vars: Int) = PyErr_PrintExHandle.invoke(set_sys_last_vars) as Unit
    val PyErr_PrintHandle: MethodHandle
    inline fun PyErr_Print() = PyErr_PrintHandle.invoke() as Unit
    val PyErr_WriteUnraisableHandle: MethodHandle
    inline fun PyErr_WriteUnraisable(obj: MemoryAddress) = PyErr_WriteUnraisableHandle.invoke(obj) as Unit
    val PyErr_DisplayExceptionHandle: MethodHandle
    inline fun PyErr_DisplayException(exc: MemoryAddress) = PyErr_DisplayExceptionHandle.invoke(exc) as Unit
    val PyErr_SetStringHandle: MethodHandle
    inline fun PyErr_SetString(type: MemoryAddress, message: String) = PyErr_SetStringHandle.invoke(type, CLinker.toCString(message, ResourceScope.newConfinedScope()).address()) as Unit
    val PyErr_SetObjectHandle: MethodHandle
    inline fun PyErr_SetObject(type: MemoryAddress, value: MemoryAddress) = PyErr_SetObjectHandle.invoke(type, value) as Unit
    val PyErr_SetNoneHandle: MethodHandle
    inline fun PyErr_SetNone(type: MemoryAddress) = PyErr_SetNoneHandle.invoke(type) as Unit
    val PyErr_BadArgumentHandle: MethodHandle
    inline fun PyErr_BadArgument(): Int = PyErr_BadArgumentHandle.invoke() as Int
    val PyErr_NoMemoryHandle: MethodHandle
    inline fun PyErr_NoMemory(): MemoryAddress? = PyErr_NoMemoryHandle.invoke() as MemoryAddress?
    val PyErr_SetFromErrnoHandle: MethodHandle
    inline fun PyErr_SetFromErrno(type: MemoryAddress): MemoryAddress? = PyErr_SetFromErrnoHandle.invoke(type) as MemoryAddress?
    val PyErr_SetFromErrnoWithFilenameObjectHandle: MethodHandle
    inline fun PyErr_SetFromErrnoWithFilenameObject(type: MemoryAddress, filenameObject: MemoryAddress): MemoryAddress? = PyErr_SetFromErrnoWithFilenameObjectHandle.invoke(type, filenameObject) as MemoryAddress?
    val PyErr_SetFromErrnoWithFilenameObjectsHandle: MethodHandle
    inline fun PyErr_SetFromErrnoWithFilenameObjects(type: MemoryAddress, filenameObject: MemoryAddress, filenameObject2: MemoryAddress): MemoryAddress? = PyErr_SetFromErrnoWithFilenameObjectsHandle.invoke(type, filenameObject, filenameObject2) as MemoryAddress?
    val PyErr_SetFromErrnoWithFilenameHandle: MethodHandle
    inline fun PyErr_SetFromErrnoWithFilename(type: MemoryAddress, filename: String): MemoryAddress? = PyErr_SetFromErrnoWithFilenameHandle.invoke(type, CLinker.toCString(filename, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyErr_SetImportErrorHandle: MethodHandle
    inline fun PyErr_SetImportError(msg: MemoryAddress, name: MemoryAddress, path: MemoryAddress): MemoryAddress? = PyErr_SetImportErrorHandle.invoke(msg, name, path) as MemoryAddress?
    val PyErr_SetImportErrorSubclassHandle: MethodHandle
    inline fun PyErr_SetImportErrorSubclass(exception: MemoryAddress, msg: MemoryAddress, name: MemoryAddress, path: MemoryAddress): MemoryAddress? = PyErr_SetImportErrorSubclassHandle.invoke(exception, msg, name, path) as MemoryAddress?
    val PyErr_SyntaxLocationExHandle: MethodHandle
    inline fun PyErr_SyntaxLocationEx(filename: String, lineno: Int, col_offset: Int) = PyErr_SyntaxLocationExHandle.invoke(CLinker.toCString(filename, ResourceScope.newConfinedScope()).address(), lineno, col_offset) as Unit
    val PyErr_SyntaxLocationHandle: MethodHandle
    inline fun PyErr_SyntaxLocation(filename: String, lineno: Int) = PyErr_SyntaxLocationHandle.invoke(CLinker.toCString(filename, ResourceScope.newConfinedScope()).address(), lineno) as Unit
    val PyErr_BadInternalCallHandle: MethodHandle
    inline fun PyErr_BadInternalCall() = PyErr_BadInternalCallHandle.invoke() as Unit
    val PyErr_WarnExplicitHandle: MethodHandle
    inline fun PyErr_WarnExplicit(category: MemoryAddress, message: String, filename: String, lineno: Int, module: String, registry: MemoryAddress): Int = PyErr_WarnExplicitHandle.invoke(category, CLinker.toCString(message, ResourceScope.newConfinedScope()).address(), CLinker.toCString(filename, ResourceScope.newConfinedScope()).address(), lineno, CLinker.toCString(module, ResourceScope.newConfinedScope()).address(), registry) as Int
    val PyErr_OccurredHandle: MethodHandle
    inline fun PyErr_Occurred(): MemoryAddress? = PyErr_OccurredHandle.invoke() as MemoryAddress?
    val PyErr_ExceptionMatchesHandle: MethodHandle
    inline fun PyErr_ExceptionMatches(exc: MemoryAddress): Int = PyErr_ExceptionMatchesHandle.invoke(exc) as Int
    val PyErr_GivenExceptionMatchesHandle: MethodHandle
    inline fun PyErr_GivenExceptionMatches(given: MemoryAddress, exc: MemoryAddress): Int = PyErr_GivenExceptionMatchesHandle.invoke(given, exc) as Int
    val PyErr_GetRaisedExceptionHandle: MethodHandle
    inline fun PyErr_GetRaisedException(): MemoryAddress? = PyErr_GetRaisedExceptionHandle.invoke() as MemoryAddress?
    val PyErr_SetRaisedExceptionHandle: MethodHandle
    inline fun PyErr_SetRaisedException(exc: MemoryAddress) = PyErr_SetRaisedExceptionHandle.invoke(exc) as Unit
    val PyErr_RestoreHandle: MethodHandle
    inline fun PyErr_Restore(type: MemoryAddress, value: MemoryAddress, traceback: MemoryAddress) = PyErr_RestoreHandle.invoke(type, value, traceback) as Unit
    val PyErr_GetHandledExceptionHandle: MethodHandle
    inline fun PyErr_GetHandledException(): MemoryAddress? = PyErr_GetHandledExceptionHandle.invoke() as MemoryAddress?
    val PyErr_SetHandledExceptionHandle: MethodHandle
    inline fun PyErr_SetHandledException(exc: MemoryAddress) = PyErr_SetHandledExceptionHandle.invoke(exc) as Unit
    val PyErr_SetExcInfoHandle: MethodHandle
    inline fun PyErr_SetExcInfo(type: MemoryAddress, value: MemoryAddress, traceback: MemoryAddress) = PyErr_SetExcInfoHandle.invoke(type, value, traceback) as Unit
    val PyErr_CheckSignalsHandle: MethodHandle
    inline fun PyErr_CheckSignals(): Int = PyErr_CheckSignalsHandle.invoke() as Int
    val PyErr_SetInterruptHandle: MethodHandle
    inline fun PyErr_SetInterrupt() = PyErr_SetInterruptHandle.invoke() as Unit
    val PyErr_SetInterruptExHandle: MethodHandle
    inline fun PyErr_SetInterruptEx(signum: Int): Int = PyErr_SetInterruptExHandle.invoke(signum) as Int
    val PyErr_NewExceptionHandle: MethodHandle
    inline fun PyErr_NewException(name: String, base: MemoryAddress, dict: MemoryAddress): MemoryAddress? = PyErr_NewExceptionHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), base, dict) as MemoryAddress?
    val PyErr_NewExceptionWithDocHandle: MethodHandle
    inline fun PyErr_NewExceptionWithDoc(name: String, doc: String, base: MemoryAddress, dict: MemoryAddress): MemoryAddress? = PyErr_NewExceptionWithDocHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), CLinker.toCString(doc, ResourceScope.newConfinedScope()).address(), base, dict) as MemoryAddress?
    val PyException_GetTracebackHandle: MethodHandle
    inline fun PyException_GetTraceback(ex: MemoryAddress): MemoryAddress? = PyException_GetTracebackHandle.invoke(ex) as MemoryAddress?
    val PyException_SetTracebackHandle: MethodHandle
    inline fun PyException_SetTraceback(ex: MemoryAddress, tb: MemoryAddress): Int = PyException_SetTracebackHandle.invoke(ex, tb) as Int
    val PyException_GetContextHandle: MethodHandle
    inline fun PyException_GetContext(ex: MemoryAddress): MemoryAddress? = PyException_GetContextHandle.invoke(ex) as MemoryAddress?
    val PyException_SetContextHandle: MethodHandle
    inline fun PyException_SetContext(ex: MemoryAddress, ctx: MemoryAddress) = PyException_SetContextHandle.invoke(ex, ctx) as Unit
    val PyException_GetCauseHandle: MethodHandle
    inline fun PyException_GetCause(ex: MemoryAddress): MemoryAddress? = PyException_GetCauseHandle.invoke(ex) as MemoryAddress?
    val PyException_SetCauseHandle: MethodHandle
    inline fun PyException_SetCause(ex: MemoryAddress, cause: MemoryAddress) = PyException_SetCauseHandle.invoke(ex, cause) as Unit
    val PyException_GetArgsHandle: MethodHandle
    inline fun PyException_GetArgs(ex: MemoryAddress): MemoryAddress? = PyException_GetArgsHandle.invoke(ex) as MemoryAddress?
    val PyException_SetArgsHandle: MethodHandle
    inline fun PyException_SetArgs(ex: MemoryAddress, args: MemoryAddress) = PyException_SetArgsHandle.invoke(ex, args) as Unit
    val PyUnicodeEncodeError_GetEncodingHandle: MethodHandle
    inline fun PyUnicodeEncodeError_GetEncoding(exc: MemoryAddress): MemoryAddress? = PyUnicodeEncodeError_GetEncodingHandle.invoke(exc) as MemoryAddress?
    val PyUnicodeTranslateError_GetObjectHandle: MethodHandle
    inline fun PyUnicodeTranslateError_GetObject(exc: MemoryAddress): MemoryAddress? = PyUnicodeTranslateError_GetObjectHandle.invoke(exc) as MemoryAddress?
    val PyUnicodeTranslateError_GetReasonHandle: MethodHandle
    inline fun PyUnicodeTranslateError_GetReason(exc: MemoryAddress): MemoryAddress? = PyUnicodeTranslateError_GetReasonHandle.invoke(exc) as MemoryAddress?
    val PyUnicodeTranslateError_SetReasonHandle: MethodHandle
    inline fun PyUnicodeTranslateError_SetReason(exc: MemoryAddress, reason: String): Int = PyUnicodeTranslateError_SetReasonHandle.invoke(exc, CLinker.toCString(reason, ResourceScope.newConfinedScope()).address()) as Int
    val Py_EnterRecursiveCallHandle: MethodHandle
    inline fun Py_EnterRecursiveCall(where: String): Int = Py_EnterRecursiveCallHandle.invoke(CLinker.toCString(where, ResourceScope.newConfinedScope()).address()) as Int
    val Py_LeaveRecursiveCallHandle: MethodHandle
    inline fun Py_LeaveRecursiveCall() = Py_LeaveRecursiveCallHandle.invoke() as Unit
    val Py_ReprEnterHandle: MethodHandle
    inline fun Py_ReprEnter(o: MemoryAddress): Int = Py_ReprEnterHandle.invoke(o) as Int
    val Py_ReprLeaveHandle: MethodHandle
    inline fun Py_ReprLeave(o: MemoryAddress) = Py_ReprLeaveHandle.invoke(o) as Unit


    // Section 4
    val Py_NewRefHandle: MethodHandle
    inline fun Py_NewRef(o: MemoryAddress): MemoryAddress? = Py_NewRefHandle.invoke(o) as MemoryAddress?
    val Py_XNewRefHandle: MethodHandle
    inline fun Py_XNewRef(o: MemoryAddress): MemoryAddress? = Py_XNewRefHandle.invoke(o) as MemoryAddress?
    val Py_IncRefHandle: MethodHandle
    inline fun Py_IncRef(o: MemoryAddress) = Py_IncRefHandle.invoke(o) as Unit
    val Py_DecRefHandle: MethodHandle
    inline fun Py_DecRef(o: MemoryAddress) = Py_DecRefHandle.invoke(o) as Unit


    // Section 5
    val PyOS_FSPathHandle: MethodHandle
    inline fun PyOS_FSPath(path: MemoryAddress): MemoryAddress? = PyOS_FSPathHandle.invoke(path) as MemoryAddress?


    // Section 6
    val PySys_GetObjectHandle: MethodHandle
    inline fun PySys_GetObject(name: String): MemoryAddress? = PySys_GetObjectHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PySys_SetObjectHandle: MethodHandle
    inline fun PySys_SetObject(name: String, v: MemoryAddress): Int = PySys_SetObjectHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), v) as Int
    val PySys_ResetWarnOptionsHandle: MethodHandle
    inline fun PySys_ResetWarnOptions() = PySys_ResetWarnOptionsHandle.invoke() as Unit
    val PySys_GetXOptionsHandle: MethodHandle
    inline fun PySys_GetXOptions(): MemoryAddress? = PySys_GetXOptionsHandle.invoke() as MemoryAddress?
    val PySys_AuditTupleHandle: MethodHandle
    inline fun PySys_AuditTuple(event: String, args: MemoryAddress): Int = PySys_AuditTupleHandle.invoke(CLinker.toCString(event, ResourceScope.newConfinedScope()).address(), args) as Int


    // Section 7
    val Py_FatalErrorHandle: MethodHandle
    inline fun Py_FatalError(message: String) = Py_FatalErrorHandle.invoke(CLinker.toCString(message, ResourceScope.newConfinedScope()).address()) as Unit
    val Py_ExitHandle: MethodHandle
    inline fun Py_Exit(status: Int) = Py_ExitHandle.invoke(status) as Unit


    // Section 8
    val PyImport_ImportModuleHandle: MethodHandle
    inline fun PyImport_ImportModule(name: String): MemoryAddress? = PyImport_ImportModuleHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyImport_ImportModuleNoBlockHandle: MethodHandle
    inline fun PyImport_ImportModuleNoBlock(name: String): MemoryAddress? = PyImport_ImportModuleNoBlockHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyImport_ImportModuleLevelObjectHandle: MethodHandle
    inline fun PyImport_ImportModuleLevelObject(name: MemoryAddress, globals: MemoryAddress, locals: MemoryAddress, fromlist: MemoryAddress, level: Int): MemoryAddress? = PyImport_ImportModuleLevelObjectHandle.invoke(name, globals, locals, fromlist, level) as MemoryAddress?
    val PyImport_ImportModuleLevelHandle: MethodHandle
    inline fun PyImport_ImportModuleLevel(name: String, globals: MemoryAddress, locals: MemoryAddress, fromlist: MemoryAddress, level: Int): MemoryAddress? = PyImport_ImportModuleLevelHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), globals, locals, fromlist, level) as MemoryAddress?
    val PyImport_ImportHandle: MethodHandle
    inline fun PyImport_Import(name: MemoryAddress): MemoryAddress? = PyImport_ImportHandle.invoke(name) as MemoryAddress?
    val PyImport_ReloadModuleHandle: MethodHandle
    inline fun PyImport_ReloadModule(m: MemoryAddress): MemoryAddress? = PyImport_ReloadModuleHandle.invoke(m) as MemoryAddress?
    val PyImport_AddModuleRefHandle: MethodHandle
    inline fun PyImport_AddModuleRef(name: String): MemoryAddress? = PyImport_AddModuleRefHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyImport_AddModuleObjectHandle: MethodHandle
    inline fun PyImport_AddModuleObject(name: MemoryAddress): MemoryAddress? = PyImport_AddModuleObjectHandle.invoke(name) as MemoryAddress?
    val PyImport_AddModuleHandle: MethodHandle
    inline fun PyImport_AddModule(name: String): MemoryAddress? = PyImport_AddModuleHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyImport_ExecCodeModuleHandle: MethodHandle
    inline fun PyImport_ExecCodeModule(name: String, co: MemoryAddress): MemoryAddress? = PyImport_ExecCodeModuleHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), co) as MemoryAddress?
    val PyImport_ExecCodeModuleExHandle: MethodHandle
    inline fun PyImport_ExecCodeModuleEx(name: String, co: MemoryAddress, pathname: String): MemoryAddress? = PyImport_ExecCodeModuleExHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), co, CLinker.toCString(pathname, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyImport_ExecCodeModuleObjectHandle: MethodHandle
    inline fun PyImport_ExecCodeModuleObject(name: MemoryAddress, co: MemoryAddress, pathname: MemoryAddress, cpathname: MemoryAddress): MemoryAddress? = PyImport_ExecCodeModuleObjectHandle.invoke(name, co, pathname, cpathname) as MemoryAddress?
    val PyImport_ExecCodeModuleWithPathnamesHandle: MethodHandle
    inline fun PyImport_ExecCodeModuleWithPathnames(name: String, co: MemoryAddress, pathname: String, cpathname: String): MemoryAddress? = PyImport_ExecCodeModuleWithPathnamesHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address(), co, CLinker.toCString(pathname, ResourceScope.newConfinedScope()).address(), CLinker.toCString(cpathname, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyImport_GetMagicTagHandle: MethodHandle
    inline fun PyImport_GetMagicTag(): String? = CLinker.toJavaString(PyImport_GetMagicTagHandle.invoke() as MemoryAddress?)
    val PyImport_GetModuleDictHandle: MethodHandle
    inline fun PyImport_GetModuleDict(): MemoryAddress? = PyImport_GetModuleDictHandle.invoke() as MemoryAddress?
    val PyImport_GetModuleHandle: MethodHandle
    inline fun PyImport_GetModule(name: MemoryAddress): MemoryAddress? = PyImport_GetModuleHandle.invoke(name) as MemoryAddress?
    val PyImport_GetImporterHandle: MethodHandle
    inline fun PyImport_GetImporter(path: MemoryAddress): MemoryAddress? = PyImport_GetImporterHandle.invoke(path) as MemoryAddress?
    val PyImport_ImportFrozenModuleObjectHandle: MethodHandle
    inline fun PyImport_ImportFrozenModuleObject(name: MemoryAddress): Int = PyImport_ImportFrozenModuleObjectHandle.invoke(name) as Int
    val PyImport_ImportFrozenModuleHandle: MethodHandle
    inline fun PyImport_ImportFrozenModule(name: String): Int = PyImport_ImportFrozenModuleHandle.invoke(CLinker.toCString(name, ResourceScope.newConfinedScope()).address()) as Int


    // Section 9
    val PyEval_GetBuiltinsHandle: MethodHandle
    inline fun PyEval_GetBuiltins(): MemoryAddress? = PyEval_GetBuiltinsHandle.invoke() as MemoryAddress?
    val PyEval_GetLocalsHandle: MethodHandle
    inline fun PyEval_GetLocals(): MemoryAddress? = PyEval_GetLocalsHandle.invoke() as MemoryAddress?
    val PyEval_GetGlobalsHandle: MethodHandle
    inline fun PyEval_GetGlobals(): MemoryAddress? = PyEval_GetGlobalsHandle.invoke() as MemoryAddress?
    val PyEval_GetFrameBuiltinsHandle: MethodHandle
    inline fun PyEval_GetFrameBuiltins(): MemoryAddress? = PyEval_GetFrameBuiltinsHandle.invoke() as MemoryAddress?
    val PyEval_GetFrameLocalsHandle: MethodHandle
    inline fun PyEval_GetFrameLocals(): MemoryAddress? = PyEval_GetFrameLocalsHandle.invoke() as MemoryAddress?
    val PyEval_GetFrameGlobalsHandle: MethodHandle
    inline fun PyEval_GetFrameGlobals(): MemoryAddress? = PyEval_GetFrameGlobalsHandle.invoke() as MemoryAddress?
    val PyEval_GetFuncNameHandle: MethodHandle
    inline fun PyEval_GetFuncName(func: MemoryAddress): String? = CLinker.toJavaString(PyEval_GetFuncNameHandle.invoke(func) as MemoryAddress?)
    val PyEval_GetFuncDescHandle: MethodHandle
    inline fun PyEval_GetFuncDesc(func: MemoryAddress): String? = CLinker.toJavaString(PyEval_GetFuncDescHandle.invoke(func) as MemoryAddress?)


    // Section 10
    val PyObject_HasAttrWithErrorHandle: MethodHandle
    inline fun PyObject_HasAttrWithError(o: MemoryAddress, attr_name: MemoryAddress): Int = PyObject_HasAttrWithErrorHandle.invoke(o, attr_name) as Int
    val PyObject_HasAttrStringWithErrorHandle: MethodHandle
    inline fun PyObject_HasAttrStringWithError(o: MemoryAddress, attr_name: String): Int = PyObject_HasAttrStringWithErrorHandle.invoke(o, CLinker.toCString(attr_name, ResourceScope.newConfinedScope()).address()) as Int
    val PyObject_HasAttrHandle: MethodHandle
    inline fun PyObject_HasAttr(o: MemoryAddress, attr_name: MemoryAddress): Int = PyObject_HasAttrHandle.invoke(o, attr_name) as Int
    val PyObject_HasAttrStringHandle: MethodHandle
    inline fun PyObject_HasAttrString(o: MemoryAddress, attr_name: String): Int = PyObject_HasAttrStringHandle.invoke(o, CLinker.toCString(attr_name, ResourceScope.newConfinedScope()).address()) as Int
    val PyObject_GetAttrHandle: MethodHandle
    inline fun PyObject_GetAttr(o: MemoryAddress, attr_name: MemoryAddress): MemoryAddress? = PyObject_GetAttrHandle.invoke(o, attr_name) as MemoryAddress?
    val PyObject_GetAttrStringHandle: MethodHandle
    inline fun PyObject_GetAttrString(o: MemoryAddress, attr_name: String): MemoryAddress? = PyObject_GetAttrStringHandle.invoke(o, CLinker.toCString(attr_name, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyObject_GenericGetAttrHandle: MethodHandle
    inline fun PyObject_GenericGetAttr(o: MemoryAddress, name: MemoryAddress): MemoryAddress? = PyObject_GenericGetAttrHandle.invoke(o, name) as MemoryAddress?
    val PyObject_SetAttrHandle: MethodHandle
    inline fun PyObject_SetAttr(o: MemoryAddress, attr_name: MemoryAddress, v: MemoryAddress): Int = PyObject_SetAttrHandle.invoke(o, attr_name, v) as Int
    val PyObject_SetAttrStringHandle: MethodHandle
    inline fun PyObject_SetAttrString(o: MemoryAddress, attr_name: String, v: MemoryAddress): Int = PyObject_SetAttrStringHandle.invoke(o, CLinker.toCString(attr_name, ResourceScope.newConfinedScope()).address(), v) as Int
    val PyObject_GenericSetAttrHandle: MethodHandle
    inline fun PyObject_GenericSetAttr(o: MemoryAddress, name: MemoryAddress, value: MemoryAddress): Int = PyObject_GenericSetAttrHandle.invoke(o, name, value) as Int
    val PyObject_DelAttrHandle: MethodHandle
    inline fun PyObject_DelAttr(o: MemoryAddress, attr_name: MemoryAddress): Int = PyObject_DelAttrHandle.invoke(o, attr_name) as Int
    val PyObject_DelAttrStringHandle: MethodHandle
    inline fun PyObject_DelAttrString(o: MemoryAddress, attr_name: String): Int = PyObject_DelAttrStringHandle.invoke(o, CLinker.toCString(attr_name, ResourceScope.newConfinedScope()).address()) as Int
    val PyObject_RichCompareHandle: MethodHandle
    inline fun PyObject_RichCompare(o1: MemoryAddress, o2: MemoryAddress, opid: Int): MemoryAddress? = PyObject_RichCompareHandle.invoke(o1, o2, opid) as MemoryAddress?
    val PyObject_RichCompareBoolHandle: MethodHandle
    inline fun PyObject_RichCompareBool(o1: MemoryAddress, o2: MemoryAddress, opid: Int): Int = PyObject_RichCompareBoolHandle.invoke(o1, o2, opid) as Int
    val PyObject_FormatHandle: MethodHandle
    inline fun PyObject_Format(obj: MemoryAddress, format_spec: MemoryAddress): MemoryAddress? = PyObject_FormatHandle.invoke(obj, format_spec) as MemoryAddress?
    val PyObject_ReprHandle: MethodHandle
    inline fun PyObject_Repr(o: MemoryAddress): MemoryAddress? = PyObject_ReprHandle.invoke(o) as MemoryAddress?
    val PyObject_ASCIIHandle: MethodHandle
    inline fun PyObject_ASCII(o: MemoryAddress): MemoryAddress? = PyObject_ASCIIHandle.invoke(o) as MemoryAddress?
    val PyObject_StrHandle: MethodHandle
    inline fun PyObject_Str(o: MemoryAddress): MemoryAddress? = PyObject_StrHandle.invoke(o) as MemoryAddress?
    val PyObject_BytesHandle: MethodHandle
    inline fun PyObject_Bytes(o: MemoryAddress): MemoryAddress? = PyObject_BytesHandle.invoke(o) as MemoryAddress?
    val PyObject_IsSubclassHandle: MethodHandle
    inline fun PyObject_IsSubclass(derived: MemoryAddress, cls: MemoryAddress): Int = PyObject_IsSubclassHandle.invoke(derived, cls) as Int
    val PyObject_IsInstanceHandle: MethodHandle
    inline fun PyObject_IsInstance(inst: MemoryAddress, cls: MemoryAddress): Int = PyObject_IsInstanceHandle.invoke(inst, cls) as Int
    val PyObject_IsTrueHandle: MethodHandle
    inline fun PyObject_IsTrue(o: MemoryAddress): Int = PyObject_IsTrueHandle.invoke(o) as Int
    val PyObject_NotHandle: MethodHandle
    inline fun PyObject_Not(o: MemoryAddress): Int = PyObject_NotHandle.invoke(o) as Int
    val PyObject_TypeHandle: MethodHandle
    inline fun PyObject_Type(o: MemoryAddress): MemoryAddress? = PyObject_TypeHandle.invoke(o) as MemoryAddress?
    val PyObject_GetItemHandle: MethodHandle
    inline fun PyObject_GetItem(o: MemoryAddress, key: MemoryAddress): MemoryAddress? = PyObject_GetItemHandle.invoke(o, key) as MemoryAddress?
    val PyObject_SetItemHandle: MethodHandle
    inline fun PyObject_SetItem(o: MemoryAddress, key: MemoryAddress, v: MemoryAddress): Int = PyObject_SetItemHandle.invoke(o, key, v) as Int
    val PyObject_DelItemHandle: MethodHandle
    inline fun PyObject_DelItem(o: MemoryAddress, key: MemoryAddress): Int = PyObject_DelItemHandle.invoke(o, key) as Int
    val PyObject_DirHandle: MethodHandle
    inline fun PyObject_Dir(o: MemoryAddress): MemoryAddress? = PyObject_DirHandle.invoke(o) as MemoryAddress?
    val PyObject_GetIterHandle: MethodHandle
    inline fun PyObject_GetIter(o: MemoryAddress): MemoryAddress? = PyObject_GetIterHandle.invoke(o) as MemoryAddress?
    val PyObject_GetAIterHandle: MethodHandle
    inline fun PyObject_GetAIter(o: MemoryAddress): MemoryAddress? = PyObject_GetAIterHandle.invoke(o) as MemoryAddress?


    // Section 11
    val PyVectorcall_CallHandle: MethodHandle
    inline fun PyVectorcall_Call(callable: MemoryAddress, tuple: MemoryAddress, dict: MemoryAddress): MemoryAddress? = PyVectorcall_CallHandle.invoke(callable, tuple, dict) as MemoryAddress?
    val PyObject_CallHandle: MethodHandle
    inline fun PyObject_Call(callable: MemoryAddress, args: MemoryAddress, kwargs: MemoryAddress): MemoryAddress? = PyObject_CallHandle.invoke(callable, args, kwargs) as MemoryAddress?
    val PyObject_CallNoArgsHandle: MethodHandle
    inline fun PyObject_CallNoArgs(callable: MemoryAddress): MemoryAddress? = PyObject_CallNoArgsHandle.invoke(callable) as MemoryAddress?
    val PyObject_CallObjectHandle: MethodHandle
    inline fun PyObject_CallObject(callable: MemoryAddress, args: MemoryAddress): MemoryAddress? = PyObject_CallObjectHandle.invoke(callable, args) as MemoryAddress?
    val PyCallable_CheckHandle: MethodHandle
    inline fun PyCallable_Check(o: MemoryAddress): Int = PyCallable_CheckHandle.invoke(o) as Int


    // Section 12
    val PyNumber_CheckHandle: MethodHandle
    inline fun PyNumber_Check(o: MemoryAddress): Int = PyNumber_CheckHandle.invoke(o) as Int
    val PyNumber_AddHandle: MethodHandle
    inline fun PyNumber_Add(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_AddHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_SubtractHandle: MethodHandle
    inline fun PyNumber_Subtract(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_SubtractHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_MultiplyHandle: MethodHandle
    inline fun PyNumber_Multiply(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_MultiplyHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_MatrixMultiplyHandle: MethodHandle
    inline fun PyNumber_MatrixMultiply(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_MatrixMultiplyHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_FloorDivideHandle: MethodHandle
    inline fun PyNumber_FloorDivide(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_FloorDivideHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_TrueDivideHandle: MethodHandle
    inline fun PyNumber_TrueDivide(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_TrueDivideHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_RemainderHandle: MethodHandle
    inline fun PyNumber_Remainder(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_RemainderHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_DivmodHandle: MethodHandle
    inline fun PyNumber_Divmod(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_DivmodHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_PowerHandle: MethodHandle
    inline fun PyNumber_Power(o1: MemoryAddress, o2: MemoryAddress, o3: MemoryAddress): MemoryAddress? = PyNumber_PowerHandle.invoke(o1, o2, o3) as MemoryAddress?
    val PyNumber_NegativeHandle: MethodHandle
    inline fun PyNumber_Negative(o: MemoryAddress): MemoryAddress? = PyNumber_NegativeHandle.invoke(o) as MemoryAddress?
    val PyNumber_PositiveHandle: MethodHandle
    inline fun PyNumber_Positive(o: MemoryAddress): MemoryAddress? = PyNumber_PositiveHandle.invoke(o) as MemoryAddress?
    val PyNumber_AbsoluteHandle: MethodHandle
    inline fun PyNumber_Absolute(o: MemoryAddress): MemoryAddress? = PyNumber_AbsoluteHandle.invoke(o) as MemoryAddress?
    val PyNumber_InvertHandle: MethodHandle
    inline fun PyNumber_Invert(o: MemoryAddress): MemoryAddress? = PyNumber_InvertHandle.invoke(o) as MemoryAddress?
    val PyNumber_LshiftHandle: MethodHandle
    inline fun PyNumber_Lshift(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_LshiftHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_RshiftHandle: MethodHandle
    inline fun PyNumber_Rshift(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_RshiftHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_AndHandle: MethodHandle
    inline fun PyNumber_And(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_AndHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_XorHandle: MethodHandle
    inline fun PyNumber_Xor(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_XorHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_OrHandle: MethodHandle
    inline fun PyNumber_Or(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_OrHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceAddHandle: MethodHandle
    inline fun PyNumber_InPlaceAdd(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceAddHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceSubtractHandle: MethodHandle
    inline fun PyNumber_InPlaceSubtract(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceSubtractHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceMultiplyHandle: MethodHandle
    inline fun PyNumber_InPlaceMultiply(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceMultiplyHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceMatrixMultiplyHandle: MethodHandle
    inline fun PyNumber_InPlaceMatrixMultiply(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceMatrixMultiplyHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceFloorDivideHandle: MethodHandle
    inline fun PyNumber_InPlaceFloorDivide(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceFloorDivideHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceTrueDivideHandle: MethodHandle
    inline fun PyNumber_InPlaceTrueDivide(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceTrueDivideHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceRemainderHandle: MethodHandle
    inline fun PyNumber_InPlaceRemainder(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceRemainderHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlacePowerHandle: MethodHandle
    inline fun PyNumber_InPlacePower(o1: MemoryAddress, o2: MemoryAddress, o3: MemoryAddress): MemoryAddress? = PyNumber_InPlacePowerHandle.invoke(o1, o2, o3) as MemoryAddress?
    val PyNumber_InPlaceLshiftHandle: MethodHandle
    inline fun PyNumber_InPlaceLshift(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceLshiftHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceRshiftHandle: MethodHandle
    inline fun PyNumber_InPlaceRshift(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceRshiftHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceAndHandle: MethodHandle
    inline fun PyNumber_InPlaceAnd(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceAndHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceXorHandle: MethodHandle
    inline fun PyNumber_InPlaceXor(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceXorHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_InPlaceOrHandle: MethodHandle
    inline fun PyNumber_InPlaceOr(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PyNumber_InPlaceOrHandle.invoke(o1, o2) as MemoryAddress?
    val PyNumber_LongHandle: MethodHandle
    inline fun PyNumber_Long(o: MemoryAddress): MemoryAddress? = PyNumber_LongHandle.invoke(o) as MemoryAddress?
    val PyNumber_FloatHandle: MethodHandle
    inline fun PyNumber_Float(o: MemoryAddress): MemoryAddress? = PyNumber_FloatHandle.invoke(o) as MemoryAddress?
    val PyNumber_IndexHandle: MethodHandle
    inline fun PyNumber_Index(o: MemoryAddress): MemoryAddress? = PyNumber_IndexHandle.invoke(o) as MemoryAddress?
    val PyNumber_ToBaseHandle: MethodHandle
    inline fun PyNumber_ToBase(n: MemoryAddress, base: Int): MemoryAddress? = PyNumber_ToBaseHandle.invoke(n, base) as MemoryAddress?
    val PyIndex_CheckHandle: MethodHandle
    inline fun PyIndex_Check(o: MemoryAddress): Int = PyIndex_CheckHandle.invoke(o) as Int


    // Section 13
    val PySequence_CheckHandle: MethodHandle
    inline fun PySequence_Check(o: MemoryAddress): Int = PySequence_CheckHandle.invoke(o) as Int
    val PySequence_ConcatHandle: MethodHandle
    inline fun PySequence_Concat(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PySequence_ConcatHandle.invoke(o1, o2) as MemoryAddress?
    val PySequence_InPlaceConcatHandle: MethodHandle
    inline fun PySequence_InPlaceConcat(o1: MemoryAddress, o2: MemoryAddress): MemoryAddress? = PySequence_InPlaceConcatHandle.invoke(o1, o2) as MemoryAddress?
    val PySequence_ContainsHandle: MethodHandle
    inline fun PySequence_Contains(o: MemoryAddress, value: MemoryAddress): Int = PySequence_ContainsHandle.invoke(o, value) as Int
    val PySequence_ListHandle: MethodHandle
    inline fun PySequence_List(o: MemoryAddress): MemoryAddress? = PySequence_ListHandle.invoke(o) as MemoryAddress?
    val PySequence_TupleHandle: MethodHandle
    inline fun PySequence_Tuple(o: MemoryAddress): MemoryAddress? = PySequence_TupleHandle.invoke(o) as MemoryAddress?
    val PySequence_FastHandle: MethodHandle
    inline fun PySequence_Fast(o: MemoryAddress, m: String): MemoryAddress? = PySequence_FastHandle.invoke(o, CLinker.toCString(m, ResourceScope.newConfinedScope()).address()) as MemoryAddress?


    // Section 14
    val PyMapping_CheckHandle: MethodHandle
    inline fun PyMapping_Check(o: MemoryAddress): Int = PyMapping_CheckHandle.invoke(o) as Int
    val PyMapping_GetItemStringHandle: MethodHandle
    inline fun PyMapping_GetItemString(o: MemoryAddress, key: String): MemoryAddress? = PyMapping_GetItemStringHandle.invoke(o, CLinker.toCString(key, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyMapping_SetItemStringHandle: MethodHandle
    inline fun PyMapping_SetItemString(o: MemoryAddress, key: String, v: MemoryAddress): Int = PyMapping_SetItemStringHandle.invoke(o, CLinker.toCString(key, ResourceScope.newConfinedScope()).address(), v) as Int
    val PyMapping_HasKeyWithErrorHandle: MethodHandle
    inline fun PyMapping_HasKeyWithError(o: MemoryAddress, key: MemoryAddress): Int = PyMapping_HasKeyWithErrorHandle.invoke(o, key) as Int
    val PyMapping_HasKeyStringWithErrorHandle: MethodHandle
    inline fun PyMapping_HasKeyStringWithError(o: MemoryAddress, key: String): Int = PyMapping_HasKeyStringWithErrorHandle.invoke(o, CLinker.toCString(key, ResourceScope.newConfinedScope()).address()) as Int
    val PyMapping_HasKeyHandle: MethodHandle
    inline fun PyMapping_HasKey(o: MemoryAddress, key: MemoryAddress): Int = PyMapping_HasKeyHandle.invoke(o, key) as Int
    val PyMapping_HasKeyStringHandle: MethodHandle
    inline fun PyMapping_HasKeyString(o: MemoryAddress, key: String): Int = PyMapping_HasKeyStringHandle.invoke(o, CLinker.toCString(key, ResourceScope.newConfinedScope()).address()) as Int
    val PyMapping_KeysHandle: MethodHandle
    inline fun PyMapping_Keys(o: MemoryAddress): MemoryAddress? = PyMapping_KeysHandle.invoke(o) as MemoryAddress?
    val PyMapping_ValuesHandle: MethodHandle
    inline fun PyMapping_Values(o: MemoryAddress): MemoryAddress? = PyMapping_ValuesHandle.invoke(o) as MemoryAddress?
    val PyMapping_ItemsHandle: MethodHandle
    inline fun PyMapping_Items(o: MemoryAddress): MemoryAddress? = PyMapping_ItemsHandle.invoke(o) as MemoryAddress?


    // Section 15
    val PyIter_CheckHandle: MethodHandle
    inline fun PyIter_Check(o: MemoryAddress): Int = PyIter_CheckHandle.invoke(o) as Int
    val PyAIter_CheckHandle: MethodHandle
    inline fun PyAIter_Check(o: MemoryAddress): Int = PyAIter_CheckHandle.invoke(o) as Int
    val PyIter_NextHandle: MethodHandle
    inline fun PyIter_Next(o: MemoryAddress): MemoryAddress? = PyIter_NextHandle.invoke(o) as MemoryAddress?


    // Section 16
    val PyLong_FromLongLongHandle: MethodHandle
    inline fun PyLong_FromLongLong(v: Long): MemoryAddress? = PyLong_FromLongLongHandle.invoke(v) as MemoryAddress?
    val PyLong_FromDoubleHandle: MethodHandle
    inline fun PyLong_FromDouble(v: kotlin.Double): MemoryAddress? = PyLong_FromDoubleHandle.invoke(v) as MemoryAddress?
    val PyLong_AsIntHandle: MethodHandle
    inline fun PyLong_AsInt(obj: MemoryAddress): Int = PyLong_AsIntHandle.invoke(obj) as Int
    val PyLong_AsLongLongHandle: MethodHandle
    inline fun PyLong_AsLongLong(obj: MemoryAddress): Long = PyLong_AsLongLongHandle.invoke(obj) as Long
    val PyLong_AsDoubleHandle: MethodHandle
    inline fun PyLong_AsDouble(pylong: MemoryAddress): kotlin.Double = PyLong_AsDoubleHandle.invoke(pylong) as kotlin.Double
    val PyLong_GetInfoHandle: MethodHandle
    inline fun PyLong_GetInfo(): MemoryAddress? = PyLong_GetInfoHandle.invoke() as MemoryAddress?


    // Section 17
    val PyBool_FromLongHandle: MethodHandle
    inline fun PyBool_FromLong(v: Long): MemoryAddress? = PyBool_FromLongHandle.invoke(v) as MemoryAddress?


    // Section 18
    val PyFloat_FromStringHandle: MethodHandle
    inline fun PyFloat_FromString(str: MemoryAddress): MemoryAddress? = PyFloat_FromStringHandle.invoke(str) as MemoryAddress?
    val PyFloat_FromDoubleHandle: MethodHandle
    inline fun PyFloat_FromDouble(v: kotlin.Double): MemoryAddress? = PyFloat_FromDoubleHandle.invoke(v) as MemoryAddress?
    val PyFloat_AsDoubleHandle: MethodHandle
    inline fun PyFloat_AsDouble(pyfloat: MemoryAddress): kotlin.Double = PyFloat_AsDoubleHandle.invoke(pyfloat) as kotlin.Double
    val PyFloat_GetInfoHandle: MethodHandle
    inline fun PyFloat_GetInfo(): MemoryAddress? = PyFloat_GetInfoHandle.invoke() as MemoryAddress?
    val PyFloat_GetMaxHandle: MethodHandle
    inline fun PyFloat_GetMax(): kotlin.Double = PyFloat_GetMaxHandle.invoke() as kotlin.Double
    val PyFloat_GetMinHandle: MethodHandle
    inline fun PyFloat_GetMin(): kotlin.Double = PyFloat_GetMinHandle.invoke() as kotlin.Double


    // Section 19
    val PyBytes_FromStringHandle: MethodHandle
    inline fun PyBytes_FromString(v: String): MemoryAddress? = PyBytes_FromStringHandle.invoke(CLinker.toCString(v, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyBytes_FromObjectHandle: MethodHandle
    inline fun PyBytes_FromObject(o: MemoryAddress): MemoryAddress? = PyBytes_FromObjectHandle.invoke(o) as MemoryAddress?
    val PyBytes_AsStringHandle: MethodHandle
    inline fun PyBytes_AsString(o: MemoryAddress): String? = CLinker.toJavaString(PyBytes_AsStringHandle.invoke(o) as MemoryAddress?)


    // Section 20
    val PyByteArray_FromObjectHandle: MethodHandle
    inline fun PyByteArray_FromObject(o: MemoryAddress): MemoryAddress? = PyByteArray_FromObjectHandle.invoke(o) as MemoryAddress?
    val PyByteArray_ConcatHandle: MethodHandle
    inline fun PyByteArray_Concat(a: MemoryAddress, b: MemoryAddress): MemoryAddress? = PyByteArray_ConcatHandle.invoke(a, b) as MemoryAddress?
    val PyByteArray_AsStringHandle: MethodHandle
    inline fun PyByteArray_AsString(bytearray: MemoryAddress): String? = CLinker.toJavaString(PyByteArray_AsStringHandle.invoke(bytearray) as MemoryAddress?)


    // Section 21
    val PyUnicode_IsIdentifierHandle: MethodHandle
    inline fun PyUnicode_IsIdentifier(unicode: MemoryAddress): Int = PyUnicode_IsIdentifierHandle.invoke(unicode) as Int
    val PyUnicode_FromStringHandle: MethodHandle
    inline fun PyUnicode_FromString(str: String): MemoryAddress? = PyUnicode_FromStringHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_FromObjectHandle: MethodHandle
    inline fun PyUnicode_FromObject(obj: MemoryAddress): MemoryAddress? = PyUnicode_FromObjectHandle.invoke(obj) as MemoryAddress?
    val PyUnicode_FromEncodedObjectHandle: MethodHandle
    inline fun PyUnicode_FromEncodedObject(obj: MemoryAddress, encoding: String, errors: String): MemoryAddress? = PyUnicode_FromEncodedObjectHandle.invoke(obj, CLinker.toCString(encoding, ResourceScope.newConfinedScope()).address(), CLinker.toCString(errors, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_DecodeLocaleHandle: MethodHandle
    inline fun PyUnicode_DecodeLocale(str: String, errors: String): MemoryAddress? = PyUnicode_DecodeLocaleHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address(), CLinker.toCString(errors, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_EncodeLocaleHandle: MethodHandle
    inline fun PyUnicode_EncodeLocale(unicode: MemoryAddress, errors: String): MemoryAddress? = PyUnicode_EncodeLocaleHandle.invoke(unicode, CLinker.toCString(errors, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_DecodeFSDefaultHandle: MethodHandle
    inline fun PyUnicode_DecodeFSDefault(str: String): MemoryAddress? = PyUnicode_DecodeFSDefaultHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_EncodeFSDefaultHandle: MethodHandle
    inline fun PyUnicode_EncodeFSDefault(unicode: MemoryAddress): MemoryAddress? = PyUnicode_EncodeFSDefaultHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsEncodedStringHandle: MethodHandle
    inline fun PyUnicode_AsEncodedString(unicode: MemoryAddress, encoding: String, errors: String): MemoryAddress? = PyUnicode_AsEncodedStringHandle.invoke(unicode, CLinker.toCString(encoding, ResourceScope.newConfinedScope()).address(), CLinker.toCString(errors, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_AsUTF8StringHandle: MethodHandle
    inline fun PyUnicode_AsUTF8String(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsUTF8StringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsUTF8Handle: MethodHandle // 수동 추가
    inline fun PyUnicode_AsUTF8(unicode: MemoryAddress): String? = CLinker.toJavaString(PyUnicode_AsUTF8Handle.invoke(unicode) as MemoryAddress?)
    val PyUnicode_AsUTF32StringHandle: MethodHandle
    inline fun PyUnicode_AsUTF32String(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsUTF32StringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsUTF16StringHandle: MethodHandle
    inline fun PyUnicode_AsUTF16String(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsUTF16StringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsUnicodeEscapeStringHandle: MethodHandle
    inline fun PyUnicode_AsUnicodeEscapeString(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsUnicodeEscapeStringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsRawUnicodeEscapeStringHandle: MethodHandle
    inline fun PyUnicode_AsRawUnicodeEscapeString(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsRawUnicodeEscapeStringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsLatin1StringHandle: MethodHandle
    inline fun PyUnicode_AsLatin1String(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsLatin1StringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsASCIIStringHandle: MethodHandle
    inline fun PyUnicode_AsASCIIString(unicode: MemoryAddress): MemoryAddress? = PyUnicode_AsASCIIStringHandle.invoke(unicode) as MemoryAddress?
    val PyUnicode_AsCharmapStringHandle: MethodHandle
    inline fun PyUnicode_AsCharmapString(unicode: MemoryAddress, mapping: MemoryAddress): MemoryAddress? = PyUnicode_AsCharmapStringHandle.invoke(unicode, mapping) as MemoryAddress?
    val PyUnicode_TranslateHandle: MethodHandle
    inline fun PyUnicode_Translate(unicode: MemoryAddress, table: MemoryAddress, errors: String): MemoryAddress? = PyUnicode_TranslateHandle.invoke(unicode, table, CLinker.toCString(errors, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyUnicode_ConcatHandle: MethodHandle
    inline fun PyUnicode_Concat(left: MemoryAddress, right: MemoryAddress): MemoryAddress? = PyUnicode_ConcatHandle.invoke(left, right) as MemoryAddress?
    val PyUnicode_SplitlinesHandle: MethodHandle
    inline fun PyUnicode_Splitlines(unicode: MemoryAddress, keepends: Int): MemoryAddress? = PyUnicode_SplitlinesHandle.invoke(unicode, keepends) as MemoryAddress?
    val PyUnicode_JoinHandle: MethodHandle
    inline fun PyUnicode_Join(separator: MemoryAddress, seq: MemoryAddress): MemoryAddress? = PyUnicode_JoinHandle.invoke(separator, seq) as MemoryAddress?
    val PyUnicode_CompareHandle: MethodHandle
    inline fun PyUnicode_Compare(left: MemoryAddress, right: MemoryAddress): Int = PyUnicode_CompareHandle.invoke(left, right) as Int
    val PyUnicode_EqualToUTF8Handle: MethodHandle
    inline fun PyUnicode_EqualToUTF8(unicode: MemoryAddress, string: String): Int = PyUnicode_EqualToUTF8Handle.invoke(unicode, CLinker.toCString(string, ResourceScope.newConfinedScope()).address()) as Int
    val PyUnicode_CompareWithASCIIStringHandle: MethodHandle
    inline fun PyUnicode_CompareWithASCIIString(unicode: MemoryAddress, string: String): Int = PyUnicode_CompareWithASCIIStringHandle.invoke(unicode, CLinker.toCString(string, ResourceScope.newConfinedScope()).address()) as Int
    val PyUnicode_RichCompareHandle: MethodHandle
    inline fun PyUnicode_RichCompare(left: MemoryAddress, right: MemoryAddress, op: Int): MemoryAddress? = PyUnicode_RichCompareHandle.invoke(left, right, op) as MemoryAddress?
    val PyUnicode_FormatHandle: MethodHandle
    inline fun PyUnicode_Format(format: MemoryAddress, args: MemoryAddress): MemoryAddress? = PyUnicode_FormatHandle.invoke(format, args) as MemoryAddress?
    val PyUnicode_ContainsHandle: MethodHandle
    inline fun PyUnicode_Contains(unicode: MemoryAddress, substr: MemoryAddress): Int = PyUnicode_ContainsHandle.invoke(unicode, substr) as Int
    val PyUnicode_InternFromStringHandle: MethodHandle
    inline fun PyUnicode_InternFromString(str: String): MemoryAddress? = PyUnicode_InternFromStringHandle.invoke(CLinker.toCString(str, ResourceScope.newConfinedScope()).address()) as MemoryAddress?


    // Section 22
    val PyList_AppendHandle: MethodHandle
    inline fun PyList_Append(list: MemoryAddress, item: MemoryAddress): Int = PyList_AppendHandle.invoke(list, item) as Int
    val PyList_SortHandle: MethodHandle
    inline fun PyList_Sort(list: MemoryAddress): Int = PyList_SortHandle.invoke(list) as Int
    val PyList_ReverseHandle: MethodHandle
    inline fun PyList_Reverse(list: MemoryAddress): Int = PyList_ReverseHandle.invoke(list) as Int
    val PyList_AsTupleHandle: MethodHandle
    inline fun PyList_AsTuple(list: MemoryAddress): MemoryAddress? = PyList_AsTupleHandle.invoke(list) as MemoryAddress?


    // Section 23
    val PyDict_NewHandle: MethodHandle
    inline fun PyDict_New(): MemoryAddress? = PyDict_NewHandle.invoke() as MemoryAddress?
    val PyDictProxy_NewHandle: MethodHandle
    inline fun PyDictProxy_New(mapping: MemoryAddress): MemoryAddress? = PyDictProxy_NewHandle.invoke(mapping) as MemoryAddress?
    val PyDict_ClearHandle: MethodHandle
    inline fun PyDict_Clear(p: MemoryAddress) = PyDict_ClearHandle.invoke(p) as Unit
    val PyDict_ContainsHandle: MethodHandle
    inline fun PyDict_Contains(p: MemoryAddress, key: MemoryAddress): Int = PyDict_ContainsHandle.invoke(p, key) as Int
    val PyDict_CopyHandle: MethodHandle
    inline fun PyDict_Copy(p: MemoryAddress): MemoryAddress? = PyDict_CopyHandle.invoke(p) as MemoryAddress?
    val PyDict_SetItemHandle: MethodHandle
    inline fun PyDict_SetItem(p: MemoryAddress, key: MemoryAddress, v: MemoryAddress): Int = PyDict_SetItemHandle.invoke(p, key, v) as Int
    val PyDict_SetItemStringHandle: MethodHandle
    inline fun PyDict_SetItemString(p: MemoryAddress, key: String, v: MemoryAddress): Int = PyDict_SetItemStringHandle.invoke(p, CLinker.toCString(key, ResourceScope.newConfinedScope()).address(), v) as Int
    val PyDict_DelItemHandle: MethodHandle
    inline fun PyDict_DelItem(p: MemoryAddress, key: MemoryAddress): Int = PyDict_DelItemHandle.invoke(p, key) as Int
    val PyDict_DelItemStringHandle: MethodHandle
    inline fun PyDict_DelItemString(p: MemoryAddress, key: String): Int = PyDict_DelItemStringHandle.invoke(p, CLinker.toCString(key, ResourceScope.newConfinedScope()).address()) as Int
    val PyDict_GetItemHandle: MethodHandle
    inline fun PyDict_GetItem(p: MemoryAddress, key: MemoryAddress): MemoryAddress? = PyDict_GetItemHandle.invoke(p, key) as MemoryAddress?
    val PyDict_GetItemWithErrorHandle: MethodHandle
    inline fun PyDict_GetItemWithError(p: MemoryAddress, key: MemoryAddress): MemoryAddress? = PyDict_GetItemWithErrorHandle.invoke(p, key) as MemoryAddress?
    val PyDict_GetItemStringHandle: MethodHandle
    inline fun PyDict_GetItemString(p: MemoryAddress, key: String): MemoryAddress? = PyDict_GetItemStringHandle.invoke(p, CLinker.toCString(key, ResourceScope.newConfinedScope()).address()) as MemoryAddress?
    val PyDict_ItemsHandle: MethodHandle
    inline fun PyDict_Items(p: MemoryAddress): MemoryAddress? = PyDict_ItemsHandle.invoke(p) as MemoryAddress?
    val PyDict_KeysHandle: MethodHandle
    inline fun PyDict_Keys(p: MemoryAddress): MemoryAddress? = PyDict_KeysHandle.invoke(p) as MemoryAddress?
    val PyDict_ValuesHandle: MethodHandle
    inline fun PyDict_Values(p: MemoryAddress): MemoryAddress? = PyDict_ValuesHandle.invoke(p) as MemoryAddress?
    val PyDict_MergeHandle: MethodHandle
    inline fun PyDict_Merge(a: MemoryAddress, b: MemoryAddress, override: Int): Int = PyDict_MergeHandle.invoke(a, b, override) as Int
    val PyDict_UpdateHandle: MethodHandle
    inline fun PyDict_Update(a: MemoryAddress, b: MemoryAddress): Int = PyDict_UpdateHandle.invoke(a, b) as Int
    val PyDict_MergeFromSeq2Handle: MethodHandle
    inline fun PyDict_MergeFromSeq2(a: MemoryAddress, seq2: MemoryAddress, override: Int): Int = PyDict_MergeFromSeq2Handle.invoke(a, seq2, override) as Int


    // Section 24
    val PySet_NewHandle: MethodHandle
    inline fun PySet_New(iterable: MemoryAddress): MemoryAddress? = PySet_NewHandle.invoke(iterable) as MemoryAddress?
    val PyFrozenSet_NewHandle: MethodHandle
    inline fun PyFrozenSet_New(iterable: MemoryAddress): MemoryAddress? = PyFrozenSet_NewHandle.invoke(iterable) as MemoryAddress?
    val PySet_ContainsHandle: MethodHandle
    inline fun PySet_Contains(anyset: MemoryAddress, key: MemoryAddress): Int = PySet_ContainsHandle.invoke(anyset, key) as Int
    val PySet_AddHandle: MethodHandle
    inline fun PySet_Add(set: MemoryAddress, key: MemoryAddress): Int = PySet_AddHandle.invoke(set, key) as Int
    val PySet_DiscardHandle: MethodHandle
    inline fun PySet_Discard(set: MemoryAddress, key: MemoryAddress): Int = PySet_DiscardHandle.invoke(set, key) as Int
    val PySet_PopHandle: MethodHandle
    inline fun PySet_Pop(set: MemoryAddress): MemoryAddress? = PySet_PopHandle.invoke(set) as MemoryAddress?
    val PySet_ClearHandle: MethodHandle
    inline fun PySet_Clear(set: MemoryAddress): Int = PySet_ClearHandle.invoke(set) as Int


    // Section 25
    val PySeqIter_NewHandle: MethodHandle
    inline fun PySeqIter_New(seq: MemoryAddress): MemoryAddress? = PySeqIter_NewHandle.invoke(seq) as MemoryAddress?
    val PyCallIter_NewHandle: MethodHandle
    inline fun PyCallIter_New(callable: MemoryAddress, sentinel: MemoryAddress): MemoryAddress? = PyCallIter_NewHandle.invoke(callable, sentinel) as MemoryAddress?


    // Section 26
    val PyWeakref_NewRefHandle: MethodHandle
    inline fun PyWeakref_NewRef(ob: MemoryAddress, callback: MemoryAddress): MemoryAddress? = PyWeakref_NewRefHandle.invoke(ob, callback) as MemoryAddress?
    val PyWeakref_NewProxyHandle: MethodHandle
    inline fun PyWeakref_NewProxy(ob: MemoryAddress, callback: MemoryAddress): MemoryAddress? = PyWeakref_NewProxyHandle.invoke(ob, callback) as MemoryAddress?
    val PyWeakref_GetObjectHandle: MethodHandle
    inline fun PyWeakref_GetObject(ref: MemoryAddress): MemoryAddress? = PyWeakref_GetObjectHandle.invoke(ref) as MemoryAddress?
    val PyObject_ClearWeakRefsHandle: MethodHandle
    inline fun PyObject_ClearWeakRefs(o: MemoryAddress) = PyObject_ClearWeakRefsHandle.invoke(o) as Unit


    // Section 27
    val PyType_IsSubtypeHandle: MethodHandle
    inline fun PyType_IsSubtype(a: MemoryAddress, b: MemoryAddress): Int = PyType_IsSubtypeHandle.invoke(a, b) as Int
    val PyType_ReadyHandle: MethodHandle
    inline fun PyType_Ready(type: MemoryAddress): Int = PyType_ReadyHandle.invoke(type) as Int
    val PyType_GetNameHandle: MethodHandle
    inline fun PyType_GetName(type: MemoryAddress): MemoryAddress? = PyType_GetNameHandle.invoke(type) as MemoryAddress?
    val PyType_GetFullyQualifiedName: MethodHandle
    inline fun PyType_GetFullyQualifiedName(type: MemoryAddress): MemoryAddress? = PyType_GetFullyQualifiedName.invoke(type) as MemoryAddress?
    val PyType_GetModuleNameHandle: MethodHandle
    inline fun PyType_GetModuleName(type: MemoryAddress): MemoryAddress? = PyType_GetModuleNameHandle.invoke(type) as MemoryAddress?
    val PyType_GetModuleHandle: MethodHandle
    inline fun PyType_GetModule(type: MemoryAddress): MemoryAddress? = PyType_GetModuleHandle.invoke(type) as MemoryAddress?
    
    
    // Section 28
    val PyTuple_NewHandle: MethodHandle
    fun PyTuple_New(len: Long): MemoryAddress? = PyTuple_NewHandle.invoke(len) as MemoryAddress?
    val PyTuple_SizeHandle: MethodHandle
    inline fun PyTuple_Size(p: MemoryAddress): Long = PyTuple_SizeHandle.invoke(p) as Long
    val PyTuple_GetItemHandle: MethodHandle
    fun PyTuple_GetItem(p: MemoryAddress, pos: Long): MemoryAddress? = PyTuple_GetItemHandle.invoke(p, pos) as MemoryAddress?
    val PyTuple_GetSliceHandle: MethodHandle
    fun PyTuple_GetSlice(p: MemoryAddress, low: Long, high: Long): MemoryAddress? = PyTuple_GetSliceHandle.invoke(p, low, high) as MemoryAddress?
    val PyTuple_SetItemHandle: MethodHandle
    inline fun PyTuple_SetItem(p: MemoryAddress, pos: Long, o: MemoryAddress): Int = PyTuple_SetItemHandle.invoke(p, pos, o) as Int

    init {
        ResourceScope.newConfinedScope().run {
            val lookup = MethodLookup(manager::loadLibPython)

            /**
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
            */


            //**************************************************
            // Section 1
            Py_InitializeHandle = lookup.find("Py_Initialize", Void.TYPE)
            Py_InitializeExHandle = lookup.find("Py_InitializeEx", Void.TYPE, Integer.TYPE)
            Py_IsInitializedHandle = lookup.find("Py_IsInitialized", Integer.TYPE)
            Py_IsFinalizingHandle = lookup.find("Py_IsFinalizing", Integer.TYPE)
            Py_FinalizeExHandle = lookup.find("Py_FinalizeEx", Integer.TYPE)
            Py_FinalizeHandle = lookup.find("Py_Finalize", Void.TYPE)
            Py_RunMainHandle = lookup.find("Py_FinalizeEx", Integer.TYPE)  // TODO: Fix this // 수동 추가
            Py_GetVersionHandle = lookup.find("Py_GetVersion", MemoryAddress::class.java)
            Py_GetPlatformHandle = lookup.find("Py_GetPlatform", MemoryAddress::class.java)
            Py_GetCopyrightHandle = lookup.find("Py_GetCopyright", MemoryAddress::class.java)
            Py_GetCompilerHandle = lookup.find("Py_GetCompiler", MemoryAddress::class.java)
            Py_GetBuildInfoHandle = lookup.find("Py_GetBuildInfo", MemoryAddress::class.java)
            PyEval_InitThreadsHandle = lookup.find("PyEval_InitThreads", Void.TYPE)
            PyThreadState_GetDictHandle = lookup.find("PyThreadState_GetDict", MemoryAddress::class.java)


            // Section 2
            PyRun_SimpleStringHandle = lookup.find("PyRun_SimpleString", Integer.TYPE, MemoryAddress::class.java) // 수동 추가
            PyRun_StringHandle = lookup.find("PyRun_String", MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java) // 수동 추가
            Py_CompileStringHandle = lookup.find("Py_CompileString", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyEval_EvalCodeHandle = lookup.find("PyEval_EvalCode", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 3
            PyErr_ClearHandle = lookup.find("PyErr_Clear", Void.TYPE)
            PyErr_PrintExHandle = lookup.find("PyErr_PrintEx", Void.TYPE, Integer.TYPE)
            PyErr_PrintHandle = lookup.find("PyErr_Print", Void.TYPE)
            PyErr_WriteUnraisableHandle = lookup.find("PyErr_WriteUnraisable", Void.TYPE, MemoryAddress::class.java)
            PyErr_DisplayExceptionHandle = lookup.find("PyErr_DisplayException", Void.TYPE, MemoryAddress::class.java)
            PyErr_SetStringHandle = lookup.find("PyErr_SetString", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetObjectHandle = lookup.find("PyErr_SetObject", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetNoneHandle = lookup.find("PyErr_SetNone", Void.TYPE, MemoryAddress::class.java)
            PyErr_BadArgumentHandle = lookup.find("PyErr_BadArgument", Integer.TYPE)
            PyErr_NoMemoryHandle = lookup.find("PyErr_NoMemory", MemoryAddress::class.java)
            PyErr_SetFromErrnoHandle = lookup.find("PyErr_SetFromErrno", MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetFromErrnoWithFilenameObjectHandle = lookup.find("PyErr_SetFromErrnoWithFilenameObject", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetFromErrnoWithFilenameObjectsHandle = lookup.find("PyErr_SetFromErrnoWithFilenameObjects", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetFromErrnoWithFilenameHandle = lookup.find("PyErr_SetFromErrnoWithFilename", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetImportErrorHandle = lookup.find("PyErr_SetImportError", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SetImportErrorSubclassHandle = lookup.find("PyErr_SetImportErrorSubclass", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_SyntaxLocationExHandle = lookup.find("PyErr_SyntaxLocationEx", Void.TYPE, MemoryAddress::class.java, Integer.TYPE, Integer.TYPE)
            PyErr_SyntaxLocationHandle = lookup.find("PyErr_SyntaxLocation", Void.TYPE, MemoryAddress::class.java, Integer.TYPE)
            PyErr_BadInternalCallHandle = lookup.find("PyErr_BadInternalCall", Void.TYPE)
            PyErr_WarnExplicitHandle = lookup.find("PyErr_WarnExplicit", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_OccurredHandle = lookup.find("PyErr_Occurred", MemoryAddress::class.java)
            PyErr_ExceptionMatchesHandle = lookup.find("PyErr_ExceptionMatches", Integer.TYPE, MemoryAddress::class.java)
            PyErr_GivenExceptionMatchesHandle = lookup.find("PyErr_GivenExceptionMatches", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_GetRaisedExceptionHandle = lookup.find("PyErr_GetRaisedException", MemoryAddress::class.java)
            PyErr_SetRaisedExceptionHandle = lookup.find("PyErr_SetRaisedException", Void.TYPE, MemoryAddress::class.java)
            PyErr_RestoreHandle = lookup.find("PyErr_Restore", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_GetHandledExceptionHandle = lookup.find("PyErr_GetHandledException", MemoryAddress::class.java)
            PyErr_SetHandledExceptionHandle = lookup.find("PyErr_SetHandledException", Void.TYPE, MemoryAddress::class.java)
            PyErr_SetExcInfoHandle = lookup.find("PyErr_SetExcInfo", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_CheckSignalsHandle = lookup.find("PyErr_CheckSignals", Integer.TYPE)
            PyErr_SetInterruptHandle = lookup.find("PyErr_SetInterrupt", Void.TYPE)
            PyErr_SetInterruptExHandle = lookup.find("PyErr_SetInterruptEx", Integer.TYPE, Integer.TYPE)
            PyErr_NewExceptionHandle = lookup.find("PyErr_NewException", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyErr_NewExceptionWithDocHandle = lookup.find("PyErr_NewExceptionWithDoc", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_GetTracebackHandle = lookup.find("PyException_GetTraceback", MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_SetTracebackHandle = lookup.find("PyException_SetTraceback", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_GetContextHandle = lookup.find("PyException_GetContext", MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_SetContextHandle = lookup.find("PyException_SetContext", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_GetCauseHandle = lookup.find("PyException_GetCause", MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_SetCauseHandle = lookup.find("PyException_SetCause", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_GetArgsHandle = lookup.find("PyException_GetArgs", MemoryAddress::class.java, MemoryAddress::class.java)
            PyException_SetArgsHandle = lookup.find("PyException_SetArgs", Void.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicodeEncodeError_GetEncodingHandle = lookup.find("PyUnicodeEncodeError_GetEncoding", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicodeTranslateError_GetObjectHandle = lookup.find("PyUnicodeTranslateError_GetObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicodeTranslateError_GetReasonHandle = lookup.find("PyUnicodeTranslateError_GetReason", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicodeTranslateError_SetReasonHandle = lookup.find("PyUnicodeTranslateError_SetReason", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            Py_EnterRecursiveCallHandle = lookup.find("Py_EnterRecursiveCall", Integer.TYPE, MemoryAddress::class.java)
            Py_LeaveRecursiveCallHandle = lookup.find("Py_LeaveRecursiveCall", Void.TYPE)
            Py_ReprEnterHandle = lookup.find("Py_ReprEnter", Integer.TYPE, MemoryAddress::class.java)
            Py_ReprLeaveHandle = lookup.find("Py_ReprLeave", Void.TYPE, MemoryAddress::class.java)


            // Section 4
            Py_NewRefHandle = lookup.find("Py_NewRef", MemoryAddress::class.java, MemoryAddress::class.java)
            Py_XNewRefHandle = lookup.find("Py_XNewRef", MemoryAddress::class.java, MemoryAddress::class.java)
            Py_IncRefHandle = lookup.find("Py_IncRef", Void.TYPE, MemoryAddress::class.java)
            Py_DecRefHandle = lookup.find("Py_DecRef", Void.TYPE, MemoryAddress::class.java)


            // Section 5
            PyOS_FSPathHandle = lookup.find("PyOS_FSPath", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 6
            PySys_GetObjectHandle = lookup.find("PySys_GetObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PySys_SetObjectHandle = lookup.find("PySys_SetObject", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PySys_ResetWarnOptionsHandle = lookup.find("PySys_ResetWarnOptions", Void.TYPE)
            PySys_GetXOptionsHandle = lookup.find("PySys_GetXOptions", MemoryAddress::class.java)
            PySys_AuditTupleHandle = lookup.find("PySys_AuditTuple", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 7
            Py_FatalErrorHandle = lookup.find("Py_FatalError", Void.TYPE, MemoryAddress::class.java)
            Py_ExitHandle = lookup.find("Py_Exit", Void.TYPE, Integer.TYPE)


            // Section 8
            PyImport_ImportModuleHandle = lookup.find("PyImport_ImportModule", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ImportModuleNoBlockHandle = lookup.find("PyImport_ImportModuleNoBlock", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ImportModuleLevelObjectHandle = lookup.find("PyImport_ImportModuleLevelObject", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyImport_ImportModuleLevelHandle = lookup.find("PyImport_ImportModuleLevel", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyImport_ImportHandle = lookup.find("PyImport_Import", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ReloadModuleHandle = lookup.find("PyImport_ReloadModule", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_AddModuleRefHandle = lookup.find("PyImport_AddModuleRef", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_AddModuleObjectHandle = lookup.find("PyImport_AddModuleObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_AddModuleHandle = lookup.find("PyImport_AddModule", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ExecCodeModuleHandle = lookup.find("PyImport_ExecCodeModule", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ExecCodeModuleExHandle = lookup.find("PyImport_ExecCodeModuleEx", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ExecCodeModuleObjectHandle = lookup.find("PyImport_ExecCodeModuleObject", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ExecCodeModuleWithPathnamesHandle = lookup.find("PyImport_ExecCodeModuleWithPathnames", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_GetMagicTagHandle = lookup.find("PyImport_GetMagicTag", MemoryAddress::class.java)
            PyImport_GetModuleDictHandle = lookup.find("PyImport_GetModuleDict", MemoryAddress::class.java)
            PyImport_GetModuleHandle = lookup.find("PyImport_GetModule", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_GetImporterHandle = lookup.find("PyImport_GetImporter", MemoryAddress::class.java, MemoryAddress::class.java)
            PyImport_ImportFrozenModuleObjectHandle = lookup.find("PyImport_ImportFrozenModuleObject", Integer.TYPE, MemoryAddress::class.java)
            PyImport_ImportFrozenModuleHandle = lookup.find("PyImport_ImportFrozenModule", Integer.TYPE, MemoryAddress::class.java)


            // Section 9
            PyEval_GetBuiltinsHandle = lookup.find("PyEval_GetBuiltins", MemoryAddress::class.java)
            PyEval_GetLocalsHandle = lookup.find("PyEval_GetLocals", MemoryAddress::class.java)
            PyEval_GetGlobalsHandle = lookup.find("PyEval_GetGlobals", MemoryAddress::class.java)
            PyEval_GetFrameBuiltinsHandle = lookup.find("PyEval_GetFrameBuiltins", MemoryAddress::class.java)
            PyEval_GetFrameLocalsHandle = lookup.find("PyEval_GetFrameLocals", MemoryAddress::class.java)
            PyEval_GetFrameGlobalsHandle = lookup.find("PyEval_GetFrameGlobals", MemoryAddress::class.java)
            PyEval_GetFuncNameHandle = lookup.find("PyEval_GetFuncName", MemoryAddress::class.java, MemoryAddress::class.java)
            PyEval_GetFuncDescHandle = lookup.find("PyEval_GetFuncDesc", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 10
            PyObject_HasAttrWithErrorHandle = lookup.find("PyObject_HasAttrWithError", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_HasAttrStringWithErrorHandle = lookup.find("PyObject_HasAttrStringWithError", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_HasAttrHandle = lookup.find("PyObject_HasAttr", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_HasAttrStringHandle = lookup.find("PyObject_HasAttrString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GetAttrHandle = lookup.find("PyObject_GetAttr", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GetAttrStringHandle = lookup.find("PyObject_GetAttrString", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GenericGetAttrHandle = lookup.find("PyObject_GenericGetAttr", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_SetAttrHandle = lookup.find("PyObject_SetAttr", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_SetAttrStringHandle = lookup.find("PyObject_SetAttrString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GenericSetAttrHandle = lookup.find("PyObject_GenericSetAttr", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_DelAttrHandle = lookup.find("PyObject_DelAttr", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_DelAttrStringHandle = lookup.find("PyObject_DelAttrString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_RichCompareHandle = lookup.find("PyObject_RichCompare", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyObject_RichCompareBoolHandle = lookup.find("PyObject_RichCompareBool", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyObject_FormatHandle = lookup.find("PyObject_Format", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_ReprHandle = lookup.find("PyObject_Repr", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_ASCIIHandle = lookup.find("PyObject_ASCII", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_StrHandle = lookup.find("PyObject_Str", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_BytesHandle = lookup.find("PyObject_Bytes", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_IsSubclassHandle = lookup.find("PyObject_IsSubclass", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_IsInstanceHandle = lookup.find("PyObject_IsInstance", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_IsTrueHandle = lookup.find("PyObject_IsTrue", Integer.TYPE, MemoryAddress::class.java)
            PyObject_NotHandle = lookup.find("PyObject_Not", Integer.TYPE, MemoryAddress::class.java)
            PyObject_TypeHandle = lookup.find("PyObject_Type", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GetItemHandle = lookup.find("PyObject_GetItem", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_SetItemHandle = lookup.find("PyObject_SetItem", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_DelItemHandle = lookup.find("PyObject_DelItem", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_DirHandle = lookup.find("PyObject_Dir", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GetIterHandle = lookup.find("PyObject_GetIter", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_GetAIterHandle = lookup.find("PyObject_GetAIter", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 11
            PyVectorcall_CallHandle = lookup.find("PyVectorcall_Call", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_CallHandle = lookup.find("PyObject_Call", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_CallNoArgsHandle = lookup.find("PyObject_CallNoArgs", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_CallObjectHandle = lookup.find("PyObject_CallObject", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyCallable_CheckHandle = lookup.find("PyCallable_Check", Integer.TYPE, MemoryAddress::class.java)


            // Section 12
            PyNumber_CheckHandle = lookup.find("PyNumber_Check", Integer.TYPE, MemoryAddress::class.java)
            PyNumber_AddHandle = lookup.find("PyNumber_Add", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_SubtractHandle = lookup.find("PyNumber_Subtract", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_MultiplyHandle = lookup.find("PyNumber_Multiply", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_MatrixMultiplyHandle = lookup.find("PyNumber_MatrixMultiply", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_FloorDivideHandle = lookup.find("PyNumber_FloorDivide", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_TrueDivideHandle = lookup.find("PyNumber_TrueDivide", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_RemainderHandle = lookup.find("PyNumber_Remainder", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_DivmodHandle = lookup.find("PyNumber_Divmod", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_PowerHandle = lookup.find("PyNumber_Power", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_NegativeHandle = lookup.find("PyNumber_Negative", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_PositiveHandle = lookup.find("PyNumber_Positive", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_AbsoluteHandle = lookup.find("PyNumber_Absolute", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InvertHandle = lookup.find("PyNumber_Invert", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_LshiftHandle = lookup.find("PyNumber_Lshift", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_RshiftHandle = lookup.find("PyNumber_Rshift", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_AndHandle = lookup.find("PyNumber_And", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_XorHandle = lookup.find("PyNumber_Xor", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_OrHandle = lookup.find("PyNumber_Or", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceAddHandle = lookup.find("PyNumber_InPlaceAdd", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceSubtractHandle = lookup.find("PyNumber_InPlaceSubtract", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceMultiplyHandle = lookup.find("PyNumber_InPlaceMultiply", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceMatrixMultiplyHandle = lookup.find("PyNumber_InPlaceMatrixMultiply", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceFloorDivideHandle = lookup.find("PyNumber_InPlaceFloorDivide", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceTrueDivideHandle = lookup.find("PyNumber_InPlaceTrueDivide", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceRemainderHandle = lookup.find("PyNumber_InPlaceRemainder", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlacePowerHandle = lookup.find("PyNumber_InPlacePower", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceLshiftHandle = lookup.find("PyNumber_InPlaceLshift", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceRshiftHandle = lookup.find("PyNumber_InPlaceRshift", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceAndHandle = lookup.find("PyNumber_InPlaceAnd", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceXorHandle = lookup.find("PyNumber_InPlaceXor", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_InPlaceOrHandle = lookup.find("PyNumber_InPlaceOr", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_LongHandle = lookup.find("PyNumber_Long", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_FloatHandle = lookup.find("PyNumber_Float", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_IndexHandle = lookup.find("PyNumber_Index", MemoryAddress::class.java, MemoryAddress::class.java)
            PyNumber_ToBaseHandle = lookup.find("PyNumber_ToBase", MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyIndex_CheckHandle = lookup.find("PyIndex_Check", Integer.TYPE, MemoryAddress::class.java)


            // Section 13
            PySequence_CheckHandle = lookup.find("PySequence_Check", Integer.TYPE, MemoryAddress::class.java)
            PySequence_ConcatHandle = lookup.find("PySequence_Concat", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PySequence_InPlaceConcatHandle = lookup.find("PySequence_InPlaceConcat", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PySequence_ContainsHandle = lookup.find("PySequence_Contains", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PySequence_ListHandle = lookup.find("PySequence_List", MemoryAddress::class.java, MemoryAddress::class.java)
            PySequence_TupleHandle = lookup.find("PySequence_Tuple", MemoryAddress::class.java, MemoryAddress::class.java)
            PySequence_FastHandle = lookup.find("PySequence_Fast", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 14
            PyMapping_CheckHandle = lookup.find("PyMapping_Check", Integer.TYPE, MemoryAddress::class.java)
            PyMapping_GetItemStringHandle = lookup.find("PyMapping_GetItemString", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_SetItemStringHandle = lookup.find("PyMapping_SetItemString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_HasKeyWithErrorHandle = lookup.find("PyMapping_HasKeyWithError", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_HasKeyStringWithErrorHandle = lookup.find("PyMapping_HasKeyStringWithError", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_HasKeyHandle = lookup.find("PyMapping_HasKey", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_HasKeyStringHandle = lookup.find("PyMapping_HasKeyString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_KeysHandle = lookup.find("PyMapping_Keys", MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_ValuesHandle = lookup.find("PyMapping_Values", MemoryAddress::class.java, MemoryAddress::class.java)
            PyMapping_ItemsHandle = lookup.find("PyMapping_Items", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 15
            PyIter_CheckHandle = lookup.find("PyIter_Check", Integer.TYPE, MemoryAddress::class.java)
            PyAIter_CheckHandle = lookup.find("PyAIter_Check", Integer.TYPE, MemoryAddress::class.java)
            PyIter_NextHandle = lookup.find("PyIter_Next", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 16
            PyLong_FromLongLongHandle = lookup.find("PyLong_FromLongLong", MemoryAddress::class.java, LongLong.TYPE)
            PyLong_FromDoubleHandle = lookup.find("PyLong_FromDouble", MemoryAddress::class.java, Double.TYPE)
            PyLong_AsIntHandle = lookup.find("PyLong_AsInt", Integer.TYPE, MemoryAddress::class.java)
            PyLong_AsLongLongHandle = lookup.find("PyLong_AsLongLong", LongLong.TYPE, MemoryAddress::class.java)
            PyLong_AsDoubleHandle = lookup.find("PyLong_AsDouble", Double.TYPE, MemoryAddress::class.java)
            PyLong_GetInfoHandle = lookup.find("PyLong_GetInfo", MemoryAddress::class.java)


            // Section 17
            PyBool_FromLongHandle = lookup.find("PyBool_FromLong", MemoryAddress::class.java, Integer.TYPE)


            // Section 18
            PyFloat_FromStringHandle = lookup.find("PyFloat_FromString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyFloat_FromDoubleHandle = lookup.find("PyFloat_FromDouble", MemoryAddress::class.java, Double.TYPE)
            PyFloat_AsDoubleHandle = lookup.find("PyFloat_AsDouble", Double.TYPE, MemoryAddress::class.java)
            PyFloat_GetInfoHandle = lookup.find("PyFloat_GetInfo", MemoryAddress::class.java)
            PyFloat_GetMaxHandle = lookup.find("PyFloat_GetMax", Double.TYPE)
            PyFloat_GetMinHandle = lookup.find("PyFloat_GetMin", Double.TYPE)


            // Section 19
            PyBytes_FromStringHandle = lookup.find("PyBytes_FromString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyBytes_FromObjectHandle = lookup.find("PyBytes_FromObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PyBytes_AsStringHandle = lookup.find("PyBytes_AsString", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 20
            PyByteArray_FromObjectHandle = lookup.find("PyByteArray_FromObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PyByteArray_ConcatHandle = lookup.find("PyByteArray_Concat", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyByteArray_AsStringHandle = lookup.find("PyByteArray_AsString", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 21
            PyUnicode_IsIdentifierHandle = lookup.find("PyUnicode_IsIdentifier", Integer.TYPE, MemoryAddress::class.java)
            PyUnicode_FromStringHandle = lookup.find("PyUnicode_FromString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_FromObjectHandle = lookup.find("PyUnicode_FromObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_FromEncodedObjectHandle = lookup.find("PyUnicode_FromEncodedObject", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_DecodeLocaleHandle = lookup.find("PyUnicode_DecodeLocale", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_EncodeLocaleHandle = lookup.find("PyUnicode_EncodeLocale", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_DecodeFSDefaultHandle = lookup.find("PyUnicode_DecodeFSDefault", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_EncodeFSDefaultHandle = lookup.find("PyUnicode_EncodeFSDefault", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsEncodedStringHandle = lookup.find("PyUnicode_AsEncodedString", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsUTF8StringHandle = lookup.find("PyUnicode_AsUTF8String", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsUTF8Handle = lookup.find("PyUnicode_AsUTF8", MemoryAddress::class.java, MemoryAddress::class.java) // 수동 추가
            PyUnicode_AsUTF32StringHandle = lookup.find("PyUnicode_AsUTF32String", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsUTF16StringHandle = lookup.find("PyUnicode_AsUTF16String", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsUnicodeEscapeStringHandle = lookup.find("PyUnicode_AsUnicodeEscapeString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsRawUnicodeEscapeStringHandle = lookup.find("PyUnicode_AsRawUnicodeEscapeString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsLatin1StringHandle = lookup.find("PyUnicode_AsLatin1String", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsASCIIStringHandle = lookup.find("PyUnicode_AsASCIIString", MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_AsCharmapStringHandle = lookup.find("PyUnicode_AsCharmapString", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_TranslateHandle = lookup.find("PyUnicode_Translate", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_ConcatHandle = lookup.find("PyUnicode_Concat", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_SplitlinesHandle = lookup.find("PyUnicode_Splitlines", MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyUnicode_JoinHandle = lookup.find("PyUnicode_Join", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_CompareHandle = lookup.find("PyUnicode_Compare", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_EqualToUTF8Handle = lookup.find("PyUnicode_EqualToUTF8", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_CompareWithASCIIStringHandle = lookup.find("PyUnicode_CompareWithASCIIString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_RichCompareHandle = lookup.find("PyUnicode_RichCompare", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyUnicode_FormatHandle = lookup.find("PyUnicode_Format", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_ContainsHandle = lookup.find("PyUnicode_Contains", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyUnicode_InternFromStringHandle = lookup.find("PyUnicode_InternFromString", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 22
            PyList_AppendHandle = lookup.find("PyList_Append", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyList_SortHandle = lookup.find("PyList_Sort", Integer.TYPE, MemoryAddress::class.java)
            PyList_ReverseHandle = lookup.find("PyList_Reverse", Integer.TYPE, MemoryAddress::class.java)
            PyList_AsTupleHandle = lookup.find("PyList_AsTuple", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 23
            PyDict_NewHandle = lookup.find("PyDict_New", MemoryAddress::class.java)
            PyDictProxy_NewHandle = lookup.find("PyDictProxy_New", MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_ClearHandle = lookup.find("PyDict_Clear", Void.TYPE, MemoryAddress::class.java)
            PyDict_ContainsHandle = lookup.find("PyDict_Contains", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_CopyHandle = lookup.find("PyDict_Copy", MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_SetItemHandle = lookup.find("PyDict_SetItem", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_SetItemStringHandle = lookup.find("PyDict_SetItemString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_DelItemHandle = lookup.find("PyDict_DelItem", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_DelItemStringHandle = lookup.find("PyDict_DelItemString", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_GetItemHandle = lookup.find("PyDict_GetItem", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_GetItemWithErrorHandle = lookup.find("PyDict_GetItemWithError", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_GetItemStringHandle = lookup.find("PyDict_GetItemString", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_ItemsHandle = lookup.find("PyDict_Items", MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_KeysHandle = lookup.find("PyDict_Keys", MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_ValuesHandle = lookup.find("PyDict_Values", MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_MergeHandle = lookup.find("PyDict_Merge", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)
            PyDict_UpdateHandle = lookup.find("PyDict_Update", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyDict_MergeFromSeq2Handle = lookup.find("PyDict_MergeFromSeq2", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java, Integer.TYPE)


            // Section 24
            PySet_NewHandle = lookup.find("PySet_New", MemoryAddress::class.java, MemoryAddress::class.java)
            PyFrozenSet_NewHandle = lookup.find("PyFrozenSet_New", MemoryAddress::class.java, MemoryAddress::class.java)
            PySet_ContainsHandle = lookup.find("PySet_Contains", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PySet_AddHandle = lookup.find("PySet_Add", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PySet_DiscardHandle = lookup.find("PySet_Discard", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PySet_PopHandle = lookup.find("PySet_Pop", MemoryAddress::class.java, MemoryAddress::class.java)
            PySet_ClearHandle = lookup.find("PySet_Clear", Integer.TYPE, MemoryAddress::class.java)


            // Section 25
            PySeqIter_NewHandle = lookup.find("PySeqIter_New", MemoryAddress::class.java, MemoryAddress::class.java)
            PyCallIter_NewHandle = lookup.find("PyCallIter_New", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 26
            PyWeakref_NewRefHandle = lookup.find("PyWeakref_NewRef", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyWeakref_NewProxyHandle = lookup.find("PyWeakref_NewProxy", MemoryAddress::class.java, MemoryAddress::class.java, MemoryAddress::class.java)
            PyWeakref_GetObjectHandle = lookup.find("PyWeakref_GetObject", MemoryAddress::class.java, MemoryAddress::class.java)
            PyObject_ClearWeakRefsHandle = lookup.find("PyObject_ClearWeakRefs", Void.TYPE, MemoryAddress::class.java)


            // Section 27
            PyType_IsSubtypeHandle = lookup.find("PyType_IsSubtype", Integer.TYPE, MemoryAddress::class.java, MemoryAddress::class.java)
            PyType_ReadyHandle = lookup.find("PyType_Ready", Integer.TYPE, MemoryAddress::class.java)
            PyType_GetNameHandle = lookup.find("PyType_GetName", MemoryAddress::class.java, MemoryAddress::class.java)
            PyType_GetFullyQualifiedName = lookup.find("PyType_GetFullyQualifiedName", MemoryAddress::class.java, MemoryAddress::class.java)
            PyType_GetModuleNameHandle = lookup.find("PyType_GetModuleName", MemoryAddress::class.java, MemoryAddress::class.java)
            PyType_GetModuleHandle = lookup.find("PyType_GetModule", MemoryAddress::class.java, MemoryAddress::class.java)


            // Section 28
            PyTuple_NewHandle = lookup.find("PyTuple_New", MemoryAddress::class.java, LongLong.TYPE)
            PyTuple_SizeHandle = lookup.find("PyTuple_Size", LongLong.TYPE, MemoryAddress::class.java)
            PyTuple_GetItemHandle = lookup.find("PyTuple_GetItem", MemoryAddress::class.java, MemoryAddress::class.java, LongLong.TYPE)
            PyTuple_GetSliceHandle = lookup.find("PyTuple_GetSlice", MemoryAddress::class.java, MemoryAddress::class.java, LongLong.TYPE, LongLong.TYPE)
            PyTuple_SetItemHandle = lookup.find("PyTuple_SetItem", Integer.TYPE, MemoryAddress::class.java, LongLong.TYPE, MemoryAddress::class.java)
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
