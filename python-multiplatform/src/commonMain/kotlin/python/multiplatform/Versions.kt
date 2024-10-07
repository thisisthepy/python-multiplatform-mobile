package python.multiplatform


/*
 * Supported versions of Python
 */
enum class Versions(val versionString: String) {
    PYTHON_3_13_0("3.13.0");

    companion object {
        val currentVersion = valueOf(BuildConfig.pythonVersion)

        fun valueOf(versionString: String): Versions {
            val found = entries.find { it.versionString == versionString }
            if (found == null) {
                throw IllegalArgumentException("Unsupported Python version: $versionString")
            }
            return found
        }
    }

    override fun toString(): String {
        return versionString
    }

    val compactVersionString: String
        get() = versionString.split(".").subList(0, 2).joinToString(".")

    val majorVersion
        get() = versionString.split(".")[0].toInt()

    val minorVersion
        get() = versionString.split(".")[1].toInt()

    val getPatchVersion
        get() = versionString.split(".")[2].toInt()
}
