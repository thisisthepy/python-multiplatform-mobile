package com.example

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString


object PythonIntegration {
    private var py: Python3Library? = null

    @JvmStatic
    fun main(args: Array<String>) {
        py = Python3Library.INSTANCE

        try {
            initializePython()
            exampleUsage()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            finalizePython()
        }
    }

    private fun initializePython() {
        py!!.Py_Initialize()
        if (py!!.Py_IsInitialized() == 0) {
            throw RuntimeException("Failed to initialize Python")
        }
    }

    private fun finalizePython() {
        if (py!!.Py_IsInitialized() != 0) {
            py!!.Py_Finalize()
        }
    }

    private fun exampleUsage() {
        // 모듈 임포트 예제
        val mathModule = importModule("math")

        // 함수 호출 예제
        callFunction(mathModule, "pow", 2.0, 3.0)

        // 리스트 조작 예제
        manipulateList()

        // math 모듈 참조 해제
        py!!.Py_DecRef(mathModule)
    }

    private fun importModule(moduleName: String): Pointer {
        val module = py!!.PyImport_ImportModule(moduleName)
        checkPythonError()
        if (module == null) {
            throw RuntimeException("Failed to import module: $moduleName")
        }
        return module
    }

    private fun manipulateList() {
        val pyList = py!!.PyList_New(3)
        py!!.PyList_SetItem(pyList, 0, py!!.PyLong_FromLong(1))
        py!!.PyList_SetItem(pyList, 1, py!!.PyLong_FromLong(2))
        py!!.PyList_SetItem(pyList, 2, py!!.PyLong_FromLong(3))

        println("List size: " + py!!.PyList_Size(pyList))
        for (i in 0 until py!!.PyList_Size(pyList)) {
            val item = py!!.PyList_GetItem(pyList, i)
            println("Item " + i + ": " + py!!.PyLong_AsLong(item))
        }

        py!!.Py_DecRef(pyList)
    }

    private fun callFunction(module: Pointer, funcName: String, vararg args: Any) {
        val func = py!!.PyObject_GetAttrString(module, funcName)

        checkPythonError()
        if (func == null) {
            throw java.lang.RuntimeException("Failed to get function: $funcName")
        }

        val pyArgs = py!!.PyTuple_New(args.size)
        for (i in args.indices) {
            val arg = convertJavaToPython(args[i])
            println("The type of variable is ${arg::class.simpleName}")
            py!!.PyTuple_SetItem(pyArgs, i, arg)
        }
        checkPythonError()

        val result = py!!.PyObject_CallObject(func, pyArgs)
        checkPythonError()

        if (result != null) {
            println(funcName + " result: " + convertPythonToJava(result))
            py!!.Py_DecRef(result)
        }
        checkPythonError()

        py!!.Py_DecRef(pyArgs)
        py!!.Py_DecRef(func)
    }

    private fun convertJavaToPython(obj: Any): Pointer {
        if (obj is Int || obj is Long) {
            println("obj is converted to PyLong: $obj")
            return py!!.PyLong_FromLong((obj as Number).toLong())
        } else if (obj is Float || obj is Double) {
            println("obj is converted to PyFloat: $obj")
            return py!!.PyFloat_FromDouble((obj as Number).toDouble())
        } else if (obj is String) {
            println("obj is converted to String: $obj")
            return py!!.PyUnicode_FromString(obj)
        }
        throw UnsupportedOperationException("Unsupported type: " + obj.javaClass)
    }

    private fun convertPythonToJava(pyObj: Pointer): Any {
        // This is a simplistic conversion. In a real-world scenario, you'd need more type checking.
        val result1 = py!!.PyLong_AsLong(pyObj)
        if (py!!.PyErr_Occurred() == null) {
            return result1
        }
        py!!.PyErr_Clear()

        val result2 = py!!.PyFloat_AsDouble(pyObj)
        if (py!!.PyErr_Occurred() == null) {
            return result2
        }
        py!!.PyErr_Clear()

        val result3 = py!!.PyUnicode_AsUTF8(pyObj)
        if (py!!.PyErr_Occurred() == null) {
            return result3
        }
        py!!.PyErr_Clear()

        throw UnsupportedOperationException("Unsupported type")
    }

    private fun checkPythonError() {
        val pErr = py!!.PyErr_Occurred()
        if (pErr != null) {
            py!!.PyErr_Print()
            py!!.PyErr_Clear()
            throw RuntimeException("Python error occurred")
        }
    }

    interface Python3Library : Library {
        // 초기화 및 종료
        fun Py_Initialize()
        fun Py_Finalize()
        fun Py_SetProgramName(name: WString?)
        fun Py_IsInitialized(): Int

        // 모듈 및 객체 관리
        fun PyImport_ImportModule(name: String?): Pointer
        fun PyObject_GetAttrString(obj: Pointer?, attrName: String?): Pointer?
        fun PyObject_HasAttrString(obj: Pointer?, attrName: String?): Int
        fun PyObject_CallObject(callable: Pointer?, args: Pointer?): Pointer?
        fun PyObject_CallFunctionObjArgs(callable: Pointer?, vararg args: Any?): Pointer?
        fun Py_IncRef(obj: Pointer?)
        fun Py_DecRef(obj: Pointer?)

        // 타입 변환
        fun PyLong_FromLong(v: Long): Pointer
        fun PyLong_AsLong(obj: Pointer?): Long
        fun PyFloat_FromDouble(v: Double): Pointer
        fun PyFloat_AsDouble(obj: Pointer?): Double
        fun PyUnicode_FromString(u: String?): Pointer
        fun PyUnicode_AsUTF8(unicode: Pointer?): String

        // 컬렉션
        fun PyList_New(size: Int): Pointer
        fun PyList_Size(list: Pointer?): Int
        fun PyList_GetItem(list: Pointer?, index: Int): Pointer
        fun PyList_SetItem(list: Pointer?, index: Int, item: Pointer?): Int
        fun PyTuple_New(size: Int): Pointer
        fun PyTuple_Size(tuple: Pointer?): Int
        fun PyTuple_GetItem(tuple: Pointer?, index: Int): Pointer?
        fun PyTuple_SetItem(tuple: Pointer?, index: Int, item: Pointer?): Int

        // 예외 처리
        fun PyErr_Occurred(): Pointer?
        fun PyErr_Print()
        fun PyErr_Clear()

        // 기타
        fun PyRun_SimpleString(command: String?): Int
        fun PyEval_GetBuiltins(): Pointer?

        companion object {
            val INSTANCE: Python3Library = Native.load(
                "python3",
                Python3Library::class.java
            )
        }
    }
}
