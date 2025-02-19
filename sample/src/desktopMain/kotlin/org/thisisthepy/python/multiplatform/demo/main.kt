package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import python.multiplatform.ffi.Python3
import python.native.ffi.*
import java.nio.charset.Charset


fun main() = application {
    Python3.initialize()

    val pylong = PyLong_FromLongLong(1234567890)
    println("pylong: $pylong")
    if (pylong == null) {
        println("PyLong_FromLongLong failed")
    } else {
        val pyptr = pylong.toRawValue()
        println("pyptr: $pyptr")
        val pyptr1 = 0.0 + pyptr
        val recoverd = pyptr1.toLong().toNativePointer()
        println("recoverd: $recoverd")
        println("address value: ${recoverd?.toAddressValue()}")
        val result = recoverd?.let { PyLong_AsLongLong(it) }
        println("result: $result")
    }

    val consoleEncoding = System.console()?.charset()?.name() ?: Charset.defaultCharset().displayName()

    // Sync Python stdout/stderr encoding with Java console encoding
    PyRun_SimpleString("""
        import sys
        import io
        print("Python original sys.getdefaultencoding():", sys.getdefaultencoding())
        #sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='${consoleEncoding}')
        #sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='${consoleEncoding}')
        print("Python sys.getdefaultencoding():", sys.getdefaultencoding())
        print("Python sys.stdout.encoding:", sys.stdout.encoding)
    """.trimIndent())


    val code = "print('Hello, 안녕 Python!', flush=True)"
    println(code)
    PyRun_SimpleString(code)
    val codeConv = PyUnicode_FromString(code)
    println(codeConv)
    val codeConvStr = codeConv?.let { PyUnicode_AsUTF8(it) }
    println(codeConvStr)


    Window(
        onCloseRequest = { Python3.finalize(); exitApplication() },
        title = "PythonMultiplatform",
    ) {
        App()
    }
}
