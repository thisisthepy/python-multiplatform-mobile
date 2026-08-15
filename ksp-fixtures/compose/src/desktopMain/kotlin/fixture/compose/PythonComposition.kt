package fixture.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.HandleTable

/**
 * **The one hand-written `@Composable` in the whole design**, and the only thing Python cannot do
 * for itself.
 *
 * ### Why exactly one, and not one per component
 *
 * The 2024 `pythonx-compose` wrote a Kotlin wrapper per widget -- 37 files, 28 of them empty --
 * and `docs/pythonx-adapter-design.md` §1 measures what that cost: `padding()` composed nothing and
 * `fill_max_size()` returned `self`, because a per-declaration wrapper is written once and then
 * never again. This is O(1) in the number of composables and stays O(1) by construction: it names no
 * composable, takes no composable-specific parameter, and knows nothing about the declaration Python
 * is about to call. Every `@Composable` in every artefact the walker binds goes through this one
 * function, because the only thing it supplies is the composer -- and there is exactly one composer,
 * not one per widget.
 *
 * ### What it actually supplies
 *
 * `$composer` is not a value, it is a *position*: `currentComposer` is an intrinsic the Compose
 * compiler plugin resolves to the composer parameter of the enclosing composable, so the only way to
 * obtain one is to be inside a composition. Everything else about calling a composable -- which
 * arguments were written, what `$default` mask that implies, what `$changed` should be -- is
 * arithmetic `pythonx` does in Python (`PythonxAdapter`'s `_bind_composable`), which is why it is
 * *not* here and why this stayed one function.
 *
 * The composer crosses as an ordinary `TypeTag.OBJECT` handle, which is `docs/ecosystem.md` §5b's
 * "the Python wrapper passes the composer as a value" and the same shape the 2024
 * `RuntimeKt.composableWrapper` used. The handle is registered here and released here, so the
 * lifetime is exactly the composition's -- `pythonx.push_composer` retains nothing and says so.
 *
 * ### What is deliberately not solved
 *
 * Recomposition. [source] is `exec`ed on every composition pass, so a Python body that is expensive
 * pays for it every frame, and a `@Composable` reached this way can never be *skipped* the way one
 * with stable parameters is. `docs/pythonx-adapter-design.md` §5.4 item 4 names this as a property to
 * measure before the shape is adopted for anything but a proof, and nothing here has measured it.
 */
@Composable
fun PythonComposition(source: String) {
    val composer = currentComposer
    val reference = HandleTable.register(composer)
    try {
        Python3.exec("import pythonx\npythonx.push_composer(${reference.raw})")
        try {
            Python3.exec(source)
        } finally {
            Python3.exec("import pythonx\npythonx.pop_composer()")
        }
    } finally {
        HandleTable.release(reference)
    }
}
