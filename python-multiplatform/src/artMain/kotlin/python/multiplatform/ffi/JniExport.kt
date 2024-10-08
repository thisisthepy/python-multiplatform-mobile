package python.multiplatform.ffi

import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.*
import platform.android.*
import platform.posix.*
import python.native.ffi.*


private const val packageName = "python_multiplatform_ffi"
private const val exportClassName = "Python3"
private const val namePrefix = "Java_${packageName}_${exportClassName}_"


@CName("${namePrefix}initialize")
@OptIn(ExperimentalNativeApi::class)
fun initialize() {
    Python3.initialize()
}

@CName("${namePrefix}pyInitializeEx")
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
fun pyInitializeEx() {
    println("Python initialization start")
    Py_InitializeEx(0)
    println("Python initialization end")
}


@CName("${namePrefix}pyInitializeFromConfig")
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
fun pyInitializeFromConfig(/*env: CPointer<JNIEnvVar>, home: jstring, runModule: jstring*/): Int {
    return memScoped {
        val config = alloc<PyConfig>()
        PyConfig_InitIsolatedConfig(config.ptr)

//        PyConfig_SetBytesString
//
//        var status = setConfigString(env, config.ptr, config.home.ptr, home)
//        if (PyStatus_Exception(status) == 1) {
//            println("Failed to set home")
//            return 1
//        }
//
//        status = setConfigString(env, config.ptr, config.run_module.ptr, runModule)
//        if (PyStatus_Exception(status) == 1) {
//            println("Failed to set run_module")
//            return 1
//        }

        config.install_signal_handlers = 1

        val status = Py_InitializeFromConfig(config.ptr)
        if (PyStatus_Exception(status) == 1) {
            println("Failed to initialize from config")
            return 1
        }
        println("Succeed to initialize from config")

        return Py_RunMain()
    }
}

@CName("${namePrefix}internalIsInitialized")
@OptIn(ExperimentalNativeApi::class)
fun internalIsInitialized(): Boolean {
    return Python3.isInitialized
}

@CName("${namePrefix}finalize")
@OptIn(ExperimentalNativeApi::class)
fun finalize() {
    Python3.finalize()
}


@OptIn(ExperimentalNativeApi::class)
@CName("${namePrefix}sayHello")
fun sayHello() {
    __android_log_print(ANDROID_LOG_INFO.toInt(), "Kn", "Hello %s", "Native")
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("${namePrefix}stringFromJNI")
fun stringFromJNI(env: CPointer<JNIEnvVar>, thiz: jobject): jstring {
    memScoped {
        return env.pointed.pointed!!.NewStringUTF!!.invoke(env, "This is from Kotlin Native!!".cstr.ptr)!!
    }
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("${namePrefix}callJava")
fun callJava(env: CPointer<JNIEnvVar>, thiz: jobject): jstring {
    memScoped {
        val jniEnvVal = env.pointed.pointed!!
        val jclass = jniEnvVal.GetObjectClass!!.invoke(env, thiz)
        val methodId = jniEnvVal.GetMethodID!!.invoke(env, jclass,
            "callFromNative".cstr.ptr, "()Ljava/lang/String;".cstr.ptr)
        return jniEnvVal.CallObjectMethodA!!.invoke(env, thiz, methodId, null) as jstring
    }
}

//@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
//@CName("JNI_OnLoad")
//fun JNI_OnLoad(vm: CPointer<JavaVMVar>, preserved: COpaquePointer): jint {
//    return memScoped {
//        val envStorage = alloc<CPointerVar<JNIEnvVar>>()
//        val vmValue = vm.pointed.pointed!!
//        val result = vmValue.GetEnv!!(vm, envStorage.ptr.reinterpret(), JNI_VERSION_1_6)
//        __android_log_print(ANDROID_LOG_INFO.toInt(), "Python Multiplatform", "JNI_OnLoad")
//        if (result == JNI_OK) {
////            val env = envStorage.pointed!!.pointed!!
////            val jclass = env.FindClass!!(envStorage.value, "com/example/hellojni/HelloJni".cstr.ptr)
////
////            val jniMethod = allocArray<JNINativeMethod>(1)
////            jniMethod[0].fnPtr = staticCFunction(::sayHello2)
////            jniMethod[0].name = "sayHello2".cstr.ptr
////            jniMethod[0].signature = "()V".cstr.ptr
////            env.RegisterNatives!!(envStorage.value, jclass, jniMethod, 1)
//
//            __android_log_print(ANDROID_LOG_INFO.toInt(), "Kn", "register say hello2, %d, %d", sizeOf<CPointerVar<JNINativeMethod>>(), sizeOf<JNINativeMethod>())
//        }
//        JNI_VERSION_1_6
//    }
//}
