//package python.multiplatform.ffi
//
//import kotlin.reflect.KClass
//
//
//object ClassLookup {
//    private val classMap = mapOf<String, Any>(
//        "kotlin" to mapOf<String, Any>(
//            "reflect" to mapOf<String, Any>(
//                "KClass" to KClass::class
//            )
//        ),
//        "python" to mapOf<String, Any>(
//            "multiplatform" to mapOf<String, Any>(
//                "currentPlatform" to python.multiplatform.currentPlatform::class,
//            )
//        )
//    )
//
//    private const val CACHE_SIZE = 100
//    private val lruCache = LRUCache<String, KClass<*>>(CACHE_SIZE)
//
//    fun findClass(packageName: String): KClass<*>? {
//        if (packageName in lruCache) {
//            return lruCache.get(packageName)  // From cache
//        }
//
//        val parts = packageName.split(".")
//        var currentMap: Any? = classMap
//        for (part in parts) {
//            if (currentMap is Map<*, *>) {
//                currentMap = currentMap[part]
//            } else {
//                return null  // Not matched while searching
//            }
//        }
//
//        return currentMap as? KClass<*>
//    }
//
//    fun getMember(kClass: KClass<*>, memberName: String): Any? {
//        return kClass.members.find { it.name == memberName }
//    }
//
//    fun invokeStaticMethod(kClass: KClass<*>, methodName: String, vararg args: Any?): Any? {
//        val method = kClass.declaredFunctions.find { it.name == methodName }
//        method?.isAccessible = true
//        return method?.call(*args)
//    }
//
//    fun invokeStaticMethodWithPackageName(packageName: String, methodName: String, vararg args: Any): Any? {
//        val kClass = findClass
//    }
//}
//
//
//private class LRUCache<K, V>(val capacity: Int) {
//    private val map: MutableMap<K, V> = linkedMapOf()
//
//    fun get(key: K): V? {
//        return map[key]
//    }
//
//    fun put(key: K, value: V) {
//        if (key in map) {
//            map.remove(key)  // Update the key ordering
//        } else if (map.size >= capacity) {
//            map.entries.iterator().next().also { map.remove(it.key) }
//        }
//        map[key] = value
//    }
//
//    fun containsKey(packageName: K): Boolean {
//        return map.containsKey(packageName)
//    }
//
//    operator fun contains(packageName: K): Boolean {
//        return containsKey(packageName)
//    }
//}
