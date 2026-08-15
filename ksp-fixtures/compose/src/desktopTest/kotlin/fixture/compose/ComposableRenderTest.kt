package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.UpcallStub
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion, end to end: **`Text('hi')` written in Python draws pixels through
 * `androidx.compose.material3.Text`.**
 *
 * Every layer is the real one. `material3-desktop.jar` is walked by `ArtifactScanner`; the composable
 * it finds is bound with its `$composer`, `$changed` and `$default` slots exposed; its call site is a
 * `.class` `ComposableThunks` emitted at build time, because a Kotlin file facade has no Kotlin name;
 * `pythonx` computes the `$default` mask from which arguments the Python call wrote; the composer
 * comes from [PythonComposition], the one hand-written `@Composable`; and `ImageComposeScene` runs a
 * real composition and rasterises it.
 *
 * ### How "it drew" is decided
 *
 * By counting pixels that are not the background. Two scenes are rendered from the same code path
 * -- one whose Python body calls `Text`, one whose body does nothing -- and the assertion is that the
 * first has ink and the second has none. That is deliberately not an image comparison against a
 * checked-in reference: this test has to survive a Compose version bump changing a font metric,
 * while still failing if nothing composes.
 *
 * A second scene renders `Text('hi')` and `Text('hi hi hi hi')` and asserts the second has strictly
 * more ink, which is what stops "any non-empty frame" from passing -- the text that was written has
 * to be the text that was drawn.
 */
class ComposableRenderTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        // No `FunctionTable`: this module declares nothing KSP binds (its one public declaration
        // is the `@Composable` entry point, which `BindingPolicy` declines), so the walked artefacts
        // are the whole table.
        UpcallTable.install(ArtifactTable.fragments)
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${UpcallStub.invokeWithArgsStubAddr}
            )
            _pm_release = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)(${UpcallStub.releaseObjectStubAddr})
            """.trimIndent(),
        )
        PythonxAdapter.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** The claim, in one line of Python. */
    @Test
    fun pythonDrawsARealMaterial3Text() {
        val drawn = inkOf("from pythonx.compose.material3 import Text\nText('hi')")
        val blank = inkOf("pass")

        println("compose render: Text('hi') -> $drawn non-background pixels, empty body -> $blank")
        assertEquals(0, blank, "an empty composition must draw nothing, or the measurement is not measuring")
        assertTrue(drawn > 0, "Text('hi') drew nothing")
    }

    /**
     * The argument crossed, and not merely *an* argument: more text is more ink.
     *
     * Without this, a test that only asserted "something was drawn" would pass if the string never
     * left Python and Compose rendered a default.
     */
    @Test
    fun theStringPythonWroteIsTheStringComposeDrew() {
        val short = inkOf("from pythonx.compose.material3 import Text\nText('hi')")
        val long = inkOf("from pythonx.compose.material3 import Text\nText('hi hi hi hi hi')")

        println("compose render: 'hi' -> $short px, 'hi hi hi hi hi' -> $long px")
        assertTrue(long > short, "expected more ink for more text: $short vs $long")
    }

    /**
     * That the mask is *right* and not merely present, argued from what a wrong one would do.
     *
     * `Text` declares 15 or 16 defaulted parameters and `Text('hi')` writes none of them. Slot 16
     * (`style`) is a `TextStyle` and arrives as `null`; the only thing that stops that `null` from
     * reaching `Text`'s body is `$default` bit 16 telling the callee to assign
     * `LocalTextStyle.current` over it first. So a mask that was shifted, truncated or zero would not
     * draw the wrong thing -- it would raise inside Compose. The rendering tests above are therefore
     * already the assertion; this one states the shape the table has, so that a regression that
     * *stops* binding composables fails here with a readable message rather than as a blank frame.
     */
    @Test
    fun theWalkedTableCarriesTextWithItsSyntheticSlots() {
        // Asserted inside Python rather than marshalled back out, the way
        // `WalkedArtifactPythonImportTest` does it: `Python3.exec` raises a Kotlin `PyException`
        // carrying the Python message, so a failure arrives intact and the check costs no boundary
        // crossing of its own that could be the thing that actually worked.
        Python3.exec(
            """
            import pythonx
            # By the *base* name, not the fully-qualified one: material3 declares four `Text`s, so
            # the walker gave each an overload suffix and `pythonx._TABLE` has no bare
            # `androidx.compose.material3.Text` key at all. `from ... import Text` still works --
            # that is `_Overloads` dispatching -- which is what the rendering tests above use.
            _decls = pythonx._BY_PACKAGE['androidx.compose.material3']['Text']
            _decl = [_d for _d in _decls if _d.param_type_names[0] == 'kotlin.String'][0]
            assert len(_decls) >= 2, 'expected Text to be an overload set: ' + repr([_d.leaf for _d in _decls])
            assert tuple(_decl.param_names[-4:]) == ('${'$'}composer', '${'$'}changed', '${'$'}changed1', '${'$'}default'), \
                repr(_decl.param_names)
            assert _decl.arity == _decl.declared_arity() + 4, (_decl.arity, _decl.declared_arity())
            assert not any(_decl.param_has_default[_decl.declared_arity():]), 'a synthetic slot is never omittable'
            assert _decl.param_has_default[0] is False, 'text has no default'
            assert sum(1 for _f in _decl.param_has_default if _f) >= 14, _decl.param_has_default
            """.trimIndent(),
        )
    }

    /**
     * How many pixels of a 200x60 scene differ from the background after running [body] inside a
     * composition.
     */
    private fun inkOf(body: String): Int = pixelsOf(body).count { it != BACKGROUND }

    private fun pixelsOf(body: String): IntArray {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val image: Image = scene.render()
            val bitmap = Bitmap.makeFromImage(image)
            val pixels = IntArray(bitmap.width * bitmap.height)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    pixels[y * bitmap.width + x] = bitmap.getColor(x, y)
                }
            }
            return pixels
        } finally {
            scene.close()
        }
    }

    private companion object {
        /** `ImageComposeScene` clears to transparent black. */
        const val BACKGROUND = 0
    }
}
