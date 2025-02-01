package python.native.ffi

import kotlin.jvm.JvmInline


/**
 * Runs given [block] providing allocation of memory
 * which will be automatically disposed at the end of this scope.
 */
expect inline fun <R : Any> memScoped(block: () -> R): R

class IncompatiblePointerConversionException(
    obj: Any? = null,
    message: String = "Incompatible type conversion request (Object $obj to PyPointer)",
    private var escalated: Boolean = true
) : IllegalArgumentException(message) {
    fun escalate() {
        escalated = true
    }
    override fun toString(): String {
        return (if (escalated) "IncompatiblePointerConversionException" else "WARNING") + ": ${super.message}"
    }
}

interface NativePointer3 {
    fun toRawValue(): Long
}
expect inline fun Long.toNativePointer(): NativePointer3?

interface AddressValue

@JvmInline
value class NativePointer2(val rawValue: Long) {
    override fun toString(): String = "${this::class.simpleName}(${this.rawValue})"
}
expect inline fun NativePointer2.toPlatformPointer(): Any?
expect inline fun nativePointer2Of(address: Any): NativePointer2?

@JvmInline
value class NativePointer internal constructor(val address: Any) {
    override fun toString(): String = "${this::class.simpleName}(${this.toRawLongValue()})"

    companion object {
        inline fun from(address: Any?, silent: Boolean = false, escalateIntoException: Boolean = false): NativePointer? = fromAddress(address, silent, escalateIntoException)
    }
}
expect fun nativePointerOf(address: AddressValue): NativePointer
expect fun nativePointerOf(rawValue: Long): NativePointer?

const val INVALID_POINTER: ULong = 0UL

fun processPointer(rawValue: ULong) {
    require(rawValue != INVALID_POINTER) { "Invalid pointer: null pointer received." }
    // 유효한 포인터 처리
}



/**
 * Converts given [address] to [NativePointer].
 * If [address] is not a valid pointer, returns null.
 *
 *             // silent  escalateIntoError
 *             // false   false       -> print warning and return null
 *             // false   true        -> print warning and throw exception
 *             // true    true        -> throw exception
 *             // true    false       -> return null
 */
internal expect inline fun fromAddress(address: Any?, silent: Boolean = false, escalateIntoException: Boolean = false): NativePointer?
expect inline fun NativePointer.toRawLongValue(): Long
expect inline fun NativePointer.toPlatformPointer(): Any


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

// expect inline fun Py_IsFinalizing(): Int

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


expect inline fun Py_RunSimpleString(code: String): Int


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
