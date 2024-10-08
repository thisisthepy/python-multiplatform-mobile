//package python.multiplatform.reflection.objc
//
//import kotlinx.cinterop.ObjCClass
//import platform.objc.objc_getClass
//import platform.objc.objc_msgSend
//import platform.objc.sel_registerName
//import python.multiplatform.reflection.ReflectedClass
//import kotlin.reflect.KClass
//
//
//class ObjcClass(private val className: String) : ReflectedClass {
//    val kClass: KClass<*>? = try {
//        Class.forName(className).kotlin
//    } catch (e: ClassNotFoundException) {
//        null
//    }
//
//    fun getObjcClass(): Any? {
//        return objc_getClass(className)
//    }
//
//    fun callMethod(instance: Any, methodName: String, vararg args: Any?): Any? {
//        val selector = sel_registerName(methodName)
//        return objc_msgSend(instance, selector, *args)
//    }
//
//    fun getVariable(instance: Any, variableName: String): Any? {
//        val selector = sel_registerName(variableName)
//        return objc_msgSend(instance, selector)
//    }
//}
//
//fun <T : Any> getObjcClassFromKotlin(kClass: KClass<T>): ObjCClass? {
//    return kClass.objectInstance?.let { instance ->
//        if (instance is NSObject) {
//            instance.objCClass
//        } else {
//            null
//        }
//    }
//}
