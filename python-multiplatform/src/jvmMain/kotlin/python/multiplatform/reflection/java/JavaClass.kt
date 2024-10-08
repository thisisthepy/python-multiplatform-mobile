package python.multiplatform.reflection.java

import python.multiplatform.reflection.ReflectedClass
import kotlin.reflect.KClass


class JavaClass(private val className: String) : ReflectedClass {
    val kClass: KClass<*>? = try {
        Class.forName(className).kotlin
    } catch (e: ClassNotFoundException) {
        null
    }

    fun getJavaClass(): Class<*>? {
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            null
        }
    }
    /*

    fun callMethod(instance: Any, methodName: String, vararg args: Any?): Any? {
        val method = kClass?.declaredMemberFunctions?.find { it.name == methodName }
        return method?.call(instance, *args)
    }

    fun getVariable(instance: Any, variableName: String): Any? {
        val property = kClass?.declaredMemberProperties?.find { it.name == variableName }
        return property?.get(instance)
    }*/
}