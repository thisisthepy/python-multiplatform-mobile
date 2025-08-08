package python.native.ffi


@RequiresOptIn(
    message = "This function has high call overhead. Please consider limiting its usage.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class HighOverheadNativeCall
