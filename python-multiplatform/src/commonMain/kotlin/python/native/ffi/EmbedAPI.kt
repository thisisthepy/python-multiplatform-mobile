package python.native.ffi

import kotlin.jvm.JvmInline


/**
 * Runs given [block] providing allocation of memory
 * which will be automatically disposed at the end of this scope.
 */
expect inline fun <R : Any> memScoped(block: () -> R): R


interface AddressValue
@JvmInline
value class NativePointer internal constructor(val address: Any) {
    override fun toString(): String =
        "${this::class.simpleName}(self=0x${hashCode().toUInt().toString(16)}, target=0x${toRawValue().toString(16)})"
}
@HighOverheadNativeCall
expect fun NativePointer.toAddressValue(): AddressValue
expect inline fun NativePointer.toRawValue(): Long
@HighOverheadNativeCall
expect fun AddressValue.toNativePointer(): NativePointer
@HighOverheadNativeCall
expect fun Long.toNativePointer(): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 1
// Initializing and finalizing the interpreter
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 * Part of the Stable ABI.
 *
 * Initialize the Python interpreter.
 * In an application embedding Python, this should be called before using any other Python/C API functions; see Before Python Initialization for the few exceptions.
 *
 * This initializes the table of loaded modules (sys.modules), and creates the fundamental modules builtins, __main__ and sys. It also initializes the module search path (sys.path).
 * It does not set sys.argv; use the Python Initialization Configuration API for that. This is a no-op when called for a second time (without calling Py_FinalizeEx() first).
 * There is no return value; it is a fatal error if the initialization fails.
 *
 * Use Py_InitializeFromConfig() to customize the Python Initialization Configuration.
 *
 * Note: On Windows, changes the console mode from O_TEXT to O_BINARY, which will also affect non-Python uses of the console using the C Runtime.
 */
expect inline fun Py_Initialize()

/**
 * Part of the Stable ABI.
 *
 * This function works like Py_Initialize() if initsigs is 1.
 * If initsigs is 0, it skips initialization registration of signal handlers, which may be useful when CPython is embedded as part of a larger application.
 *
 * Use Py_InitializeFromConfig() to customize the Python Initialization Configuration.
 *
 * @param initsigs: 0(skip signal handler registration) or 1(normal initialization)
 */
expect inline fun Py_InitializeEx(initsigs: Int)

//expect inline fun Py_InitializeFromConfig()

/**
 * Part of the Stable ABI.
 *
 * Return true (nonzero) when the Python interpreter has been initialized, false (zero) if not.
 * After Py_FinalizeEx() is called, this returns false until Py_Initialize() is called again.
 *
 * @return 1(true), 0(false)
 */
expect inline fun Py_IsInitialized(): Int

/**
 * Part of the Stable ABI since version 3.13.
 *
 * Return true (non-zero) if the main Python interpreter is shutting down.
 * Return false (zero) otherwise.
 *
 * Added in version 3.13.
 */
expect inline fun Py_IsFinalizing(): Int

/**
 * Part of the Stable ABI.
 *
 * This is a backwards-compatible version of Py_FinalizeEx() that disregards the return value.
 */
expect inline fun Py_Finalize()

/**
 * Part of the Stable ABI since version 3.6.
 *
 * Undo all initializations made by Py_Initialize() and subsequent use of Python/C API functions, and destroy all sub-interpreters (see Py_NewInterpreter() below) that were created and not yet destroyed since the last call to Py_Initialize().
 * Ideally, this frees all memory allocated by the Python interpreter.
 * This is a no-op when called for a second time (without calling Py_Initialize() again first).
 *
 * Since this is the reverse of Py_Initialize(), it should be called in the same thread with the same interpreter active.
 * That means the main thread and the main interpreter.
 * This should never be called while Py_RunMain() is running.
 *
 * Normally the return value is 0.
 * If there were errors during finalization (flushing buffered data), -1 is returned.
 *
 * This function is provided for a number of reasons.
 * An embedding application might want to restart Python without having to restart the application itself.
 * An application that has loaded the Python interpreter from a dynamically loadable library (or DLL) might want to free all memory allocated by Python before unloading the DLL.
 * During a hunt for memory leaks in an application a developer might want to free all memory allocated by Python before exiting from the application.
 *
 * Bugs and caveats:
 * The destruction of modules and objects in modules is done in random order; this may cause destructors (__del__() methods) to fail when they depend on other objects (even functions) or modules.
 * Dynamically loaded extension modules loaded by Python are not unloaded.
 * Small amounts of memory allocated by the Python interpreter may not be freed (if you find a leak, please report it).
 * Memory tied up in circular references between objects is not freed.
 * Some memory allocated by extension modules may not be freed.
 * Some extensions may not work properly if their initialization routine is called more than once; this can happen if an application calls Py_Initialize() and Py_FinalizeEx() more than once.
 *
 * Raises an auditing event cpython._PySys_ClearAuditHooks with no arguments.
 *
 * @return 0(success), -1(failure)
 */
expect inline fun Py_FinalizeEx(): Int

/**
 * Part of the Stable ABI since version 3.8.
 *
 * Similar to Py_Main() but argv is an array of bytes strings, allowing the calling application to delegate the text decoding step to the CPython runtime.
 *
 * Added in version 3.8.
 */
expect inline fun Py_BytesMain(args: Array<String>): Int

/**
 * Executes the main module in a fully configured CPython runtime.
 *
 * Executes the command (PyConfig.run_command), the script (PyConfig.run_filename) or the module (PyConfig.run_module) specified on the command line or in the configuration.
 * If none of these values are set, runs the interactive Python prompt (REPL) using the __main__ module’s global namespace.
 *
 * If PyConfig.inspect is not set (the default), the return value will be 0 if the interpreter exits normally (that is, without raising an exception), or 1 if the interpreter exits due to an exception.
 * If an otherwise unhandled SystemExit is raised, the function will immediately exit the process instead of returning 1.
 *
 * If PyConfig.inspect is set (such as when the -i option is used), rather than returning when the interpreter exits, execution will instead resume in an interactive Python prompt (REPL) using the __main__ module’s global namespace.
 * If the interpreter exited with an exception, it is immediately raised in the REPL session.
 * The function return value is then determined by the way the REPL session terminates: returning 0 if the session terminates without raising an unhandled exception, exiting immediately for an unhandled SystemExit, and returning 1 for any other unhandled exception.
 *
 * This function always finalizes the Python interpreter regardless of whether it returns a value or immediately exits the process due to an unhandled SystemExit exception.
 *
 * See Python Configuration for an example of a customized Python that always runs in isolated mode using Py_RunMain().
 */
expect inline fun Py_RunMain(): Int

/**
 * Executes the Python source code from command in the __main__ module according to the flags argument.
 * If __main__ does not already exist, it is created.
 * Returns 0 on success or -1 if an exception was raised.
 * If there was an error, there is no way to get the exception information.
 * For the meaning of flags, see below.
 *
 * Note that if an otherwise unhandled SystemExit is raised, this function will not return -1, but exit the process, as long as PyConfig.inspect is zero.
 */
expect inline fun PyRun_SimpleString(command: String): Int

/**
 * Return value: New reference.
 *
 * Execute Python source code from str in the context specified by the objects globals and locals with the compiler flags specified by flags.
 * globals must be a dictionary; locals can be any object that implements the mapping protocol.
 * The parameter start specifies the start token that should be used to parse the source code.
 *
 * Returns the result of executing the code as a Python object, or NULL if an exception was raised.
 */
expect fun PyRun_String(str: String, start: Int, globals: NativePointer, locals: NativePointer): NativePointer?

/**
 * Part of the Stable ABI.
 *
 * Return the version of this Python interpreter. This is a string that looks something like
 * "3.0a5+ (py3k:63103M, May 12 2008, 00:53:55) \n[GCC 4.2.3]"
 *
 * The first word (up to the first space character) is the current Python version; the first characters are the major and minor version separated by a period. The returned string points into static storage; the caller should not modify its value. The value is available to Python code as sys.version.
 *
 * See also the Py_Version constant.
 */
expect inline fun Py_GetVersion(): String?

/**
 * Part of the Stable ABI.
 *
 * Return the platform identifier for the current platform.
 * On Unix, this is formed from the “official” name of the operating system, converted to lower case, followed by the major revision number;
 * e.g., for Solaris 2.x, which is also known as SunOS 5.x, the value is 'sunos5'.
 * On macOS, it is 'darwin'. On Windows, it is 'win'.
 * The returned string points into static storage; the caller should not modify its value.
 * The value is available to Python code as sys.platform.
 */
expect inline fun Py_GetPlatform(): String?

/**
 * Part of the Stable ABI.
 *
 * Return the official copyright string for the current Python version, for example
 *
 * 'Copyright 1991-1995 Stichting Mathematisch Centrum, Amsterdam'
 *
 * The returned string points into static storage; the caller should not modify its value.
 * The value is available to Python code as sys.copyright.
 */
expect inline fun Py_GetCopyright(): String?

/**
 * Part of the Stable ABI.
 *
 * Return an indication of the compiler used to build the current Python version, in square brackets, for example:
 * "[GCC 2.7.2.2]"
 *
 * The returned string points into static storage; the caller should not modify its value.
 * The value is available to Python code as part of the variable sys.version.
 */
expect inline fun Py_GetCompiler(): String?

/**
 * Part of the Stable ABI.
 *
 * Return information about the sequence number and build date and time of the current Python interpreter instance, for example
 * "#67, Aug  1 1997, 22:34:28"
 *
 * The returned string points into static storage; the caller should not modify its value.
 * The value is available to Python code as part of the variable sys.version.
 */
expect inline fun Py_GetBuildInfo(): String?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 2
// Exception handling
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
expect fun PyErr_Occurred(): NativePointer?
//expect inline fun PyErr_Clear()
//expect inline fun PyErr_Print()



expect fun PyLong_FromLongLong(v: Long): NativePointer?
expect inline fun PyLong_AsLongLong(p: NativePointer): Long
expect inline fun PyLong_AsInt(p: NativePointer): Int


expect fun PyUnicode_FromString(str: String): NativePointer?
expect inline fun PyUnicode_AsUTF8(unicode: NativePointer): String?


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
