package fixture.artifact

/**
 * The `pythonx` -> Kotlin module map these fixtures install.
 *
 * `PythonxAdapter` no longer seeds any mapping of its own -- the binder may not know a library by
 * name -- so an embedder supplies one. This file is what a consumer's `pythonx-map.toml` stands in
 * for, and `pythonx-map.toml` in this same directory is the stub generator's copy of the same
 * facts. Both exist because the runtime and the generator read the map at different times; they
 * name the same packages.
 */
internal val PYTHONX_MODULES: Map<String, String> = mapOf(
    "pythonx.compose" to "androidx.compose",
    "pythonx.compose.ui" to "androidx.compose.ui",
    "pythonx.compose.ui.unit" to "androidx.compose.ui.unit",
    "pythonx.compose.foundation.layout" to "androidx.compose.foundation.layout",
    "pythonx.compose.layout" to "androidx.compose.foundation.layout",
    "pythonx.kotlin" to "kotlin",
)

/** §4.4's one value class that may be written as its raw primitive. */
internal val PYTHONX_RAW_VALUE_CLASSES: Set<String> = setOf("androidx.compose.ui.unit.Dp")
