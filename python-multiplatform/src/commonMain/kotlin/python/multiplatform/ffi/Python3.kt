package python.multiplatform.ffi


expect object Python3 {
    var isInitialized: Boolean
        private set
    fun initialize()
    fun finalize()

    //val builtins: Builtins
}
