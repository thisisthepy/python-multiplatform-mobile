package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
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
 * **A Python callback, driven by a real event** -- the third wall `ebe3365f` left standing.
 *
 * ### What was missing, and why it mattered
 *
 * Every render proof in this module so far is a *static rasterisation*: build a scene, call
 * `render()`, count pixels. That proves a composable was reached and drew, and it can say nothing at
 * all about a slot whose whole purpose is to be **called back** -- `Checkbox.onCheckedChange`,
 * `Slider.onValueChange`, `TextField.onValueChange`. `ComposableBindingTest` proves those slots are
 * *described* (`kotlin.Function1(kotlin.Boolean)->kotlin.Unit`) and `FunctionSlotBindingTest` proves
 * a Python callable can be *put* in one, and nothing anywhere proved one is ever *invoked*.
 *
 * ### What delivers the event
 *
 * `ImageComposeScene.sendPointerEvent`, which is on the same class the rasterisation tests already
 * use -- so this needs no new harness, no window and no display. A press and a release at the
 * component's centre is what Compose turns into a click, and `Checkbox` is the smallest bound
 * declaration that reacts to one with a value rather than with a `Unit`: the argument it hands back
 * is the thing to assert, because a callback invoked with the wrong value and a callback invoked at
 * all are different claims.
 *
 * ### Why the redraw is a second scene
 *
 * `Checkbox` is stateless: what it draws comes from its `checked` argument, which is Python's to
 * hold. Clicking it invalidates Compose's own interaction state, not `PythonComposition`'s -- so the
 * scene that received the click will not re-run the Python body, and asserting on its second frame
 * would be asserting on nothing. A **fresh** scene over the same body reads the Python variable the
 * callback wrote, which is the honest way round: the pixels prove what Python holds now, and the
 * `_events` list proves how it got there.
 */
class CallbackDrivenRenderTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
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

    /**
     * **The claim.** A `lambda v: ...` written in Python, in `Checkbox`'s `onCheckedChange` slot,
     * invoked by a pointer press and release, with `True` -- and the state it wrote showing up as
     * different pixels the next time the same body composes.
     *
     * Four assertions, and each one fails for a different reason:
     *
     * | assertion | what it rules out |
     * |---|---|
     * | the unclicked control draws something | the checkbox never composed, so nothing was there to click |
     * | `_events == [True]` after the click | the callback was never invoked, or was invoked with the wrong value -- a `Function1` handed a `Boolean` it forwards as `None` fails here |
     * | the two renders differ | Python holds the new state but nothing can see it |
     * | the untouched control does **not** differ | the difference came from the scene being built twice rather than from the click |
     */
    @Test
    fun clickingACheckboxInvokesThePythonCallbackAndTheStateItWroteRedraws() {
        Python3.exec(
            """
            _cb_events = []
            _cb_state = [False]

            def _cb_toggle(value):
                _cb_events.append(value)
                _cb_state[0] = value
            """.trimIndent(),
        )
        val unchecked = pixelsOf(CHECKBOX)
        assertTrue(inkOf(unchecked) > 0, "the Checkbox never composed, so there was nothing to click")

        // The control first, and before the click: composing the same body twice must not, on its
        // own, change a single pixel.
        assertEquals(0, differing(unchecked, pixelsOf(CHECKBOX)), "the render is not deterministic")

        clickCentre(CHECKBOX)
        // Asserted in Python, because the value to check is a Python one: the boundary carries no
        // reader for an arbitrary expression, and a Kotlin-side copy of `_cb_events` would be a
        // second thing that can disagree with the list the callback actually appended to.
        Python3.exec("assert _cb_events == [True], repr(_cb_events)")
        val checked = pixelsOf(CHECKBOX)
        val moved = differing(unchecked, checked)
        println(
            "compose input: Checkbox click -> on_checked_change(True), " +
                "ink ${inkOf(unchecked)} -> ${inkOf(checked)} px, $moved pixels changed",
        )
        assertTrue(
            moved > 100,
            "only $moved pixels changed after the click, so the state the callback wrote never reached Compose",
        )
    }

    /**
     * The negative control, stated apart so that the test above cannot pass by accident: a press and
     * a release **outside** the component invoke nothing. Without this, a callback invoked by
     * composition rather than by the click would satisfy every assertion above.
     */
    @Test
    fun aClickThatMissesTheComponentInvokesNothing() {
        Python3.exec(
            """
            _cb_events = []
            _cb_state = [False]

            def _cb_toggle(value):
                _cb_events.append(value)
                _cb_state[0] = value
            """.trimIndent(),
        )
        clickAt(CHECKBOX, Offset(SCENE - 2f, SCENE - 2f))
        Python3.exec("assert _cb_events == [], 'a click that missed the checkbox invoked its callback: ' + repr(_cb_events)")
    }

    /**
     * The same event, one declaration further on: `Switch` is a different composable with the same
     * `(Boolean) -> Unit` shape, so a mechanism that only worked for `Checkbox` -- a lucky slot
     * index, a `$default` mask that happened to line up -- fails here.
     */
    @Test
    fun theSameCallbackShapeIsDrivenOnASecondDeclaration() {
        Python3.exec(
            """
            _cb_events = []
            _cb_state = [False]

            def _cb_toggle(value):
                _cb_events.append(value)
                _cb_state[0] = value
            """.trimIndent(),
        )
        val before = pixelsOf(SWITCH)
        assertTrue(inkOf(before) > 0, "the Switch never composed")
        clickCentre(SWITCH)
        Python3.exec("assert _cb_events == [True], repr(_cb_events)")
        val after = pixelsOf(SWITCH)
        val moved = differing(before, after)
        println(
            "compose input: Switch click -> on_checked_change(True), " +
                "ink ${inkOf(before)} -> ${inkOf(after)} px, $moved pixels changed",
        )
        // Deliberately not an ink comparison: the thumb slides from one end of the track to the
        // other, which is a large change that leaves the total almost exactly where it was.
        assertTrue(moved > 100, "only $moved pixels changed, so the switch drew the same state twice")
    }

    private fun clickCentre(body: String) = clickAt(body, Offset(COMPONENT_CENTRE, COMPONENT_CENTRE))

    /** Builds the scene, lays it out with one render, delivers a press and a release at [at], and
     * lets the scene go. The frame after the click is deliberately not measured; see this class's
     * KDoc for why the redraw has to be a fresh scene. */
    private fun clickAt(body: String, at: Offset) {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            scene.render()
            // Move first: a pointer that has never been anywhere has no position for the press to be
            // *in*, and Compose's tap detector wants an enter before a press.
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            scene.render()
        } finally {
            scene.close()
        }
    }

    /**
     * Every pixel of one render of [body], so that two renders can be compared **position by
     * position** rather than by how much ink each contains.
     *
     * The difference matters for `Switch` and would have made this test a false negative: flipping it
     * slides a thumb from one end of the track to the other and recolours both, which moves hundreds
     * of pixels while changing the *total* ink by four. Counting ink would have called that "no
     * change"; counting disagreements calls it what it is.
     */
    private fun pixelsOf(body: String): IntArray {
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(SCENE * SCENE) { bitmap.getColor(it % SCENE, it / SCENE) }
        } finally {
            scene.close()
        }
    }

    private fun inkOf(pixels: IntArray): Int = pixels.count { it != BACKGROUND }

    private fun differing(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    private companion object {
        /** `ImageComposeScene` clears to transparent black. */
        const val BACKGROUND = 0

        /** Wide enough for material3's 48dp minimum interactive size at density 1, with room around
         * it for [aClickThatMissesTheComponentInvokesNothing] to miss into. */
        const val SCENE = 80

        /** The component's centre, not the scene's. Content is placed at the scene's top-left, so a
         * 48dp interactive box occupies `(0,0)..(48,48)` and its middle is 24 -- measured, not
         * assumed: the ink a `Checkbox` leaves in this scene spans `(14,14)..(33,33)`, which is the
         * 20dp box centred on exactly that point. Clicking the *scene's* centre lands outside it and
         * invokes nothing, which is what [aClickThatMissesTheComponentInvokesNothing] now asserts
         * deliberately. */
        const val COMPONENT_CENTRE = 24f

        /** `checked` is read out of Python on every composition, which is what makes a fresh scene
         * show what the callback wrote. */
        val CHECKBOX = """
            from pythonx.compose.material3 import Checkbox
            Checkbox(_cb_state[0], on_checked_change=_cb_toggle)
        """.trimIndent()

        val SWITCH = """
            from pythonx.compose.material3 import Switch
            Switch(_cb_state[0], on_checked_change=_cb_toggle)
        """.trimIndent()
    }
}
