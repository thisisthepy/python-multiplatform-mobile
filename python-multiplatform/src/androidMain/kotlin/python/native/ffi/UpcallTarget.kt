package python.native.ffi

object UpcallTarget {
    @JvmStatic
    fun upcallPrimitive(x: Long): Long {
        return x
    }

    @JvmStatic
    fun upcallString(s: String): Long {
        return s.length.toLong()
    }
}
