package python.multiplatform.reflection


/**
 * Name -> [ReflectedClass] registry.
 *
 * Populated by [UpcallTable] as fragments are registered, and cleared alongside it -- kept
 * separate because class lookup (`type()`, `isinstance()`) is a distinct Python-visible operation
 * from callable resolution, even though both come from the same fragments.
 */
object ClassLookup {

    private val classes = LinkedHashMap<String, ReflectedClass>()

    internal fun register(cls: ReflectedClass) {
        classes[cls.name] = cls
    }

    internal fun clear() {
        classes.clear()
    }

    internal val count: Int get() = classes.size

    /** The class registered under [name], or `null` if nothing claims it. */
    fun find(name: String): ReflectedClass? = classes[name]

    /** [find], but throws where the caller has no better response than to give up. */
    fun require(name: String): ReflectedClass =
        find(name) ?: throw IllegalArgumentException("no such exposed class: $name")
}
