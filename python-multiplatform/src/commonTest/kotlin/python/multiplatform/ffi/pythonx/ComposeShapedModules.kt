package python.multiplatform.ffi.pythonx

/**
 * The module map these tests install, standing where `pythonx-compose`'s `pythonx-map.toml` stands
 * for a real consumer.
 *
 * It lives in the test source on purpose. [PythonxAdapter] used to carry
 * `register_package('pythonx.compose', 'androidx.compose')` in `commonMain`, which made the binder
 * know a UI library by name -- and it could not express what the map actually needs: an exact
 * rename, `pythonx.compose.layout` for `androidx.compose.foundation.layout`, with `foundation.`
 * dropped. A prefix substitution has no way to remove a middle segment, so the runtime and the
 * generated `.pyi` stubs disagreed about the module name and an IDE could complete a name the
 * interpreter would refuse.
 *
 * The names here are the ones `ComposeShapedFragment` declares, so this file and that one move
 * together.
 */
internal val COMPOSE_SHAPED_MODULES: Map<String, String> = mapOf(
    "pythonx.compose" to "androidx.compose",
    "pythonx.compose.ui" to "androidx.compose.ui",
    "pythonx.compose.ui.unit" to "androidx.compose.ui.unit",
    "pythonx.compose.foundation.layout" to "androidx.compose.foundation.layout",
    "pythonx.compose.layout" to "androidx.compose.foundation.layout",
    "pythonx.compose.material3" to "androidx.compose.material3",
    "pythonx.compose.runtime" to "androidx.compose.runtime",
    "pythonx.kotlin" to "kotlin",
)

/**
 * The value classes these tests may write as a raw primitive, standing where the same map's
 * `[value-classes]` section stands for a real consumer.
 *
 * `Dp`'s public constructor is the identity on the float it wraps, so `padding(16)` and
 * `padding(Dp(16f))` mean the same thing. `TextUnit`, `Color` and every other `packedValue` class
 * are deliberately absent: a raw number decodes as something else through each of them. Which side
 * of that line a class falls on is a fact about the library that declares it, which is why the
 * binder no longer carries this set.
 */
internal val COMPOSE_SHAPED_RAW_VALUE_CLASSES: Set<String> = setOf("androidx.compose.ui.unit.Dp")
