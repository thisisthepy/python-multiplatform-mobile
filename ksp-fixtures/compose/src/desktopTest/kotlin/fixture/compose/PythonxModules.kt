package fixture.compose

/**
 * The `pythonx` -> Kotlin module map these fixtures install.
 *
 * `PythonxAdapter` used to seed `pythonx.compose` -> `androidx.compose` itself, which made the
 * binder know a UI library by name. It does not any more: the map is the Python package's
 * (`pythonx-compose` ships `pythonx-map.toml`) and the embedder installs it. A fixture is an
 * embedder, so it supplies its own -- and this file is what a consumer's manifest stands in for.
 *
 * `pythonx.compose.layout` and `pythonx.compose.foundation.layout` both appear because both are
 * real: the generated `.pyi` stubs use the short spelling the manifest prescribes, and these tests
 * were written against the long one. Mapping both keeps the two readable side by side while the
 * spelling settles.
 */
internal val PYTHONX_MODULES: Map<String, String> = mapOf(
    "pythonx.compose" to "androidx.compose",
    "pythonx.compose.ui" to "androidx.compose.ui",
    "pythonx.compose.ui.unit" to "androidx.compose.ui.unit",
    "pythonx.compose.ui.text" to "androidx.compose.ui.text",
    "pythonx.compose.ui.graphics" to "androidx.compose.ui.graphics",
    "pythonx.compose.foundation.layout" to "androidx.compose.foundation.layout",
    "pythonx.compose.layout" to "androidx.compose.foundation.layout",
    "pythonx.compose.material3" to "androidx.compose.material3",
    "pythonx.compose.material" to "androidx.compose.material",
    "pythonx.compose.runtime" to "androidx.compose.runtime",
    "pythonx.kotlin" to "kotlin",
)

/** §4.4's one value class that may be written as its raw primitive. See `PYTHONX_MODULES`. */
internal val PYTHONX_RAW_VALUE_CLASSES: Set<String> = setOf("androidx.compose.ui.unit.Dp")
