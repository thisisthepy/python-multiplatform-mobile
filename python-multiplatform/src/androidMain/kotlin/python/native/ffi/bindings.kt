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
    external fun PyRun_String(str: String, start: Int, globals: JNIPointer, locals: JNIPointer): JNIPointer?
    external fun Py_GetVersion(): String?
    external fun Py_GetPlatform(): String?
    external fun Py_GetCopyright(): String?
    external fun Py_GetCompiler(): String?
    external fun Py_GetBuildInfo(): String?



    external fun PyErr_Occurred(): JNIPointer?


    external fun PyLong_FromLongLong(v: Long): JNIPointer?
    external fun PyLong_AsLongLong(p: JNIPointer): Long
    external fun PyLong_AsInt(p: JNIPointer): Int

    external fun PyUnicode_FromString(str: String): JNIPointer?
    external fun PyUnicode_AsUTF8(unicode: JNIPointer): String?
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
    external fun Py_RunMain(): Int // 수동 추가
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_GetVersion(): Long
    external fun Py_GetPlatform(): String?
    external fun Py_GetCopyright(): String?
    external fun Py_GetCompiler(): String?
    external fun Py_GetBuildInfo(): String?
    external fun PyEval_InitThreads()
    external fun PyThreadState_GetDict(): JNIPointer?

    external fun PyGILState_Ensure(): Int
    external fun PyGILState_Release(state: Int)
    external fun PyGILState_GetThisThreadState(): JNIPointer?
    external fun PyEval_SaveThread(): JNIPointer?
    external fun PyEval_RestoreThread(tstate: JNIPointer)


    // Section 2
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyRun_SimpleString(command: Long): Int // 수동 추가
    external fun PyRun_String(str: String, start: Int, globals: JNIPointer, locals: JNIPointer): JNIPointer? // 수동 추가
    external fun Py_CompileString(str: String, filename: String, start: Int): JNIPointer?
    external fun PyEval_EvalCode(co: JNIPointer, globals: JNIPointer, locals: JNIPointer): JNIPointer?


    // Section 3
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyErr_Clear()
    external fun PyErr_PrintEx(set_sys_last_vars: Int)
    external fun PyErr_Print()
    external fun PyErr_WriteUnraisable(obj: JNIPointer)
    external fun PyErr_DisplayException(exc: JNIPointer)
    external fun PyErr_SetString(type: JNIPointer, message: String)
    external fun PyErr_SetObject(type: JNIPointer, value: JNIPointer)
    external fun PyErr_SetNone(type: JNIPointer)
    external fun PyErr_BadArgument(): Int
    external fun PyErr_NoMemory(): JNIPointer?
    external fun PyErr_SetFromErrno(type: JNIPointer): JNIPointer?
    external fun PyErr_SetFromErrnoWithFilenameObject(type: JNIPointer, filenameObject: JNIPointer): JNIPointer?
    external fun PyErr_SetFromErrnoWithFilenameObjects(type: JNIPointer, filenameObject: JNIPointer, filenameObject2: JNIPointer): JNIPointer?
    external fun PyErr_SetFromErrnoWithFilename(type: JNIPointer, filename: String): JNIPointer?
    external fun PyErr_SetImportError(msg: JNIPointer, name: JNIPointer, path: JNIPointer): JNIPointer?
    external fun PyErr_SetImportErrorSubclass(exception: JNIPointer, msg: JNIPointer, name: JNIPointer, path: JNIPointer): JNIPointer?
    external fun PyErr_SyntaxLocationEx(filename: String, lineno: Int, col_offset: Int)
    external fun PyErr_SyntaxLocation(filename: String, lineno: Int)
    external fun PyErr_BadInternalCall()
    external fun PyErr_WarnExplicit(category: JNIPointer, message: String, filename: String, lineno: Int, module: String, registry: JNIPointer): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyErr_Occurred(): Long
    external fun PyErr_ExceptionMatches(exc: JNIPointer): Int
    external fun PyErr_GivenExceptionMatches(given: JNIPointer, exc: JNIPointer): Int
    external fun PyErr_GetRaisedException(): JNIPointer?
    external fun PyErr_SetRaisedException(exc: JNIPointer)
    external fun PyErr_Restore(type: JNIPointer, value: JNIPointer, traceback: JNIPointer)
    external fun PyErr_GetHandledException(): JNIPointer?
    external fun PyErr_SetHandledException(exc: JNIPointer)
    external fun PyErr_SetExcInfo(type: JNIPointer, value: JNIPointer, traceback: JNIPointer)
    external fun PyErr_CheckSignals(): Int
    external fun PyErr_SetInterrupt()
    external fun PyErr_SetInterruptEx(signum: Int): Int
    external fun PyErr_NewException(name: String, base: JNIPointer, dict: JNIPointer): JNIPointer?
    external fun PyErr_NewExceptionWithDoc(name: String, doc: String, base: JNIPointer, dict: JNIPointer): JNIPointer?
    external fun PyException_GetTraceback(ex: JNIPointer): JNIPointer?
    external fun PyException_SetTraceback(ex: JNIPointer, tb: JNIPointer): Int
    external fun PyException_GetContext(ex: JNIPointer): JNIPointer?
    external fun PyException_SetContext(ex: JNIPointer, ctx: JNIPointer)
    external fun PyException_GetCause(ex: JNIPointer): JNIPointer?
    external fun PyException_SetCause(ex: JNIPointer, cause: JNIPointer)
    external fun PyException_GetArgs(ex: JNIPointer): JNIPointer?
    external fun PyException_SetArgs(ex: JNIPointer, args: JNIPointer)
    external fun PyUnicodeEncodeError_GetEncoding(exc: JNIPointer): JNIPointer?
    external fun PyUnicodeTranslateError_GetObject(exc: JNIPointer): JNIPointer?
    external fun PyUnicodeTranslateError_GetReason(exc: JNIPointer): JNIPointer?
    external fun PyUnicodeTranslateError_SetReason(exc: JNIPointer, reason: String): Int
    external fun Py_EnterRecursiveCall(where: String): Int
    external fun Py_LeaveRecursiveCall()
    external fun Py_ReprEnter(o: JNIPointer): Int
    external fun Py_ReprLeave(o: JNIPointer)


    // Section 4
    external fun Py_NewRef(o: JNIPointer): JNIPointer?
    external fun Py_XNewRef(o: JNIPointer): JNIPointer?
    external fun Py_IncRef(o: JNIPointer)
    external fun Py_DecRef(o: JNIPointer)


    // Section 5
    external fun PyOS_FSPath(path: JNIPointer): JNIPointer?


    // Section 6
    external fun PySys_GetObject(name: String): JNIPointer?
    external fun PySys_SetObject(name: String, v: JNIPointer): Int
    external fun PySys_ResetWarnOptions()
    external fun PySys_GetXOptions(): JNIPointer?
    external fun PySys_AuditTuple(event: String, args: JNIPointer): Int


    // Section 7
    external fun Py_FatalError(message: String)
    external fun Py_Exit(status: Int)


    // Section 8
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyImport_ImportModule(name: Long): Long
    external fun PyImport_ImportModuleNoBlock(name: String): JNIPointer?
    external fun PyImport_ImportModuleLevelObject(name: JNIPointer, globals: JNIPointer, locals: JNIPointer, fromlist: JNIPointer, level: Int): JNIPointer?
    external fun PyImport_ImportModuleLevel(name: String, globals: JNIPointer, locals: JNIPointer, fromlist: JNIPointer, level: Int): JNIPointer?
    external fun PyImport_Import(name: JNIPointer): JNIPointer?
    external fun PyImport_ReloadModule(m: JNIPointer): JNIPointer?
    external fun PyImport_AddModuleRef(name: String): JNIPointer?
    external fun PyImport_AddModuleObject(name: JNIPointer): JNIPointer?
    external fun PyImport_AddModule(name: String): JNIPointer?
    external fun PyImport_ExecCodeModule(name: String, co: JNIPointer): JNIPointer?
    external fun PyImport_ExecCodeModuleEx(name: String, co: JNIPointer, pathname: String): JNIPointer?
    external fun PyImport_ExecCodeModuleObject(name: JNIPointer, co: JNIPointer, pathname: JNIPointer, cpathname: JNIPointer): JNIPointer?
    external fun PyImport_ExecCodeModuleWithPathnames(name: String, co: JNIPointer, pathname: String, cpathname: String): JNIPointer?
    external fun PyImport_GetMagicTag(): String?
    external fun PyImport_GetModuleDict(): JNIPointer?
    external fun PyImport_GetModule(name: JNIPointer): JNIPointer?
    external fun PyImport_GetImporter(path: JNIPointer): JNIPointer?
    external fun PyImport_ImportFrozenModuleObject(name: JNIPointer): Int
    external fun PyImport_ImportFrozenModule(name: String): Int


    // Section 9
    external fun PyEval_GetBuiltins(): JNIPointer?
    external fun PyEval_GetLocals(): JNIPointer?
    external fun PyEval_GetGlobals(): JNIPointer?
    external fun PyEval_GetFrameBuiltins(): JNIPointer?
    external fun PyEval_GetFrameLocals(): JNIPointer?
    external fun PyEval_GetFrameGlobals(): JNIPointer?
    external fun PyEval_GetFuncName(func: JNIPointer): String?
    external fun PyEval_GetFuncDesc(func: JNIPointer): String?


    // Section 10
    external fun PyObject_HasAttrWithError(o: JNIPointer, attr_name: JNIPointer): Int
    external fun PyObject_HasAttrStringWithError(o: JNIPointer, attr_name: String): Int
    external fun PyObject_HasAttr(o: JNIPointer, attr_name: JNIPointer): Int
    external fun PyObject_HasAttrString(o: JNIPointer, attr_name: String): Int
    external fun PyObject_GetAttr(o: JNIPointer, attr_name: JNIPointer): JNIPointer?
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyObject_GetAttrString(o: Long, attr_name: Long): Long
    external fun PyObject_GenericGetAttr(o: JNIPointer, name: JNIPointer): JNIPointer?
    external fun PyObject_SetAttr(o: JNIPointer, attr_name: JNIPointer, v: JNIPointer): Int
    external fun PyObject_SetAttrString(o: JNIPointer, attr_name: String, v: JNIPointer): Int
    external fun PyObject_GenericSetAttr(o: JNIPointer, name: JNIPointer, value: JNIPointer): Int
    external fun PyObject_DelAttr(o: JNIPointer, attr_name: JNIPointer): Int
    external fun PyObject_DelAttrString(o: JNIPointer, attr_name: String): Int
    external fun PyObject_RichCompare(o1: JNIPointer, o2: JNIPointer, opid: Int): JNIPointer?
    external fun PyObject_RichCompareBool(o1: JNIPointer, o2: JNIPointer, opid: Int): Int
    external fun PyObject_Format(obj: JNIPointer, format_spec: JNIPointer): JNIPointer?
    external fun PyObject_Repr(o: JNIPointer): JNIPointer?
    external fun PyObject_ASCII(o: JNIPointer): JNIPointer?
    external fun PyObject_Str(o: JNIPointer): JNIPointer?
    external fun PyObject_Bytes(o: JNIPointer): JNIPointer?
    external fun PyObject_IsSubclass(derived: JNIPointer, cls: JNIPointer): Int
    external fun PyObject_IsInstance(inst: JNIPointer, cls: JNIPointer): Int
    external fun PyObject_IsTrue(o: JNIPointer): Int
    external fun PyObject_Not(o: JNIPointer): Int
    external fun PyObject_Type(o: JNIPointer): JNIPointer?
    external fun PyObject_Size(o: JNIPointer): Long
    external fun PyObject_Length(o: JNIPointer): Long
    external fun PyObject_GetItem(o: JNIPointer, key: JNIPointer): JNIPointer?
    external fun PyObject_SetItem(o: JNIPointer, key: JNIPointer, v: JNIPointer): Int
    external fun PyObject_DelItem(o: JNIPointer, key: JNIPointer): Int
    external fun PyObject_Dir(o: JNIPointer): JNIPointer?
    external fun PyObject_GetIter(o: JNIPointer): JNIPointer?
    external fun PyObject_GetAIter(o: JNIPointer): JNIPointer?


    // Section 11
    external fun PyVectorcall_Call(callable: JNIPointer, tuple: JNIPointer, dict: JNIPointer): JNIPointer?
    external fun PyObject_Call(callable: JNIPointer, args: JNIPointer, kwargs: JNIPointer): JNIPointer?
    external fun PyObject_CallNoArgs(callable: JNIPointer): JNIPointer?
    external fun PyObject_CallObject(callable: JNIPointer, args: JNIPointer): JNIPointer?
    external fun PyCallable_Check(o: JNIPointer): Int


    // Section 12
    external fun PyNumber_Check(o: JNIPointer): Int
    external fun PyNumber_Add(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Subtract(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Multiply(o1: JNIPointer, o2: JNIPointer): JNIPointer?
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
    external fun PyNumber_And(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Xor(o1: JNIPointer, o2: JNIPointer): JNIPointer?
    external fun PyNumber_Or(o1: JNIPointer, o2: JNIPointer): JNIPointer?
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
    external fun PySequence_Contains(o: JNIPointer, value: JNIPointer): Int
    external fun PySequence_List(o: JNIPointer): JNIPointer?
    external fun PySequence_Tuple(o: JNIPointer): JNIPointer?
    external fun PySequence_Fast(o: JNIPointer, m: String): JNIPointer?


    // Section 14
    external fun PyMapping_Check(o: JNIPointer): Int
    external fun PyMapping_GetItemString(o: JNIPointer, key: String): JNIPointer?
    external fun PyMapping_SetItemString(o: JNIPointer, key: String, v: JNIPointer): Int
    external fun PyMapping_HasKeyWithError(o: JNIPointer, key: JNIPointer): Int
    external fun PyMapping_HasKeyStringWithError(o: JNIPointer, key: String): Int
    external fun PyMapping_HasKey(o: JNIPointer, key: JNIPointer): Int
    external fun PyMapping_HasKeyString(o: JNIPointer, key: String): Int
    external fun PyMapping_Keys(o: JNIPointer): JNIPointer?
    external fun PyMapping_Values(o: JNIPointer): JNIPointer?
    external fun PyMapping_Items(o: JNIPointer): JNIPointer?


    // Section 15
    external fun PyIter_Check(o: JNIPointer): Int
    external fun PyAIter_Check(o: JNIPointer): Int
    external fun PyIter_Next(o: JNIPointer): JNIPointer?


    // Section 16
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyLong_FromLongLong(v: Long): Long
    external fun PyLong_FromDouble(v: Double): JNIPointer?
    external fun PyLong_AsInt(obj: JNIPointer): Int
    external fun PyLong_AsLongLong(obj: JNIPointer): Long
    external fun PyLong_AsDouble(pylong: JNIPointer): Double
    external fun PyLong_GetInfo(): JNIPointer?


    // Section 17
    external fun PyBool_FromLong(v: Long): JNIPointer?


    // Section 18
    external fun PyFloat_FromString(str: JNIPointer): JNIPointer?
    external fun PyFloat_FromDouble(v: Double): JNIPointer?
    external fun PyFloat_AsDouble(pyfloat: JNIPointer): Double
    external fun PyFloat_GetInfo(): JNIPointer?
    external fun PyFloat_GetMax(): Double
    external fun PyFloat_GetMin(): Double


    // Section 19
    external fun PyBytes_FromString(v: String): JNIPointer?
    external fun PyBytes_FromObject(o: JNIPointer): JNIPointer?
    external fun PyBytes_AsString(o: JNIPointer): String?


    // Section 20
    external fun PyByteArray_FromObject(o: JNIPointer): JNIPointer?
    external fun PyByteArray_Concat(a: JNIPointer, b: JNIPointer): JNIPointer?
    external fun PyByteArray_AsString(bytearray: JNIPointer): String?


    // Section 21
    external fun PyUnicode_IsIdentifier(unicode: JNIPointer): Int
    external fun PyUnicode_FromString(str: String): JNIPointer?
    external fun PyUnicode_FromObject(obj: JNIPointer): JNIPointer?
    external fun PyUnicode_FromEncodedObject(obj: JNIPointer, encoding: String, errors: String): JNIPointer?
    external fun PyUnicode_DecodeLocale(str: String, errors: String): JNIPointer?
    external fun PyUnicode_EncodeLocale(unicode: JNIPointer, errors: String): JNIPointer?
    external fun PyUnicode_DecodeFSDefault(str: String): JNIPointer?
    external fun PyUnicode_EncodeFSDefault(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsEncodedString(unicode: JNIPointer, encoding: String, errors: String): JNIPointer?
    external fun PyUnicode_AsUTF8String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsUTF8(unicode: JNIPointer): String? // 수동 추가
    external fun PyUnicode_AsUTF32String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsUTF16String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsUnicodeEscapeString(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsRawUnicodeEscapeString(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsLatin1String(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsASCIIString(unicode: JNIPointer): JNIPointer?
    external fun PyUnicode_AsCharmapString(unicode: JNIPointer, mapping: JNIPointer): JNIPointer?
    external fun PyUnicode_Translate(unicode: JNIPointer, table: JNIPointer, errors: String): JNIPointer?
    external fun PyUnicode_Concat(left: JNIPointer, right: JNIPointer): JNIPointer?
    external fun PyUnicode_Splitlines(unicode: JNIPointer, keepends: Int): JNIPointer?
    external fun PyUnicode_Join(separator: JNIPointer, seq: JNIPointer): JNIPointer?
    external fun PyUnicode_Compare(left: JNIPointer, right: JNIPointer): Int
    external fun PyUnicode_EqualToUTF8(unicode: JNIPointer, string: String): Int
    external fun PyUnicode_CompareWithASCIIString(unicode: JNIPointer, string: String): Int
    external fun PyUnicode_RichCompare(left: JNIPointer, right: JNIPointer, op: Int): JNIPointer?
    external fun PyUnicode_Format(format: JNIPointer, args: JNIPointer): JNIPointer?
    external fun PyUnicode_Contains(unicode: JNIPointer, substr: JNIPointer): Int
    external fun PyUnicode_InternFromString(str: String): JNIPointer?


    // Section 22
    external fun PyList_New(len: Long): JNIPointer?
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyList_Size(list: Long): Long
    external fun PyList_GetItem(list: JNIPointer, index: Long): JNIPointer?
    external fun PyList_SetItem(list: JNIPointer, index: Long, item: JNIPointer): Int
    external fun PyList_Insert(list: JNIPointer, index: Long, item: JNIPointer): Int
    external fun PyList_Append(list: JNIPointer, item: JNIPointer): Int
    external fun PyList_Sort(list: JNIPointer): Int
    external fun PyList_Reverse(list: JNIPointer): Int
    external fun PyList_AsTuple(list: JNIPointer): JNIPointer?


    // Section 23
    external fun PyDict_New(): JNIPointer?
    external fun PyDict_Size(p: JNIPointer): Long
    external fun PyDictProxy_New(mapping: JNIPointer): JNIPointer?
    external fun PyDict_Clear(p: JNIPointer)
    external fun PyDict_Contains(p: JNIPointer, key: JNIPointer): Int
    external fun PyDict_Copy(p: JNIPointer): JNIPointer?
    external fun PyDict_SetItem(p: JNIPointer, key: JNIPointer, v: JNIPointer): Int
    external fun PyDict_SetItemString(p: JNIPointer, key: String, v: JNIPointer): Int
    external fun PyDict_DelItem(p: JNIPointer, key: JNIPointer): Int
    external fun PyDict_DelItemString(p: JNIPointer, key: String): Int
    external fun PyDict_GetItem(p: JNIPointer, key: JNIPointer): JNIPointer?
    external fun PyDict_GetItemWithError(p: JNIPointer, key: JNIPointer): JNIPointer?
    external fun PyDict_GetItemString(p: JNIPointer, key: String): JNIPointer?
    external fun PyDict_Items(p: JNIPointer): JNIPointer?
    external fun PyDict_Keys(p: JNIPointer): JNIPointer?
    external fun PyDict_Values(p: JNIPointer): JNIPointer?
    external fun PyDict_Merge(a: JNIPointer, b: JNIPointer, override: Int): Int
    external fun PyDict_Update(a: JNIPointer, b: JNIPointer): Int
    external fun PyDict_MergeFromSeq2(a: JNIPointer, seq2: JNIPointer, override: Int): Int


    // Section 24
    external fun PySet_New(iterable: JNIPointer): JNIPointer?
    external fun PyFrozenSet_New(iterable: JNIPointer): JNIPointer?
    external fun PySet_Contains(anyset: JNIPointer, key: JNIPointer): Int
    external fun PySet_Size(anyset: JNIPointer): Long
    external fun PySet_Add(set: JNIPointer, key: JNIPointer): Int
    external fun PySet_Discard(set: JNIPointer, key: JNIPointer): Int
    external fun PySet_Pop(set: JNIPointer): JNIPointer?
    external fun PySet_Clear(set: JNIPointer): Int


    // Section 25
    external fun PySeqIter_New(seq: JNIPointer): JNIPointer?
    external fun PyCallIter_New(callable: JNIPointer, sentinel: JNIPointer): JNIPointer?


    // Section 26
    external fun PyWeakref_NewRef(ob: JNIPointer, callback: JNIPointer): JNIPointer?
    external fun PyWeakref_NewProxy(ob: JNIPointer, callback: JNIPointer): JNIPointer?
    external fun PyWeakref_GetObject(ref: JNIPointer): JNIPointer?
    external fun PyObject_ClearWeakRefs(o: JNIPointer)


    // Section 27
    external fun PyType_IsSubtype(a: JNIPointer, b: JNIPointer): Int
    external fun PyType_Ready(type: JNIPointer): Int
    external fun PyType_GetName(type: JNIPointer): JNIPointer?
    external fun PyType_GetFullyQualifiedName(type: JNIPointer): JNIPointer?
    external fun PyType_GetModuleName(type: JNIPointer): JNIPointer?
    external fun PyType_GetModule(type: JNIPointer): JNIPointer?


    // Section 28
    external fun PyTuple_New(len: Long): JNIPointer?
    external fun PyTuple_Size(p: JNIPointer): Long
    external fun PyTuple_GetItem(p: JNIPointer, pos: Long): JNIPointer?
    external fun PyTuple_GetSlice(p: JNIPointer, low: Long, high: Long): JNIPointer?
    external fun PyTuple_SetItem(p: JNIPointer, pos: Long, o: JNIPointer): Int


    // Section 29
    external fun PyModule_GetName(module: JNIPointer): String?
    external fun PyModule_GetDict(module: JNIPointer): JNIPointer?
    external fun PyModule_GetFilenameObject(module: JNIPointer): JNIPointer?


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
}
