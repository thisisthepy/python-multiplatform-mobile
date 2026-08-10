package python.native.ffi

import jdk.incubator.foreign.MemoryAddress
import jdk.incubator.foreign.ResourceScope


actual inline fun <R : Any> memScoped(block: () -> R): R = ResourceScope.newSharedScope().run {
    return block()
}


@JvmInline
internal value class NativeAddressValue(val ptr: MemoryAddress): AddressValue {
    override fun toString(): String = ptr
        .toString()
        .replace(": ", "=")
        .replace(" offset=", ", offset=")
        .replace("{ ", "(").replace(" }", ")")

}
@HighOverheadNativeCall
actual fun NativePointer.toAddressValue(): AddressValue = NativeAddressValue(toPlatformPointer())
actual inline fun NativePointer.toRawValue(): Long = toPlatformPointer().toRawLongValue()
inline fun NativePointer.toPlatformPointer(): MemoryAddress = this.address as MemoryAddress
@HighOverheadNativeCall
actual fun AddressValue.toNativePointer(): NativePointer = NativePointer((this as NativeAddressValue).ptr)
@HighOverheadNativeCall
actual fun Long.toNativePointer(): NativePointer? = MemoryAddress.ofLong(this).toNativePointer()
internal inline fun MemoryAddress?.toNativePointer(): NativePointer? = this?.let { NativePointer(it) }


/**
// Section 1
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
//actual inline fun Py_InitializeFromConfig(config) = python.native.ffi.bindings.Py_InitializeFromConfig()
actual inline fun Py_IsInitialized() = python.native.ffi.bindings.Py_IsInitialized()
actual inline fun Py_IsFinalizing() = python.native.ffi.bindings.Py_IsFinalizing()
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()
actual inline fun Py_RunMain(): Int = python.native.ffi.bindings.Py_RunMain()
actual inline fun PyRun_SimpleString(command: String): Int = python.native.ffi.bindings.PyRun_SimpleString(command)
actual fun PyRun_String(
    str: String, start: Int, globals: NativePointer, locals: NativePointer
): NativePointer? = python.native.ffi.bindings.PyRun_String(
    str, start, globals.toPlatformPointer(), locals.toPlatformPointer()
).toNativePointer()
actual inline fun Py_GetVersion(): String? = python.native.ffi.bindings.Py_GetVersion()
actual inline fun Py_GetPlatform(): String? = python.native.ffi.bindings.Py_GetPlatform()
actual inline fun Py_GetCopyright(): String? = python.native.ffi.bindings.Py_GetCopyright()
actual inline fun Py_GetCompiler(): String? = python.native.ffi.bindings.Py_GetCompiler()
actual inline fun Py_GetBuildInfo(): String? = python.native.ffi.bindings.Py_GetBuildInfo()


// Section 2
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toNativePointer()





actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toNativePointer()
actual inline fun PyLong_AsLongLong(p: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(p.toPlatformPointer())
actual inline fun PyLong_AsInt(p: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(p.toPlatformPointer())



actual fun PyUnicode_FromString(str: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_FromString(str).toNativePointer()
actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? =
    python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer())
*/


//**************************************************
// Section 1
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_Initialize()
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeEx(initsigs)
actual inline fun Py_IsInitialized(): Int = python.native.ffi.bindings.Py_IsInitialized()
actual inline fun Py_IsFinalizing(): Int = python.native.ffi.bindings.Py_IsFinalizing()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeEx()
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_Finalize()
// actual inline fun Py_BytesMain(argc: Int, argv: List<String>): Int // 수동 추가
actual inline fun Py_RunMain(): Int = python.native.ffi.bindings.Py_RunMain() // 수동 추가
actual inline fun Py_GetVersion(): String? = python.native.ffi.bindings.Py_GetVersion()
actual inline fun Py_GetPlatform(): String? = python.native.ffi.bindings.Py_GetPlatform()
actual inline fun Py_GetCopyright(): String? = python.native.ffi.bindings.Py_GetCopyright()
actual inline fun Py_GetCompiler(): String? = python.native.ffi.bindings.Py_GetCompiler()
actual inline fun Py_GetBuildInfo(): String? = python.native.ffi.bindings.Py_GetBuildInfo()
actual inline fun PyEval_InitThreads() = python.native.ffi.bindings.PyEval_InitThreads()
actual fun PyThreadState_GetDict(): NativePointer? = python.native.ffi.bindings.PyThreadState_GetDict().toNativePointer()


// Section 2
actual inline fun PyRun_SimpleString(command: String): Int = python.native.ffi.bindings.PyRun_SimpleString(command) // 수동 추가
actual fun PyRun_String(str: String, start: Int, globals: NativePointer, locals: NativePointer): NativePointer? = python.native.ffi.bindings.PyRun_String(str, start, globals.toPlatformPointer(), locals.toPlatformPointer()).toNativePointer() // 수동 추가
actual fun Py_CompileString(str: String, filename: String, start: Int): NativePointer? = python.native.ffi.bindings.Py_CompileString(str, filename, start).toNativePointer()
actual fun PyEval_EvalCode(co: NativePointer, globals: NativePointer, locals: NativePointer): NativePointer? = python.native.ffi.bindings.PyEval_EvalCode(co.toPlatformPointer(), globals.toPlatformPointer(), locals.toPlatformPointer()).toNativePointer()


// Section 3
actual inline fun PyErr_Clear() = python.native.ffi.bindings.PyErr_Clear()
actual inline fun PyErr_PrintEx(set_sys_last_vars: Int) = python.native.ffi.bindings.PyErr_PrintEx(set_sys_last_vars)
actual inline fun PyErr_Print() = python.native.ffi.bindings.PyErr_Print()
actual inline fun PyErr_WriteUnraisable(obj: NativePointer) = python.native.ffi.bindings.PyErr_WriteUnraisable(obj.toPlatformPointer())
actual inline fun PyErr_DisplayException(exc: NativePointer) = python.native.ffi.bindings.PyErr_DisplayException(exc.toPlatformPointer())
actual inline fun PyErr_SetString(type: NativePointer, message: String) = python.native.ffi.bindings.PyErr_SetString(type.toPlatformPointer(), message)
actual inline fun PyErr_SetObject(type: NativePointer, value: NativePointer) = python.native.ffi.bindings.PyErr_SetObject(type.toPlatformPointer(), value.toPlatformPointer())
actual inline fun PyErr_SetNone(type: NativePointer) = python.native.ffi.bindings.PyErr_SetNone(type.toPlatformPointer())
actual inline fun PyErr_BadArgument(): Int = python.native.ffi.bindings.PyErr_BadArgument()
actual fun PyErr_NoMemory(): NativePointer? = python.native.ffi.bindings.PyErr_NoMemory().toNativePointer()
actual fun PyErr_SetFromErrno(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrno(type.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetFromErrnoWithFilenameObject(type: NativePointer, filenameObject: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrnoWithFilenameObject(type.toPlatformPointer(), filenameObject.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetFromErrnoWithFilenameObjects(type: NativePointer, filenameObject: NativePointer, filenameObject2: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrnoWithFilenameObjects(type.toPlatformPointer(), filenameObject.toPlatformPointer(), filenameObject2.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetFromErrnoWithFilename(type: NativePointer, filename: String): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrnoWithFilename(type.toPlatformPointer(), filename).toNativePointer()
actual fun PyErr_SetImportError(msg: NativePointer, name: NativePointer, path: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetImportError(msg.toPlatformPointer(), name.toPlatformPointer(), path.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetImportErrorSubclass(exception: NativePointer, msg: NativePointer, name: NativePointer, path: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetImportErrorSubclass(exception.toPlatformPointer(), msg.toPlatformPointer(), name.toPlatformPointer(), path.toPlatformPointer()).toNativePointer()
actual inline fun PyErr_SyntaxLocationEx(filename: String, lineno: Int, col_offset: Int) = python.native.ffi.bindings.PyErr_SyntaxLocationEx(filename, lineno, col_offset)
actual inline fun PyErr_SyntaxLocation(filename: String, lineno: Int) = python.native.ffi.bindings.PyErr_SyntaxLocation(filename, lineno)
actual inline fun PyErr_BadInternalCall() = python.native.ffi.bindings.PyErr_BadInternalCall()
actual inline fun PyErr_WarnExplicit(category: NativePointer, message: String, filename: String, lineno: Int, module: String, registry: NativePointer): Int = python.native.ffi.bindings.PyErr_WarnExplicit(category.toPlatformPointer(), message, filename, lineno, module, registry.toPlatformPointer())
actual fun PyErr_Occurred(): NativePointer? = python.native.ffi.bindings.PyErr_Occurred().toNativePointer()
actual inline fun PyErr_ExceptionMatches(exc: NativePointer): Int = python.native.ffi.bindings.PyErr_ExceptionMatches(exc.toPlatformPointer())
actual inline fun PyErr_GivenExceptionMatches(given: NativePointer, exc: NativePointer): Int = python.native.ffi.bindings.PyErr_GivenExceptionMatches(given.toPlatformPointer(), exc.toPlatformPointer())
actual fun PyErr_GetRaisedException(): NativePointer? = python.native.ffi.bindings.PyErr_GetRaisedException().toNativePointer()
actual inline fun PyErr_SetRaisedException(exc: NativePointer) = python.native.ffi.bindings.PyErr_SetRaisedException(exc.toPlatformPointer())
actual inline fun PyErr_Restore(type: NativePointer, value: NativePointer, traceback: NativePointer) = python.native.ffi.bindings.PyErr_Restore(type.toPlatformPointer(), value.toPlatformPointer(), traceback.toPlatformPointer())
actual fun PyErr_GetHandledException(): NativePointer? = python.native.ffi.bindings.PyErr_GetHandledException().toNativePointer()
actual inline fun PyErr_SetHandledException(exc: NativePointer) = python.native.ffi.bindings.PyErr_SetHandledException(exc.toPlatformPointer())
actual inline fun PyErr_SetExcInfo(type: NativePointer, value: NativePointer, traceback: NativePointer) = python.native.ffi.bindings.PyErr_SetExcInfo(type.toPlatformPointer(), value.toPlatformPointer(), traceback.toPlatformPointer())
actual inline fun PyErr_CheckSignals(): Int = python.native.ffi.bindings.PyErr_CheckSignals()
actual inline fun PyErr_SetInterrupt() = python.native.ffi.bindings.PyErr_SetInterrupt()
actual inline fun PyErr_SetInterruptEx(signum: Int): Int = python.native.ffi.bindings.PyErr_SetInterruptEx(signum)
actual fun PyErr_NewException(name: String, base: NativePointer, dict: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_NewException(name, base.toPlatformPointer(), dict.toPlatformPointer()).toNativePointer()
actual fun PyErr_NewExceptionWithDoc(name: String, doc: String, base: NativePointer, dict: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_NewExceptionWithDoc(name, doc, base.toPlatformPointer(), dict.toPlatformPointer()).toNativePointer()
actual fun PyException_GetTraceback(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetTraceback(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetTraceback(ex: NativePointer, tb: NativePointer): Int = python.native.ffi.bindings.PyException_SetTraceback(ex.toPlatformPointer(), tb.toPlatformPointer())
actual fun PyException_GetContext(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetContext(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetContext(ex: NativePointer, ctx: NativePointer) = python.native.ffi.bindings.PyException_SetContext(ex.toPlatformPointer(), ctx.toPlatformPointer())
actual fun PyException_GetCause(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetCause(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetCause(ex: NativePointer, cause: NativePointer) = python.native.ffi.bindings.PyException_SetCause(ex.toPlatformPointer(), cause.toPlatformPointer())
actual fun PyException_GetArgs(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetArgs(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetArgs(ex: NativePointer, args: NativePointer) = python.native.ffi.bindings.PyException_SetArgs(ex.toPlatformPointer(), args.toPlatformPointer())
actual fun PyUnicodeEncodeError_GetEncoding(exc: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicodeEncodeError_GetEncoding(exc.toPlatformPointer()).toNativePointer()
actual fun PyUnicodeTranslateError_GetObject(exc: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicodeTranslateError_GetObject(exc.toPlatformPointer()).toNativePointer()
actual fun PyUnicodeTranslateError_GetReason(exc: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicodeTranslateError_GetReason(exc.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicodeTranslateError_SetReason(exc: NativePointer, reason: String): Int = python.native.ffi.bindings.PyUnicodeTranslateError_SetReason(exc.toPlatformPointer(), reason)
actual inline fun Py_EnterRecursiveCall(where: String): Int = python.native.ffi.bindings.Py_EnterRecursiveCall(where)
actual inline fun Py_LeaveRecursiveCall() = python.native.ffi.bindings.Py_LeaveRecursiveCall()
actual inline fun Py_ReprEnter(o: NativePointer): Int = python.native.ffi.bindings.Py_ReprEnter(o.toPlatformPointer())
actual inline fun Py_ReprLeave(o: NativePointer) = python.native.ffi.bindings.Py_ReprLeave(o.toPlatformPointer())


// Section 4
actual fun Py_NewRef(o: NativePointer): NativePointer? = python.native.ffi.bindings.Py_NewRef(o.toPlatformPointer()).toNativePointer()
actual fun Py_XNewRef(o: NativePointer): NativePointer? = python.native.ffi.bindings.Py_XNewRef(o.toPlatformPointer()).toNativePointer()
actual inline fun Py_IncRef(o: NativePointer) = python.native.ffi.bindings.Py_IncRef(o.toPlatformPointer())
actual inline fun Py_DecRef(o: NativePointer) = python.native.ffi.bindings.Py_DecRef(o.toPlatformPointer())


// Section 5
actual fun PyOS_FSPath(path: NativePointer): NativePointer? = python.native.ffi.bindings.PyOS_FSPath(path.toPlatformPointer()).toNativePointer()


// Section 6
actual fun PySys_GetObject(name: String): NativePointer? = python.native.ffi.bindings.PySys_GetObject(name).toNativePointer()
actual inline fun PySys_SetObject(name: String, v: NativePointer): Int = python.native.ffi.bindings.PySys_SetObject(name, v.toPlatformPointer())
actual inline fun PySys_ResetWarnOptions() = python.native.ffi.bindings.PySys_ResetWarnOptions()
actual fun PySys_GetXOptions(): NativePointer? = python.native.ffi.bindings.PySys_GetXOptions().toNativePointer()
actual inline fun PySys_AuditTuple(event: String, args: NativePointer): Int = python.native.ffi.bindings.PySys_AuditTuple(event, args.toPlatformPointer())


// Section 7
actual inline fun Py_FatalError(message: String) = python.native.ffi.bindings.Py_FatalError(message)
actual inline fun Py_Exit(status: Int) = python.native.ffi.bindings.Py_Exit(status)


// Section 8
actual fun PyImport_ImportModule(name: String): NativePointer? = python.native.ffi.bindings.PyImport_ImportModule(name).toNativePointer()
actual fun PyImport_ImportModuleNoBlock(name: String): NativePointer? = python.native.ffi.bindings.PyImport_ImportModuleNoBlock(name).toNativePointer()
actual fun PyImport_ImportModuleLevelObject(name: NativePointer, globals: NativePointer, locals: NativePointer, fromlist: NativePointer, level: Int): NativePointer? = python.native.ffi.bindings.PyImport_ImportModuleLevelObject(name.toPlatformPointer(), globals.toPlatformPointer(), locals.toPlatformPointer(), fromlist.toPlatformPointer(), level).toNativePointer()
actual fun PyImport_ImportModuleLevel(name: String, globals: NativePointer, locals: NativePointer, fromlist: NativePointer, level: Int): NativePointer? = python.native.ffi.bindings.PyImport_ImportModuleLevel(name, globals.toPlatformPointer(), locals.toPlatformPointer(), fromlist.toPlatformPointer(), level).toNativePointer()
actual fun PyImport_Import(name: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_Import(name.toPlatformPointer()).toNativePointer()
actual fun PyImport_ReloadModule(m: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_ReloadModule(m.toPlatformPointer()).toNativePointer()
actual fun PyImport_AddModuleRef(name: String): NativePointer? = python.native.ffi.bindings.PyImport_AddModuleRef(name).toNativePointer()
actual fun PyImport_AddModuleObject(name: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_AddModuleObject(name.toPlatformPointer()).toNativePointer()
actual fun PyImport_AddModule(name: String): NativePointer? = python.native.ffi.bindings.PyImport_AddModule(name).toNativePointer()
actual fun PyImport_ExecCodeModule(name: String, co: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_ExecCodeModule(name, co.toPlatformPointer()).toNativePointer()
actual fun PyImport_ExecCodeModuleEx(name: String, co: NativePointer, pathname: String): NativePointer? = python.native.ffi.bindings.PyImport_ExecCodeModuleEx(name, co.toPlatformPointer(), pathname).toNativePointer()
actual fun PyImport_ExecCodeModuleObject(name: NativePointer, co: NativePointer, pathname: NativePointer, cpathname: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_ExecCodeModuleObject(name.toPlatformPointer(), co.toPlatformPointer(), pathname.toPlatformPointer(), cpathname.toPlatformPointer()).toNativePointer()
actual fun PyImport_ExecCodeModuleWithPathnames(name: String, co: NativePointer, pathname: String, cpathname: String): NativePointer? = python.native.ffi.bindings.PyImport_ExecCodeModuleWithPathnames(name, co.toPlatformPointer(), pathname, cpathname).toNativePointer()
actual inline fun PyImport_GetMagicTag(): String? = python.native.ffi.bindings.PyImport_GetMagicTag()
actual fun PyImport_GetModuleDict(): NativePointer? = python.native.ffi.bindings.PyImport_GetModuleDict().toNativePointer()
actual fun PyImport_GetModule(name: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_GetModule(name.toPlatformPointer()).toNativePointer()
actual fun PyImport_GetImporter(path: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_GetImporter(path.toPlatformPointer()).toNativePointer()
actual inline fun PyImport_ImportFrozenModuleObject(name: NativePointer): Int = python.native.ffi.bindings.PyImport_ImportFrozenModuleObject(name.toPlatformPointer())
actual inline fun PyImport_ImportFrozenModule(name: String): Int = python.native.ffi.bindings.PyImport_ImportFrozenModule(name)


// Section 9
actual fun PyEval_GetBuiltins(): NativePointer? = python.native.ffi.bindings.PyEval_GetBuiltins().toNativePointer()
actual fun PyEval_GetLocals(): NativePointer? = python.native.ffi.bindings.PyEval_GetLocals().toNativePointer()
actual fun PyEval_GetGlobals(): NativePointer? = python.native.ffi.bindings.PyEval_GetGlobals().toNativePointer()
actual fun PyEval_GetFrameBuiltins(): NativePointer? = python.native.ffi.bindings.PyEval_GetFrameBuiltins().toNativePointer()
actual fun PyEval_GetFrameLocals(): NativePointer? = python.native.ffi.bindings.PyEval_GetFrameLocals().toNativePointer()
actual fun PyEval_GetFrameGlobals(): NativePointer? = python.native.ffi.bindings.PyEval_GetFrameGlobals().toNativePointer()
actual inline fun PyEval_GetFuncName(func: NativePointer): String? = python.native.ffi.bindings.PyEval_GetFuncName(func.toPlatformPointer())
actual inline fun PyEval_GetFuncDesc(func: NativePointer): String? = python.native.ffi.bindings.PyEval_GetFuncDesc(func.toPlatformPointer())


// Section 10
actual inline fun PyObject_HasAttrWithError(o: NativePointer, attr_name: NativePointer): Int = python.native.ffi.bindings.PyObject_HasAttrWithError(o.toPlatformPointer(), attr_name.toPlatformPointer())
actual inline fun PyObject_HasAttrStringWithError(o: NativePointer, attr_name: String): Int = python.native.ffi.bindings.PyObject_HasAttrStringWithError(o.toPlatformPointer(), attr_name)
actual inline fun PyObject_HasAttr(o: NativePointer, attr_name: NativePointer): Int = python.native.ffi.bindings.PyObject_HasAttr(o.toPlatformPointer(), attr_name.toPlatformPointer())
actual inline fun PyObject_HasAttrString(o: NativePointer, attr_name: String): Int = python.native.ffi.bindings.PyObject_HasAttrString(o.toPlatformPointer(), attr_name)
actual fun PyObject_GetAttr(o: NativePointer, attr_name: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetAttr(o.toPlatformPointer(), attr_name.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetAttrString(o: NativePointer, attr_name: String): NativePointer? = python.native.ffi.bindings.PyObject_GetAttrString(o.toPlatformPointer(), attr_name).toNativePointer()
actual fun PyObject_GenericGetAttr(o: NativePointer, name: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GenericGetAttr(o.toPlatformPointer(), name.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_SetAttr(o: NativePointer, attr_name: NativePointer, v: NativePointer): Int = python.native.ffi.bindings.PyObject_SetAttr(o.toPlatformPointer(), attr_name.toPlatformPointer(), v.toPlatformPointer())
actual inline fun PyObject_SetAttrString(o: NativePointer, attr_name: String, v: NativePointer): Int = python.native.ffi.bindings.PyObject_SetAttrString(o.toPlatformPointer(), attr_name, v.toPlatformPointer())
actual inline fun PyObject_GenericSetAttr(o: NativePointer, name: NativePointer, value: NativePointer): Int = python.native.ffi.bindings.PyObject_GenericSetAttr(o.toPlatformPointer(), name.toPlatformPointer(), value.toPlatformPointer())
actual inline fun PyObject_DelAttr(o: NativePointer, attr_name: NativePointer): Int = python.native.ffi.bindings.PyObject_DelAttr(o.toPlatformPointer(), attr_name.toPlatformPointer())
actual inline fun PyObject_DelAttrString(o: NativePointer, attr_name: String): Int = python.native.ffi.bindings.PyObject_DelAttrString(o.toPlatformPointer(), attr_name)
actual fun PyObject_RichCompare(o1: NativePointer, o2: NativePointer, opid: Int): NativePointer? = python.native.ffi.bindings.PyObject_RichCompare(o1.toPlatformPointer(), o2.toPlatformPointer(), opid).toNativePointer()
actual inline fun PyObject_RichCompareBool(o1: NativePointer, o2: NativePointer, opid: Int): Int = python.native.ffi.bindings.PyObject_RichCompareBool(o1.toPlatformPointer(), o2.toPlatformPointer(), opid)
actual fun PyObject_Format(obj: NativePointer, format_spec: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Format(obj.toPlatformPointer(), format_spec.toPlatformPointer()).toNativePointer()
actual fun PyObject_Repr(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Repr(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_ASCII(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_ASCII(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_Str(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Str(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_Bytes(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Bytes(o.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_IsSubclass(derived: NativePointer, cls: NativePointer): Int = python.native.ffi.bindings.PyObject_IsSubclass(derived.toPlatformPointer(), cls.toPlatformPointer())
actual inline fun PyObject_IsInstance(inst: NativePointer, cls: NativePointer): Int = python.native.ffi.bindings.PyObject_IsInstance(inst.toPlatformPointer(), cls.toPlatformPointer())
actual inline fun PyObject_IsTrue(o: NativePointer): Int = python.native.ffi.bindings.PyObject_IsTrue(o.toPlatformPointer())
actual inline fun PyObject_Not(o: NativePointer): Int = python.native.ffi.bindings.PyObject_Not(o.toPlatformPointer())
actual fun PyObject_Type(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Type(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetItem(o: NativePointer, key: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetItem(o.toPlatformPointer(), key.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_SetItem(o: NativePointer, key: NativePointer, v: NativePointer): Int = python.native.ffi.bindings.PyObject_SetItem(o.toPlatformPointer(), key.toPlatformPointer(), v.toPlatformPointer())
actual inline fun PyObject_DelItem(o: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyObject_DelItem(o.toPlatformPointer(), key.toPlatformPointer())
actual fun PyObject_Dir(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Dir(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetIter(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetIter(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetAIter(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetAIter(o.toPlatformPointer()).toNativePointer()


// Section 11
actual fun PyVectorcall_Call(callable: NativePointer, tuple: NativePointer, dict: NativePointer): NativePointer? = python.native.ffi.bindings.PyVectorcall_Call(callable.toPlatformPointer(), tuple.toPlatformPointer(), dict.toPlatformPointer()).toNativePointer()
actual fun PyObject_Call(callable: NativePointer, args: NativePointer, kwargs: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_Call(callable.toPlatformPointer(), args.toPlatformPointer(), kwargs.toPlatformPointer()).toNativePointer()
actual fun PyObject_CallNoArgs(callable: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallNoArgs(callable.toPlatformPointer()).toNativePointer()
actual fun PyObject_CallObject(callable: NativePointer, args: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallObject(callable.toPlatformPointer(), args.toPlatformPointer()).toNativePointer()
actual inline fun PyCallable_Check(o: NativePointer): Int = python.native.ffi.bindings.PyCallable_Check(o.toPlatformPointer())


// Section 12
actual inline fun PyNumber_Check(o: NativePointer): Int = python.native.ffi.bindings.PyNumber_Check(o.toPlatformPointer())
actual fun PyNumber_Add(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Add(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Subtract(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Subtract(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Multiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Multiply(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_MatrixMultiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_MatrixMultiply(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_FloorDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_FloorDivide(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_TrueDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_TrueDivide(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Remainder(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Remainder(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Divmod(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Divmod(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Power(o1: NativePointer, o2: NativePointer, o3: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Power(o1.toPlatformPointer(), o2.toPlatformPointer(), o3.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Negative(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Negative(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Positive(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Positive(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Absolute(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Absolute(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Invert(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Invert(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Lshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Lshift(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Rshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Rshift(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_And(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_And(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Xor(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Xor(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Or(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Or(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceAdd(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceAdd(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceSubtract(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceSubtract(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceMultiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceMultiply(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceMatrixMultiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceMatrixMultiply(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceFloorDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceFloorDivide(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceTrueDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceTrueDivide(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceRemainder(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceRemainder(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlacePower(o1: NativePointer, o2: NativePointer, o3: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlacePower(o1.toPlatformPointer(), o2.toPlatformPointer(), o3.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceLshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceLshift(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceRshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceRshift(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceAnd(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceAnd(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceXor(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceXor(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceOr(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceOr(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Long(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Long(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Float(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Float(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Index(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_Index(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_ToBase(n: NativePointer, base: Int): NativePointer? = python.native.ffi.bindings.PyNumber_ToBase(n.toPlatformPointer(), base).toNativePointer()
actual inline fun PyIndex_Check(o: NativePointer): Int = python.native.ffi.bindings.PyIndex_Check(o.toPlatformPointer())


// Section 13
actual inline fun PySequence_Check(o: NativePointer): Int = python.native.ffi.bindings.PySequence_Check(o.toPlatformPointer())
actual fun PySequence_Concat(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_Concat(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PySequence_InPlaceConcat(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_InPlaceConcat(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual inline fun PySequence_Contains(o: NativePointer, value: NativePointer): Int = python.native.ffi.bindings.PySequence_Contains(o.toPlatformPointer(), value.toPlatformPointer())
actual fun PySequence_List(o: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_List(o.toPlatformPointer()).toNativePointer()
actual fun PySequence_Tuple(o: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_Tuple(o.toPlatformPointer()).toNativePointer()
actual fun PySequence_Fast(o: NativePointer, m: String): NativePointer? = python.native.ffi.bindings.PySequence_Fast(o.toPlatformPointer(), m).toNativePointer()


// Section 14
actual inline fun PyMapping_Check(o: NativePointer): Int = python.native.ffi.bindings.PyMapping_Check(o.toPlatformPointer())
actual fun PyMapping_GetItemString(o: NativePointer, key: String): NativePointer? = python.native.ffi.bindings.PyMapping_GetItemString(o.toPlatformPointer(), key).toNativePointer()
actual inline fun PyMapping_SetItemString(o: NativePointer, key: String, v: NativePointer): Int = python.native.ffi.bindings.PyMapping_SetItemString(o.toPlatformPointer(), key, v.toPlatformPointer())
actual inline fun PyMapping_HasKeyWithError(o: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyMapping_HasKeyWithError(o.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PyMapping_HasKeyStringWithError(o: NativePointer, key: String): Int = python.native.ffi.bindings.PyMapping_HasKeyStringWithError(o.toPlatformPointer(), key)
actual inline fun PyMapping_HasKey(o: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyMapping_HasKey(o.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PyMapping_HasKeyString(o: NativePointer, key: String): Int = python.native.ffi.bindings.PyMapping_HasKeyString(o.toPlatformPointer(), key)
actual fun PyMapping_Keys(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyMapping_Keys(o.toPlatformPointer()).toNativePointer()
actual fun PyMapping_Values(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyMapping_Values(o.toPlatformPointer()).toNativePointer()
actual fun PyMapping_Items(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyMapping_Items(o.toPlatformPointer()).toNativePointer()


// Section 15
actual inline fun PyIter_Check(o: NativePointer): Int = python.native.ffi.bindings.PyIter_Check(o.toPlatformPointer())
actual inline fun PyAIter_Check(o: NativePointer): Int = python.native.ffi.bindings.PyAIter_Check(o.toPlatformPointer())
actual fun PyIter_Next(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyIter_Next(o.toPlatformPointer()).toNativePointer()


// Section 16
actual fun PyLong_FromLongLong(v: Long): NativePointer? = python.native.ffi.bindings.PyLong_FromLongLong(v).toNativePointer()
actual fun PyLong_FromDouble(v: Double): NativePointer? = python.native.ffi.bindings.PyLong_FromDouble(v).toNativePointer()
actual inline fun PyLong_AsInt(obj: NativePointer): Int = python.native.ffi.bindings.PyLong_AsInt(obj.toPlatformPointer())
actual inline fun PyLong_AsLongLong(obj: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLong(obj.toPlatformPointer())
actual inline fun PyLong_AsDouble(pylong: NativePointer): Double = python.native.ffi.bindings.PyLong_AsDouble(pylong.toPlatformPointer())
actual fun PyLong_GetInfo(): NativePointer? = python.native.ffi.bindings.PyLong_GetInfo().toNativePointer()


// Section 17
actual fun PyBool_FromLong(v: Int): NativePointer? = python.native.ffi.bindings.PyBool_FromLong(v.toLong()).toNativePointer()


// Section 18
actual fun PyFloat_FromString(str: NativePointer): NativePointer? = python.native.ffi.bindings.PyFloat_FromString(str.toPlatformPointer()).toNativePointer()
actual fun PyFloat_FromDouble(v: Double): NativePointer? = python.native.ffi.bindings.PyFloat_FromDouble(v).toNativePointer()
actual inline fun PyFloat_AsDouble(pyfloat: NativePointer): Double = python.native.ffi.bindings.PyFloat_AsDouble(pyfloat.toPlatformPointer())
actual fun PyFloat_GetInfo(): NativePointer? = python.native.ffi.bindings.PyFloat_GetInfo().toNativePointer()
actual inline fun PyFloat_GetMax(): Double = python.native.ffi.bindings.PyFloat_GetMax()
actual inline fun PyFloat_GetMin(): Double = python.native.ffi.bindings.PyFloat_GetMin()


// Section 19
actual fun PyBytes_FromString(v: String): NativePointer? = python.native.ffi.bindings.PyBytes_FromString(v).toNativePointer()
actual fun PyBytes_FromObject(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyBytes_FromObject(o.toPlatformPointer()).toNativePointer()
actual inline fun PyBytes_AsString(o: NativePointer): String? = python.native.ffi.bindings.PyBytes_AsString(o.toPlatformPointer())


// Section 20
actual fun PyByteArray_FromObject(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyByteArray_FromObject(o.toPlatformPointer()).toNativePointer()
actual fun PyByteArray_Concat(a: NativePointer, b: NativePointer): NativePointer? = python.native.ffi.bindings.PyByteArray_Concat(a.toPlatformPointer(), b.toPlatformPointer()).toNativePointer()
actual inline fun PyByteArray_AsString(bytearray: NativePointer): String? = python.native.ffi.bindings.PyByteArray_AsString(bytearray.toPlatformPointer())


// Section 21
actual inline fun PyUnicode_IsIdentifier(unicode: NativePointer): Int = python.native.ffi.bindings.PyUnicode_IsIdentifier(unicode.toPlatformPointer())
actual fun PyUnicode_FromString(str: String): NativePointer? = python.native.ffi.bindings.PyUnicode_FromString(str).toNativePointer()
actual fun PyUnicode_FromObject(obj: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_FromObject(obj.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_FromEncodedObject(obj: NativePointer, encoding: String, errors: String): NativePointer? = python.native.ffi.bindings.PyUnicode_FromEncodedObject(obj.toPlatformPointer(), encoding, errors).toNativePointer()
actual fun PyUnicode_DecodeLocale(str: String, errors: String): NativePointer? = python.native.ffi.bindings.PyUnicode_DecodeLocale(str, errors).toNativePointer()
actual fun PyUnicode_EncodeLocale(unicode: NativePointer, errors: String): NativePointer? = python.native.ffi.bindings.PyUnicode_EncodeLocale(unicode.toPlatformPointer(), errors).toNativePointer()
actual fun PyUnicode_DecodeFSDefault(str: String): NativePointer? = python.native.ffi.bindings.PyUnicode_DecodeFSDefault(str).toNativePointer()
actual fun PyUnicode_EncodeFSDefault(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_EncodeFSDefault(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsEncodedString(unicode: NativePointer, encoding: String, errors: String): NativePointer? = python.native.ffi.bindings.PyUnicode_AsEncodedString(unicode.toPlatformPointer(), encoding, errors).toNativePointer()
actual fun PyUnicode_AsUTF8String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUTF8String(unicode.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? = python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer()) // 수동 추가
actual fun PyUnicode_AsUTF32String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUTF32String(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsUTF16String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUTF16String(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsUnicodeEscapeString(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUnicodeEscapeString(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsRawUnicodeEscapeString(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsRawUnicodeEscapeString(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsLatin1String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsLatin1String(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsASCIIString(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsASCIIString(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsCharmapString(unicode: NativePointer, mapping: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsCharmapString(unicode.toPlatformPointer(), mapping.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_Translate(unicode: NativePointer, table: NativePointer, errors: String): NativePointer? = python.native.ffi.bindings.PyUnicode_Translate(unicode.toPlatformPointer(), table.toPlatformPointer(), errors).toNativePointer()
actual fun PyUnicode_Concat(left: NativePointer, right: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_Concat(left.toPlatformPointer(), right.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_Splitlines(unicode: NativePointer, keepends: Int): NativePointer? = python.native.ffi.bindings.PyUnicode_Splitlines(unicode.toPlatformPointer(), keepends).toNativePointer()
actual fun PyUnicode_Join(separator: NativePointer, seq: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_Join(separator.toPlatformPointer(), seq.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicode_Compare(left: NativePointer, right: NativePointer): Int = python.native.ffi.bindings.PyUnicode_Compare(left.toPlatformPointer(), right.toPlatformPointer())
actual inline fun PyUnicode_EqualToUTF8(unicode: NativePointer, string: String): Int = python.native.ffi.bindings.PyUnicode_EqualToUTF8(unicode.toPlatformPointer(), string)
actual inline fun PyUnicode_CompareWithASCIIString(unicode: NativePointer, string: String): Int = python.native.ffi.bindings.PyUnicode_CompareWithASCIIString(unicode.toPlatformPointer(), string)
actual fun PyUnicode_RichCompare(left: NativePointer, right: NativePointer, op: Int): NativePointer? = python.native.ffi.bindings.PyUnicode_RichCompare(left.toPlatformPointer(), right.toPlatformPointer(), op).toNativePointer()
actual fun PyUnicode_Format(format: NativePointer, args: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_Format(format.toPlatformPointer(), args.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicode_Contains(unicode: NativePointer, substr: NativePointer): Int = python.native.ffi.bindings.PyUnicode_Contains(unicode.toPlatformPointer(), substr.toPlatformPointer())
actual fun PyUnicode_InternFromString(str: String): NativePointer? = python.native.ffi.bindings.PyUnicode_InternFromString(str).toNativePointer()


// Section 22
actual inline fun PyList_Append(list: NativePointer, item: NativePointer): Int = python.native.ffi.bindings.PyList_Append(list.toPlatformPointer(), item.toPlatformPointer())
actual inline fun PyList_Sort(list: NativePointer): Int = python.native.ffi.bindings.PyList_Sort(list.toPlatformPointer())
actual inline fun PyList_Reverse(list: NativePointer): Int = python.native.ffi.bindings.PyList_Reverse(list.toPlatformPointer())
actual fun PyList_AsTuple(list: NativePointer): NativePointer? = python.native.ffi.bindings.PyList_AsTuple(list.toPlatformPointer()).toNativePointer()


// Section 23
actual fun PyDict_New(): NativePointer? = python.native.ffi.bindings.PyDict_New().toNativePointer()
actual fun PyDictProxy_New(mapping: NativePointer): NativePointer? = python.native.ffi.bindings.PyDictProxy_New(mapping.toPlatformPointer()).toNativePointer()
actual inline fun PyDict_Clear(p: NativePointer) = python.native.ffi.bindings.PyDict_Clear(p.toPlatformPointer())
actual inline fun PyDict_Contains(p: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyDict_Contains(p.toPlatformPointer(), key.toPlatformPointer())
actual fun PyDict_Copy(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_Copy(p.toPlatformPointer()).toNativePointer()
actual inline fun PyDict_SetItem(p: NativePointer, key: NativePointer, v: NativePointer): Int = python.native.ffi.bindings.PyDict_SetItem(p.toPlatformPointer(), key.toPlatformPointer(), v.toPlatformPointer())
actual inline fun PyDict_SetItemString(p: NativePointer, key: String, v: NativePointer): Int = python.native.ffi.bindings.PyDict_SetItemString(p.toPlatformPointer(), key, v.toPlatformPointer())
actual inline fun PyDict_DelItem(p: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyDict_DelItem(p.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PyDict_DelItemString(p: NativePointer, key: String): Int = python.native.ffi.bindings.PyDict_DelItemString(p.toPlatformPointer(), key)
actual fun PyDict_GetItem(p: NativePointer, key: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_GetItem(p.toPlatformPointer(), key.toPlatformPointer()).toNativePointer()
actual fun PyDict_GetItemWithError(p: NativePointer, key: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_GetItemWithError(p.toPlatformPointer(), key.toPlatformPointer()).toNativePointer()
actual fun PyDict_GetItemString(p: NativePointer, key: String): NativePointer? = python.native.ffi.bindings.PyDict_GetItemString(p.toPlatformPointer(), key).toNativePointer()
actual fun PyDict_Items(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_Items(p.toPlatformPointer()).toNativePointer()
actual fun PyDict_Keys(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_Keys(p.toPlatformPointer()).toNativePointer()
actual fun PyDict_Values(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_Values(p.toPlatformPointer()).toNativePointer()
actual inline fun PyDict_Merge(a: NativePointer, b: NativePointer, override: Int): Int = python.native.ffi.bindings.PyDict_Merge(a.toPlatformPointer(), b.toPlatformPointer(), override)
actual inline fun PyDict_Update(a: NativePointer, b: NativePointer): Int = python.native.ffi.bindings.PyDict_Update(a.toPlatformPointer(), b.toPlatformPointer())
actual inline fun PyDict_MergeFromSeq2(a: NativePointer, seq2: NativePointer, override: Int): Int = python.native.ffi.bindings.PyDict_MergeFromSeq2(a.toPlatformPointer(), seq2.toPlatformPointer(), override)


// Section 24
actual fun PySet_New(iterable: NativePointer): NativePointer? = python.native.ffi.bindings.PySet_New(iterable.toPlatformPointer()).toNativePointer()
actual fun PyFrozenSet_New(iterable: NativePointer): NativePointer? = python.native.ffi.bindings.PyFrozenSet_New(iterable.toPlatformPointer()).toNativePointer()
actual inline fun PySet_Contains(anyset: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PySet_Contains(anyset.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PySet_Add(set: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PySet_Add(set.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PySet_Discard(set: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PySet_Discard(set.toPlatformPointer(), key.toPlatformPointer())
actual fun PySet_Pop(set: NativePointer): NativePointer? = python.native.ffi.bindings.PySet_Pop(set.toPlatformPointer()).toNativePointer()
actual inline fun PySet_Clear(set: NativePointer): Int = python.native.ffi.bindings.PySet_Clear(set.toPlatformPointer())


// Section 25
actual fun PySeqIter_New(seq: NativePointer): NativePointer? = python.native.ffi.bindings.PySeqIter_New(seq.toPlatformPointer()).toNativePointer()
actual fun PyCallIter_New(callable: NativePointer, sentinel: NativePointer): NativePointer? = python.native.ffi.bindings.PyCallIter_New(callable.toPlatformPointer(), sentinel.toPlatformPointer()).toNativePointer()


// Section 26
actual fun PyWeakref_NewRef(ob: NativePointer, callback: NativePointer): NativePointer? = python.native.ffi.bindings.PyWeakref_NewRef(ob.toPlatformPointer(), callback.toPlatformPointer()).toNativePointer()
actual fun PyWeakref_NewProxy(ob: NativePointer, callback: NativePointer): NativePointer? = python.native.ffi.bindings.PyWeakref_NewProxy(ob.toPlatformPointer(), callback.toPlatformPointer()).toNativePointer()
actual fun PyWeakref_GetObject(ref: NativePointer): NativePointer? = python.native.ffi.bindings.PyWeakref_GetObject(ref.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_ClearWeakRefs(o: NativePointer) = python.native.ffi.bindings.PyObject_ClearWeakRefs(o.toPlatformPointer())


// Section 27
actual inline fun PyType_IsSubtype(a: NativePointer, b: NativePointer): Int = python.native.ffi.bindings.PyType_IsSubtype(a.toPlatformPointer(), b.toPlatformPointer())
actual inline fun PyType_Ready(type: NativePointer): Int = python.native.ffi.bindings.PyType_Ready(type.toPlatformPointer())
actual fun PyType_GetName(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetName(type.toPlatformPointer()).toNativePointer()
actual fun PyType_GetFullyQualifiedName(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetFullyQualifiedName(type.toPlatformPointer()).toNativePointer()
actual fun PyType_GetModuleName(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetModuleName(type.toPlatformPointer()).toNativePointer()
actual fun PyType_GetModule(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetModule(type.toPlatformPointer()).toNativePointer()


// Section 28
actual fun PyTuple_New(len: Long): NativePointer? = python.native.ffi.bindings.PyTuple_New(len).toNativePointer()
actual inline fun PyTuple_Size(p: NativePointer): Long = python.native.ffi.bindings.PyTuple_Size(p.toPlatformPointer())
actual fun PyTuple_GetItem(p: NativePointer, pos: Long): NativePointer? = python.native.ffi.bindings.PyTuple_GetItem(p.toPlatformPointer(), pos).toNativePointer()
actual fun PyTuple_GetSlice(p: NativePointer, low: Long, high: Long): NativePointer? = python.native.ffi.bindings.PyTuple_GetSlice(p.toPlatformPointer(), low, high).toNativePointer()
actual inline fun PyTuple_SetItem(p: NativePointer, pos: Long, o: NativePointer): Int = python.native.ffi.bindings.PyTuple_SetItem(p.toPlatformPointer(), pos, o.toPlatformPointer())
