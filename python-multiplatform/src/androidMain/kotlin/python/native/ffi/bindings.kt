package python.native.ffi

import python.native.ffi.manager.loadLibPython

typealias JNIPointer = Long


object bindings {
    init {
        loadLibPython()
    }

    /**
    external fun Py_Initialize()
    external fun Py_InitializeEx(sigint: Int)
    //external fun Py_InitializeFromConfig()
    external fun Py_IsInitialized(): Int
    external fun Py_Finalize()
    external fun Py_FinalizeEx(): Int
    external fun Py_IsFinalizing(): Int
    external fun Py_BytesMain(args: Array<String>): Int
    external fun Py_RunMain(): Int
    external fun PyRun_SimpleString(command: String): Int
    // external fun PyRun_String(str: String, start: Int, globals: JNIPointer, locals: JNIPointer): JNIPointer?
    external fun Py_GetVersion(): String?
    external fun Py_GetPlatform(): String?
    external fun Py_GetCopyright(): String?
    external fun Py_GetCompiler(): String?
    external fun Py_GetBuildInfo(): String?



    external fun PyErr_Occurred(): JNIPointer?


    external fun PyLong_FromLongLong(v: Long): JNIPointer?
    external fun PyLong_AsLongLong(p: JNIPointer): Long
    external fun PyLong_AsInt(p: JNIPointer): Int

    // external fun PyUnicode_FromString(str: String): JNIPointer?
    // external fun PyUnicode_AsUTF8(unicode: JNIPointer): String?
    */


    //**************************************************
    // Section 1
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_Initialize()
    external fun Py_InitializeEx(initsigs: Int)
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echo0(x: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echo1(x: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echo2(x: Long): Long

    // Same C body as echo0, bound under the other two conventions so a benchmark can
    // isolate the transition cost. See jni_onload.def.
    @JvmStatic
    external fun echoNormal(x: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.FastNative
    external fun echoFast(x: Long): Long

    // @CriticalNative reached by name-based linking, NOT via RegisterNatives -- see
    // JNIOnLoadExporter.echoCriticalNamed. Isolates the binding method from the convention.
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echoCriticalNamed(x: Long): Long

    // The same real CPython function (PyList_Size) under the other two conventions, so the
    // comparison can be made on a call that does actual work rather than on an empty echo.
    @JvmStatic
    @dalvik.annotation.optimization.FastNative
    external fun PyList_SizeFast(list: Long): Long
    @JvmStatic
    external fun PyList_SizeNormal(list: Long): Long

    // ---- @FastNative twins of the migrated functions ----
    //
    // ART fast-paths @CriticalNative up to API 33 and @FastNative from 34 onward; each is
    // roughly 20x the other on the wrong side of that line (docs/downcall-design.md). Both
    // are declared so EmbedAPI.android.kt can select per device. @FastNative still receives
    // JNIEnv and jclass, hence the separate C wrappers in jni_onload.def.
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_InitializeF()
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_IsInitializedF(): Int
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_FinalizeF()
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyErr_ClearF()
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyLong_FromLongLongF(v: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyList_SizeF(list: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyRun_SimpleStringF(command: Long): Int
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_GetVersionF(): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyImport_ImportModuleF(name: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyObject_GetAttrStringF(o: Long, name: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyErr_OccurredF(): Long

    // ---- Ordinary JNI, for calls that must NOT block GC or forbid JVM re-entry ----
    //
    // @CriticalNative and @FastNative both stop the collector for the duration of the call,
    // and @CriticalNative additionally has no JNIEnv, so nothing under it can re-enter the
    // runtime. These functions can execute arbitrary Python -- module top-level code, a
    // __getattr__, a __del__ reached by dropping the last reference, interpreter start-up and
    // shutdown -- which is unbounded in time and, once Kotlin callables are exposed to
    // Python, re-enters the JVM. They stay on the ordinary path at every API level.
    @JvmStatic external fun Py_InitializeN()
    @JvmStatic external fun Py_FinalizeN()
    @JvmStatic external fun PyErr_ClearN()
    @JvmStatic external fun PyRun_SimpleStringN(command: Long): Int
    @JvmStatic external fun PyImport_ImportModuleN(name: Long): Long
    @JvmStatic external fun PyImport_ImportN(name: Long): Long
    @JvmStatic external fun PyObject_GetAttrStringN(o: Long, name: Long): Long

    // ---- Composition probes: the whole binder operation in one crossing ----
    // asmGetAttr replaces ffiAllocUtf8 + PyObject_GetAttrString + PyErr_Clear + ffiFreeUtf8.
    // asmListToArray replaces PyList_Size + N x PyList_GetItem, filling the caller's array
    // and returning how many elements it wrote.
    // Borrowed reference, leaf: pure index into the list's item array.
    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyList_GetItemRaw(list: Long, i: Long): Long
    // Python3.exec composed into one crossing -- see jni_onload.def.
    // Address of a DirectByteBuffer's off-heap storage. Called once per buffer, not per
    // string -- see jni_onload.def.
    @JvmStatic external fun ffiDirectBufferAddress(buf: java.nio.ByteBuffer): Long
    @JvmStatic external fun asmExec(code: String): Int
    @JvmStatic external fun asmGetAttr(obj: Long, name: String): Long
    @JvmStatic external fun asmListToArray(list: Long, out: LongArray): Int

    @JvmStatic external fun testUpcallPrimitive(x: Long): Long
    @JvmStatic external fun testUpcallString(s: String): Long
    @JvmStatic external fun testUpcallUnattached(x: Long): Long
    @JvmStatic external fun testThreadCreateFloor(x: Long): Long

    /**
     * Which JNI calling convention this device fast-paths.
     *
     * Measured, not guessed: @CriticalNative is at or below the timing floor from API 26
     * through 33 and costs ~24ns on 34 and ~44ns on API 36 hardware, while @FastNative is
     * ~24-39ns through API 31 and ~2ns from 33 on. Both are cheap at 33, so the threshold is
     * placed where being off by one costs the least. See docs/downcall-design.md.
     *
     * Read once into a static final so the JIT folds the branch at each call site.
     */
    @JvmStatic
    val preferFastNative: Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_IsInitialized(): Int
    external fun Py_IsFinalizing(): Int
    external fun Py_FinalizeEx(): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_Finalize()
    // external fun Py_BytesMain(args: Array<String>): Int // 수동 추가
    @JvmStatic external fun Py_RunMainN(): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_GetVersion(): Long
    @JvmStatic external fun Py_GetPlatformN(): Long
    @JvmStatic external fun Py_GetCopyrightN(): Long
    @JvmStatic external fun Py_GetCompilerN(): Long
    @JvmStatic external fun Py_GetBuildInfoN(): Long
    // external fun PyEval_InitThreads()
    // external fun PyThreadState_GetDict(): JNIPointer?

    // external fun PyGILState_Ensure(): Int
    // external fun PyGILState_Release(state: Int)
    // external fun PyGILState_GetThisThreadState(): JNIPointer?
    // external fun PyEval_SaveThread(): JNIPointer?
    // external fun PyEval_RestoreThread(tstate: JNIPointer)


    // Section 2
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyRun_SimpleString(command: Long): Int // 수동 추가
    // external fun PyRun_String(str: String, start: Int, globals: JNIPointer, locals: JNIPointer): JNIPointer? // 수동 추가
    // `str` is arbitrary source text and `filename` a repeated identifier, so the two arguments
    // take different marshalling paths on the Kotlin side -- see EmbedAPI.android.kt.
    @JvmStatic external fun Py_CompileStringN(str: Long, filename: Long, start: Int): Long
    external fun PyEval_EvalCode(co: JNIPointer, globals: JNIPointer, locals: JNIPointer): JNIPointer?


    // Section 3
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyErr_Clear()
    external fun PyErr_PrintEx(set_sys_last_vars: Int)
    @JvmStatic external fun PyErr_PrintN()
    external fun PyErr_WriteUnraisable(obj: JNIPointer)
    external fun PyErr_DisplayException(exc: JNIPointer)
    // PyErr_SetString is bound as PyErr_SetStringN below; the String-taking declaration is gone
    // deliberately, because reaching it would fall back to the name-linked @CName export (ROADMAP §2).
    external fun PyErr_SetObject(type: JNIPointer, value: JNIPointer)
    external fun PyErr_SetNone(type: JNIPointer)
    external fun PyErr_BadArgument(): Int
    external fun PyErr_NoMemory(): JNIPointer?
    external fun PyErr_SetFromErrno(type: JNIPointer): JNIPointer?
    external fun PyErr_SetFromErrnoWithFilenameObject(type: JNIPointer, filenameObject: JNIPointer): JNIPointer?
    external fun PyErr_SetFromErrnoWithFilenameObjects(type: JNIPointer, filenameObject: JNIPointer, filenameObject2: JNIPointer): JNIPointer?
    @JvmStatic external fun PyErr_SetFromErrnoWithFilenameN(type: Long, filename: Long): Long
    external fun PyErr_SetImportError(msg: JNIPointer, name: JNIPointer, path: JNIPointer): JNIPointer?
    external fun PyErr_SetImportErrorSubclass(exception: JNIPointer, msg: JNIPointer, name: JNIPointer, path: JNIPointer): JNIPointer?
    @JvmStatic external fun PyErr_SyntaxLocationExN(filename: Long, lineno: Int, col_offset: Int)
    @JvmStatic external fun PyErr_SyntaxLocationN(filename: Long, lineno: Int)
    external fun PyErr_BadInternalCall()
    @JvmStatic external fun PyErr_WarnExplicitN(category: Long, message: Long, filename: Long, lineno: Int, module: Long, registry: Long): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyErr_Occurred(): Long
    external fun PyErr_ExceptionMatches(exc: JNIPointer): Int
    external fun PyErr_GivenExceptionMatches(given: JNIPointer, exc: JNIPointer): Int
    @JvmStatic external fun PyErr_GetRaisedExceptionN(): Long
    @JvmStatic external fun PyErr_SetRaisedExceptionN(exc: Long)
    external fun PyErr_Restore(type: JNIPointer, value: JNIPointer, traceback: JNIPointer)
    external fun PyErr_GetHandledException(): JNIPointer?
    external fun PyErr_SetHandledException(exc: JNIPointer)
    external fun PyErr_SetExcInfo(type: JNIPointer, value: JNIPointer, traceback: JNIPointer)
    external fun PyErr_CheckSignals(): Int
    external fun PyErr_SetInterrupt()
    external fun PyErr_SetInterruptEx(signum: Int): Int
    @JvmStatic external fun PyErr_NewExceptionN(name: Long, base: Long, dict: Long): Long
    @JvmStatic external fun PyErr_NewExceptionWithDocN(name: Long, doc: Long, base: Long, dict: Long): Long
    @JvmStatic external fun PyException_GetTracebackN(ex: Long): Long
    external fun PyException_SetTraceback(ex: JNIPointer, tb: JNIPointer): Int
    @JvmStatic external fun PyException_GetContextN(ex: Long): Long
    external fun PyException_SetContext(ex: JNIPointer, ctx: JNIPointer)
    @JvmStatic external fun PyException_GetCauseN(ex: Long): Long
    external fun PyException_SetCause(ex: JNIPointer, cause: JNIPointer)
    external fun PyException_GetArgs(ex: JNIPointer): JNIPointer?
    external fun PyException_SetArgs(ex: JNIPointer, args: JNIPointer)
    external fun PyUnicodeEncodeError_GetEncoding(exc: JNIPointer): JNIPointer?
    external fun PyUnicodeTranslateError_GetObject(exc: JNIPointer): JNIPointer?
    external fun PyUnicodeTranslateError_GetReason(exc: JNIPointer): JNIPointer?
    @JvmStatic external fun PyUnicodeTranslateError_SetReasonN(exc: Long, reason: Long): Int
    @JvmStatic external fun Py_EnterRecursiveCallN(where: Long): Int
    external fun Py_LeaveRecursiveCall()
    external fun Py_ReprEnter(o: JNIPointer): Int
    external fun Py_ReprLeave(o: JNIPointer)


    // Section 4
    // external fun Py_NewRef(o: JNIPointer): JNIPointer?
    // external fun Py_XNewRef(o: JNIPointer): JNIPointer?
    // external fun Py_IncRef(o: JNIPointer)
    // external fun Py_DecRef(o: JNIPointer)


    // Section 5
    external fun PyOS_FSPath(path: JNIPointer): JNIPointer?


    // Section 6
    @JvmStatic external fun PySys_GetObjectN(name: Long): Long
    @JvmStatic external fun PySys_SetObjectN(name: Long, v: Long): Int
    external fun PySys_ResetWarnOptions()
    external fun PySys_GetXOptions(): JNIPointer?
    @JvmStatic external fun PySys_AuditTupleN(event: Long, args: Long): Int


    // Section 7
    @JvmStatic external fun Py_FatalErrorN(message: Long)
    external fun Py_Exit(status: Int)


    // Section 8
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyImport_ImportModule(name: Long): Long
    @JvmStatic external fun PyImport_ImportModuleNoBlockN(name: Long): Long
    external fun PyImport_ImportModuleLevelObject(name: JNIPointer, globals: JNIPointer, locals: JNIPointer, fromlist: JNIPointer, level: Int): JNIPointer?
    @JvmStatic external fun PyImport_ImportModuleLevelN(name: Long, globals: Long, locals: Long, fromlist: Long, level: Int): Long
    // external fun PyImport_Import(name: JNIPointer): JNIPointer?
    external fun PyImport_ReloadModule(m: JNIPointer): JNIPointer?
    // external fun PyImport_AddModuleRef(name: String): JNIPointer?
    external fun PyImport_AddModuleObject(name: JNIPointer): JNIPointer?
    // PyImport_AddModule is bound as PyImport_AddModuleN below.
    @JvmStatic external fun PyImport_ExecCodeModuleN(name: Long, co: Long): Long
    @JvmStatic external fun PyImport_ExecCodeModuleExN(name: Long, co: Long, pathname: Long): Long
    external fun PyImport_ExecCodeModuleObject(name: JNIPointer, co: JNIPointer, pathname: JNIPointer, cpathname: JNIPointer): JNIPointer?
    @JvmStatic external fun PyImport_ExecCodeModuleWithPathnamesN(name: Long, co: Long, pathname: Long, cpathname: Long): Long
    // CPython-owned const char*; the JVM side reads it with ffiReadUtf8 and must not free it.
    @JvmStatic external fun PyImport_GetMagicTagN(): Long
    external fun PyImport_GetModuleDict(): JNIPointer?
    external fun PyImport_GetModule(name: JNIPointer): JNIPointer?
    external fun PyImport_GetImporter(path: JNIPointer): JNIPointer?
    external fun PyImport_ImportFrozenModuleObject(name: JNIPointer): Int
    @JvmStatic external fun PyImport_ImportFrozenModuleN(name: Long): Int


    // Section 9
    @JvmStatic external fun PyEval_GetBuiltinsN(): Long
    external fun PyEval_GetLocals(): JNIPointer?
    external fun PyEval_GetGlobals(): JNIPointer?
    external fun PyEval_GetFrameBuiltins(): JNIPointer?
    external fun PyEval_GetFrameLocals(): JNIPointer?
    external fun PyEval_GetFrameGlobals(): JNIPointer?
    // Both return a CPython-owned const char*; read with ffiReadUtf8, never freed here.
    @JvmStatic external fun PyEval_GetFuncNameN(func: Long): Long
    @JvmStatic external fun PyEval_GetFuncDescN(func: Long): Long


    // Section 10
    external fun PyObject_HasAttrWithError(o: JNIPointer, attr_name: JNIPointer): Int
    @JvmStatic external fun PyObject_HasAttrStringWithErrorN(o: Long, attr_name: Long): Int
    external fun PyObject_HasAttr(o: JNIPointer, attr_name: JNIPointer): Int
    @JvmStatic external fun PyObject_HasAttrStringN(o: Long, attr_name: Long): Int
    external fun PyObject_GetAttr(o: JNIPointer, attr_name: JNIPointer): JNIPointer?
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyObject_GetAttrString(o: Long, attr_name: Long): Long
    external fun PyObject_GenericGetAttr(o: JNIPointer, name: JNIPointer): JNIPointer?
    external fun PyObject_SetAttr(o: JNIPointer, attr_name: JNIPointer, v: JNIPointer): Int
    // PyObject_SetAttrString / PyObject_DelAttrString are bound as the *N variants below.
    external fun PyObject_GenericSetAttr(o: JNIPointer, name: JNIPointer, value: JNIPointer): Int
    external fun PyObject_DelAttr(o: JNIPointer, attr_name: JNIPointer): Int
    @JvmStatic external fun PyObject_RichCompareN(o1: Long, o2: Long, opid: Int): Long
    @JvmStatic external fun PyObject_RichCompareBoolN(o1: Long, o2: Long, opid: Int): Int
    external fun PyObject_Format(obj: JNIPointer, format_spec: JNIPointer): JNIPointer?
    @JvmStatic external fun PyObject_ReprN(o: Long): Long
    external fun PyObject_ASCII(o: JNIPointer): JNIPointer?
    // external fun PyObject_Str(o: JNIPointer): JNIPointer?
    external fun PyObject_Bytes(o: JNIPointer): JNIPointer?
    external fun PyObject_IsSubclass(derived: JNIPointer, cls: JNIPointer): Int
    @JvmStatic external fun PyObject_IsInstanceN(inst: Long, cls: Long): Int
    // external fun PyObject_IsTrue(o: JNIPointer): Int
    external fun PyObject_Not(o: JNIPointer): Int
    @JvmStatic external fun PyObject_TypeN(o: Long): Long
    external fun PyObject_Size(o: JNIPointer): Long
    external fun PyObject_Length(o: JNIPointer): Long
    @JvmStatic external fun PyObject_GetItemN(o: Long, key: Long): Long
    @JvmStatic external fun PyObject_SetItemN(o: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyObject_DelItemN(o: Long, key: Long): Int
    external fun PyObject_Dir(o: JNIPointer): JNIPointer?
    @JvmStatic external fun PyObject_GetIterN(o: Long): Long
    external fun PyObject_GetAIter(o: JNIPointer): JNIPointer?


    // Section 11
    external fun PyVectorcall_Call(callable: JNIPointer, tuple: JNIPointer, dict: JNIPointer): JNIPointer?
    @JvmStatic external fun PyObject_CallN(callable: Long, args: Long, kwargs: Long): Long
    external fun PyObject_Call(callable: JNIPointer, args: JNIPointer, kwargs: JNIPointer): JNIPointer?
    // external fun PyObject_CallNoArgs(callable: JNIPointer): JNIPointer?
    // external fun PyObject_CallObject(callable: JNIPointer, args: JNIPointer): JNIPointer?
    @JvmStatic external fun PyCallable_CheckN(o: Long): Int


    // Section 12
    external fun PyNumber_Check(o: JNIPointer): Int
    @JvmStatic external fun PyNumber_AddN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_SubtractN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_MultiplyN(o1: Long, o2: Long): Long
    external fun PyNumber_MatrixMultiply(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_FloorDivide(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_TrueDivide(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Remainder(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Divmod(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Power(o1: JNIPointer, o2: JNIPointer, o3: JNIPointer): JNIPointer?
    external fun PyNumber_Negative(o: JNIPointer): JNIPointer?
    external fun PyNumber_Positive(o: JNIPointer): JNIPointer?
    external fun PyNumber_Absolute(o: JNIPointer): JNIPointer?
    external fun PyNumber_Invert(o: JNIPointer): JNIPointer?
    external fun PyNumber_Lshift(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Rshift(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    @JvmStatic external fun PyNumber_AndN(o1: Long, o2: Long): Long
    external fun PyNumber_Xor(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    @JvmStatic external fun PyNumber_OrN(o1: Long, o2: Long): Long
    external fun PyNumber_InPlaceAdd(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceSubtract(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceMultiply(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceMatrixMultiply(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceFloorDivide(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceTrueDivide(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceRemainder(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlacePower(o1: JNIPointer, o2: JNIPointer, o3: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceLshift(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceRshift(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceAnd(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceXor(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_InPlaceOr(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Long(o: JNIPointer): JNIPointer?
    external fun PyNumber_Float(o: JNIPointer): JNIPointer?
    external fun PyNumber_Index(o: JNIPointer): JNIPointer?
    external fun PyNumber_ToBase(n: JNIPointer, base: Int): JNIPointer?
    external fun PyIndex_Check(o: JNIPointer): Int


    // Section 13
    external fun PySequence_Check(o: JNIPointer): Int
    external fun PySequence_Concat(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PySequence_InPlaceConcat(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    @JvmStatic external fun PySequence_ContainsN(o: Long, value: Long): Int
    @JvmStatic external fun PySequence_ListN(o: Long): Long
    @JvmStatic external fun PySequence_TupleN(o: Long): Long
    @JvmStatic external fun PySequence_FastN(o: Long, m: Long): Long


    // Section 14
    external fun PyMapping_Check(o: JNIPointer): Int
    @JvmStatic external fun PyMapping_GetItemStringN(o: Long, key: Long): Long
    @JvmStatic external fun PyMapping_SetItemStringN(o: Long, key: Long, v: Long): Int
    external fun PyMapping_HasKeyWithError(o: JNIPointer, key: JNIPointer): Int
    @JvmStatic external fun PyMapping_HasKeyStringWithErrorN(o: Long, key: Long): Int
    external fun PyMapping_HasKey(o: JNIPointer, key: JNIPointer): Int
    @JvmStatic external fun PyMapping_HasKeyStringN(o: Long, key: Long): Int
    external fun PyMapping_Keys(o: JNIPointer): JNIPointer?
    external fun PyMapping_Values(o: JNIPointer): JNIPointer?
    external fun PyMapping_Items(o: JNIPointer): JNIPointer?


    // Section 15
    external fun PyIter_Check(o: JNIPointer): Int
    external fun PyAIter_Check(o: JNIPointer): Int
    @JvmStatic external fun PyIter_NextN(o: Long): Long


    // Section 16
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyLong_FromLongLong(v: Long): Long
    external fun PyLong_FromDouble(v: Double): JNIPointer?
    @JvmStatic external fun PyLong_AsIntN(obj: Long): Int
    @JvmStatic external fun PyLong_AsLongLongN(obj: Long): Long
    external fun PyLong_AsDouble(pylong: JNIPointer): Double
    external fun PyLong_GetInfo(): JNIPointer?


    // Section 17
    @JvmStatic external fun PyBool_FromLongN(v: Long): Long


    // Section 18
    external fun PyFloat_FromString(str: JNIPointer): JNIPointer?
    @JvmStatic external fun PyFloat_FromDoubleN(v: Double): Long
    @JvmStatic external fun PyFloat_AsDoubleN(pyfloat: Long): Double
    external fun PyFloat_GetInfo(): JNIPointer?
    external fun PyFloat_GetMax(): Double
    external fun PyFloat_GetMin(): Double


    // Section 19
    @JvmStatic external fun PyBytes_FromStringN(v: Long): Long
    external fun PyBytes_FromObject(o: JNIPointer): JNIPointer?
    // char* into the bytes object's own buffer -- valid while `o` lives, never freed here.
    @JvmStatic external fun PyBytes_AsStringN(o: Long): Long


    // Section 20
    external fun PyByteArray_FromObject(o: JNIPointer): JNIPointer?
    external fun PyByteArray_Concat(a: JNIPointer, b: JNIPointer): JNIPointer?
    @JvmStatic external fun PyByteArray_AsStringN(bytearray: Long): Long


    // Section 21
    external fun PyUnicode_IsIdentifier(unicode: JNIPointer): Int
    // external fun PyUnicode_FromString(str: String): JNIPointer?
    external fun PyUnicode_FromObject(obj: JNIPointer): JNIPointer?
    @JvmStatic external fun PyUnicode_FromEncodedObjectN(obj: Long, encoding: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_DecodeLocaleN(str: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_EncodeLocaleN(unicode: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_DecodeFSDefaultN(str: Long): Long
    external fun PyUnicode_EncodeFSDefault(unicode: JNIPointer): JNIPointer?
    @JvmStatic external fun PyUnicode_AsEncodedStringN(unicode: Long, encoding: Long, errors: Long): Long
    external fun PyUnicode_AsUTF8String(unicode: JNIPointer): JNIPointer?
    // external fun PyUnicode_AsUTF8(unicode: JNIPointer): String? // 수동 추가
    external fun PyUnicode_AsUTF32String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsUTF16String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsUnicodeEscapeString(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsRawUnicodeEscapeString(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsLatin1String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsASCIIString(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsCharmapString(unicode: JNIPointer, mapping: JNIPointer): JNIPointer?
    @JvmStatic external fun PyUnicode_TranslateN(unicode: Long, table: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_ConcatN(left: Long, right: Long): Long
    external fun PyUnicode_Splitlines(unicode: JNIPointer, keepends: Int): JNIPointer?
    external fun PyUnicode_Join(separator: JNIPointer, seq: JNIPointer): JNIPointer?
    external fun PyUnicode_Compare(left: JNIPointer, right: JNIPointer): Int
    @JvmStatic external fun PyUnicode_EqualToUTF8N(unicode: Long, string: Long): Int
    @JvmStatic external fun PyUnicode_CompareWithASCIIStringN(unicode: Long, string: Long): Int
    external fun PyUnicode_RichCompare(left: JNIPointer, right: JNIPointer, op: Int): JNIPointer?
    external fun PyUnicode_Format(format: JNIPointer, args: JNIPointer): JNIPointer?
    @JvmStatic external fun PyUnicode_ContainsN(unicode: Long, substr: Long): Int
    @JvmStatic external fun PyUnicode_InternFromStringN(str: Long): Long


    // Section 22
    @JvmStatic external fun PyList_NewN(len: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyList_Size(list: Long): Long
    external fun PyList_GetItem(list: JNIPointer, index: Long): JNIPointer?
    @JvmStatic external fun PyList_SetItemN(list: Long, index: Long, item: Long): Int
    @JvmStatic external fun PyList_InsertN(list: Long, index: Long, item: Long): Int
    @JvmStatic external fun PyList_AppendN(list: Long, item: Long): Int
    @JvmStatic external fun PyList_SortN(list: Long): Int
    @JvmStatic external fun PyList_ReverseN(list: Long): Int
    @JvmStatic external fun PyList_AsTupleN(list: Long): Long


    // Section 23
    // external fun PyDict_New(): JNIPointer?
    @JvmStatic external fun PyDict_SizeN(p: Long): Long
    external fun PyDictProxy_New(mapping: JNIPointer): JNIPointer?
    @JvmStatic external fun PyDict_ClearN(p: Long)
    @JvmStatic external fun PyDict_ContainsN(p: Long, key: Long): Int
    external fun PyDict_Copy(p: JNIPointer): JNIPointer?
    // external fun PyDict_SetItem(p: JNIPointer, key: JNIPointer, v: JNIPointer): Int
    // external fun PyDict_SetItemString(p: JNIPointer, key: String, v: JNIPointer): Int
    @JvmStatic external fun PyDict_DelItemN(p: Long, key: Long): Int
    @JvmStatic external fun PyDict_DelItemStringN(p: Long, key: Long): Int
    @JvmStatic external fun PyDict_GetItemN(p: Long, key: Long): Long
    external fun PyDict_GetItemWithError(p: JNIPointer, key: JNIPointer): JNIPointer?
    // external fun PyDict_GetItemString(p: JNIPointer, key: String): JNIPointer?
    @JvmStatic external fun PyDict_ItemsN(p: Long): Long
    external fun PyDict_Keys(p: JNIPointer): JNIPointer?
    @JvmStatic external fun PyDict_ValuesN(p: Long): Long
    external fun PyDict_Merge(a: JNIPointer, b: JNIPointer, override: Int): Int
    external fun PyDict_Update(a: JNIPointer, b: JNIPointer): Int
    external fun PyDict_MergeFromSeq2(a: JNIPointer, seq2: JNIPointer, override: Int): Int


    // Section 24
    @JvmStatic external fun PySet_NewN(iterable: Long): Long
    @JvmStatic external fun PyFrozenSet_NewN(iterable: Long): Long
    @JvmStatic external fun PySet_ContainsN(anyset: Long, key: Long): Int
    @JvmStatic external fun PySet_SizeN(anyset: Long): Long
    @JvmStatic external fun PySet_AddN(set: Long, key: Long): Int
    @JvmStatic external fun PySet_DiscardN(set: Long, key: Long): Int
    @JvmStatic external fun PySet_PopN(set: Long): Long
    @JvmStatic external fun PySet_ClearN(set: Long): Int


    // Section 25
    external fun PySeqIter_New(seq: JNIPointer): JNIPointer?
    external fun PyCallIter_New(callable: JNIPointer, sentinel: JNIPointer): JNIPointer?


    // Section 26
    external fun PyWeakref_NewRef(ob: JNIPointer, callback: JNIPointer): JNIPointer?
    external fun PyWeakref_NewProxy(ob: JNIPointer, callback: JNIPointer): JNIPointer?
    external fun PyWeakref_GetObject(ref: JNIPointer): JNIPointer?
    external fun PyObject_ClearWeakRefs(o: JNIPointer)


    // Section 27
    @JvmStatic external fun PyType_IsSubtypeN(a: Long, b: Long): Int
    external fun PyType_Ready(type: JNIPointer): Int
    @JvmStatic external fun PyType_GetNameN(type: Long): Long
    external fun PyType_GetFullyQualifiedName(type: JNIPointer): JNIPointer?
    external fun PyType_GetModuleName(type: JNIPointer): JNIPointer?
    external fun PyType_GetModule(type: JNIPointer): JNIPointer?


    // Section 28
    // external fun PyTuple_New(len: Long): JNIPointer?
    @JvmStatic external fun PyTuple_SizeN(p: Long): Long
    @JvmStatic external fun PyTuple_GetItemN(p: Long, pos: Long): Long
    @JvmStatic external fun PyTuple_GetSliceN(p: Long, low: Long, high: Long): Long
    // external fun PyTuple_SetItem(p: JNIPointer, pos: Long, o: JNIPointer): Int


    // Section 29
    @JvmStatic external fun PyModule_GetNameN(module: Long): Long
    @JvmStatic external fun PyModule_GetDictN(module: Long): Long
    @JvmStatic external fun PyModule_GetFilenameObjectN(module: Long): Long


    //**************************************************
    // Shape vocabulary (see docs/downcall-design.md and jvmMain/.../ShapeDowncalls.kt).
    //
    // These bind, via JNI, to the `@CName`-exported trampolines compiled into
    // `libmultiplatform_python3.13.so` from `nativeMain/.../EmbedAPI.native.kt`. Unlike every
    // other declaration in this file (one native method per CPython function), these 14 are
    // reused across all ~330 CPython functions of a given shape -- the target function's
    // address travels as the leading `fn` argument.
    external fun downcall_V(fn: Long)
    external fun downcall_I(fn: Long): Long
    external fun downcall_F(fn: Long): Double
    external fun downcallI_V(fn: Long, a0: Long)
    external fun downcallI_I(fn: Long, a0: Long): Long
    external fun downcallI_F(fn: Long, a0: Long): Double
    external fun downcallF_I(fn: Long, a0: Double): Long
    external fun downcallII_V(fn: Long, a0: Long, a1: Long)
    external fun downcallII_I(fn: Long, a0: Long, a1: Long): Long
    external fun downcallIII_V(fn: Long, a0: Long, a1: Long, a2: Long)
    external fun downcallIII_I(fn: Long, a0: Long, a1: Long, a2: Long): Long
    external fun downcallIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long
    external fun downcallIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long): Long
    external fun downcallIIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long): Long

    // Symbol lookup (uncached primitive -- caching happens on the jvmMain side, see ffiSymbol)
    external fun ffiSymbolRaw(name: String): Long

    // UTF-8 string marshalling. See the lifetime discussion in jvmMain/.../ShapeDowncalls.kt:
    // ffiAllocUtf8's result must be released with ffiFreeUtf8 by the caller; ffiReadUtf8 never
    // frees its input.
    external fun ffiAllocUtf8(str: String): Long
    external fun ffiFreeUtf8(ptr: Long)
    external fun ffiReadUtf8(ptr: Long): String?

    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyUnicode_AsUTF8(unicode: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyUnicode_AsUTF8F(unicode: Long): Long
    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyUnicode_FromString(str: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyUnicode_FromStringF(str: Long): Long

    @JvmStatic external fun PyImport_AddModuleRefN(name: Long): Long
    // Registered after the name-linked fallback crashed the object model on Android: the second
    // commonTest case died with SIGSEGV at a truncated pointer inside PyImport_AddModule.
    @JvmStatic external fun PyImport_AddModuleN(name: Long): Long
    @JvmStatic external fun PyErr_SetStringN(type: Long, message: Long)
    @JvmStatic external fun PyObject_SetAttrStringN(o: Long, attr_name: Long, v: Long): Int
    @JvmStatic external fun PyObject_DelAttrStringN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyRun_StringN(str: Long, start: Int, globals: Long, locals: Long): Long
    @JvmStatic external fun PyObject_StrN(o: Long): Long
    @JvmStatic external fun PyObject_IsTrueN(o: Long): Int
    @JvmStatic external fun PyObject_CallNoArgsN(callable: Long): Long
    @JvmStatic external fun PyObject_CallObjectN(callable: Long, args: Long): Long


    @JvmStatic external fun Py_DecRefN(o: Long)
    @JvmStatic external fun Py_IncRefN(o: Long)
    @JvmStatic external fun Py_NewRefN(o: Long): Long
    @JvmStatic external fun Py_XNewRefN(o: Long): Long
    @JvmStatic external fun PyGILState_EnsureN(): Int
    @JvmStatic external fun PyGILState_ReleaseN(state: Int)
    @JvmStatic external fun PyGILState_GetThisThreadStateN(): Long
    @JvmStatic external fun PyEval_SaveThreadN(): Long
    @JvmStatic external fun PyEval_RestoreThreadN(tstate: Long)
    @JvmStatic external fun PyEval_InitThreadsN()
    @JvmStatic external fun PyThreadState_GetDictN(): Long
    @JvmStatic external fun PyTuple_NewN(len: Long): Long
    @JvmStatic external fun PyTuple_SetItemN(p: Long, pos: Long, o: Long): Int
    @JvmStatic external fun PyDict_NewN(): Long
    @JvmStatic external fun PyDict_SetItemStringN(p: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyDict_SetItemN(p: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyDict_GetItemStringN(p: Long, key: Long): Long

}
