package dalvik.annotation.optimization

/**
 * Google advises @CriticalNative be used via RegisterNatives rather than name-based linking before Android 12,
 * and the public SDK only exposes the annotation from API 34.
 * We test on API 34 here, so the API 26-33 range is UNVERIFIED.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CriticalNative
