# Python Multiplatform

![Build](https://github.com/thisisthepy/toolchain/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)


### Description

A multiplatform solution to use Python with Kotlin interoperably.

Thank you to many contributors who develop dependent packages for this python-kotlin library production.


#### Supporting multiplatforms:

- Android (arm64, arm32, x86, x86_64) by [Kivy Android ToolChain](https://github.com/thisisthepy/toolchain-android)
- iOS (arm64) by [Kivy ios Toolchain](https://github.com/thisisthepy/toolchain-ios)
- masOS (universal) by [Python Standalone Builds](https://github.com/indygreg/python-build-standalone)
- Linux (x86_64) by [Python Standalone Builds](https://github.com/indygreg/python-build-standalone)
- Windows (x86_64) by [Python Standalone Builds](https://github.com/indygreg/python-build-standalone)
- WASM - Not yet supported.

** Since Xcode only runs on macOS, you need macOS to build this repo for iOS.


### Template ToDo list
- [x] Bring python embed API for Kotlin/JVM targets (Windows, Linux, macOS, Android).
- [x] Bring python embed API for Kotlin/Native targets (iOS).
- [x] Python interop API (Binder) for Kotlin side.
- [ ] Kotlin interop API (Binder) for Python side.

___

## Build Manually

### (1) Clone this repo

- RC version

    $ git clone https://github.com/thisisthepy/python-multiplatform-mobile PythonMultiplatform

- dev version

    $ git clone https://github.com/thisisthepy/python-multiplatform-mobile@develop PythonMultiplatform

- release version

    $ git clone https://github.com/thisisthepy/python-multiplatform-mobile@python3.13 PythonMultiplatform


### (2) Build gradle project

This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop.

* `/composeApp` is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - `commonMain` is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    `iosMain` would be the right folder for such calls.

* `/iosApp` contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform, 
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.


Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [GitHub](https://github.com/JetBrains/compose-multiplatform/issues).

You can open the web application by running the `:composeApp:wasmJsBrowserDevelopmentRun` Gradle task.


---

## Use Pre-Built Package

### (1) Maven Repo (Release only)

In your project build.gradle.kts

    implementation("org.thisisthepy.python:python-multiplatform:0.0.1")

### (2) Jitpack (for Pre-release)

In your project settings.gradle.kts

    jitpack.io


In your project build.gradle.kts

    implementation("com.github.thisisthepy:python-multiplatform-mobile:0.0.1")

---

## Usage

In your main method,

```kotlin

object PythonIntegration {
    val python = Python3Library()
    
    @JvmStatic
    fun main(args: Array<String>) {

        python!!.Py_Initialize()  // Run python interprepter

        if (python!!.Py_IsInitialized() == 0) {
            throw RuntimeException("Failed to initialize Python")
        }
        
        val module = py!!.PyImport_ImportModule(moduleName)  // Module import
        if (module == null) {
            throw RuntimeException("Failed to import module: $moduleName")
        }

        callFunction(mathModule, "pow", 2.0, 3.0)

        val func = py!!.PyObject_GetAttrString(module, funcName)

        if (func == null) {
            throw java.lang.RuntimeException("Failed to get function: $funcName")
        }

        val pyArgs = py!!.PyTuple_New(args.size)
        for (i in args.indices) {
            val arg = convertJavaToPython(args[i])
            println("The type of variable is ${arg::class.simpleName}")
            py!!.PyTuple_SetItem(pyArgs, i, arg)
        }

        val result = py!!.PyObject_CallObject(func, pyArgs)

        if (result != null) {
            println(funcName + " result: " + convertPythonToJava(result))
            py!!.Py_DecRef(result)
        }

        py!!.Py_DecRef(pyArgs)
        py!!.Py_DecRef(func)

        python!!.Py_DecRef(mathModule)

        if (python!!.Py_IsInitialized() != 0) {
            python!!.Py_Finalize()
        }
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

}

```
