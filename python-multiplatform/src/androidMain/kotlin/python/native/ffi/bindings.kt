package python.native.ffi

import python.native.ffi.manager.loadLibPython

typealias JNIPointer = Long


object bindings {
    init {
        loadLibPython()
    }

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
}
