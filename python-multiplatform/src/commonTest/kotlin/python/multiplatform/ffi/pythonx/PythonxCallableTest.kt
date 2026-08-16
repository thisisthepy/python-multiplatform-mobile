package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.publishesProxyEntryPoints
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The direction the composable work left open: **a Python callable reaching a Kotlin function-typed
 * parameter.**
 *
 * `docs/pythonx-adapter-design.md` §6 states the starting position exactly -- "a Python callable can
 * already cross into Kotlin", as a `PyObject`, because `UpcallTrampoline.toKotlinObject` wraps
 * anything that is not an integer handle. What it *cannot* do is arrive as a `Function0` or a
 * `Function3`, which is what every container composable's `content` slot is after the Compose plugin
 * has lowered it. So `Column`, `Row`, `Box` and `Button` were reachable only with `content` left
 * defaulted, which for `Column` is not reachable at all -- `content` declares no default.
 *
 * ### The three questions, and which one this file answers
 *
 * | | where |
 * |---|---|
 * | does a Python callable become a Kotlin `FunctionN`, with the composer threaded through | here, on all five targets, against [ComposableShapedFragment] |
 * | does a real `androidx.compose.foundation.layout.Column` then draw its content | `:ksp-fixtures:compose`'s `ComposableRenderTest`, desktop only |
 * | does Compose give the callable back when it drops the slot | `ComposableRenderTest.aDisposedCompositionGivesEveryPythonCallableBack`, because `RememberObserver` is a Compose type |
 *
 * The split is [ComposableShapedFragment]'s: `commonTest` runs where there is no Compose runtime, no
 * `Composer` and no generated thunk class, so what is checked here is the mechanism -- which object
 * arrives, what it does when invoked, and who holds the Python reference -- and not the pixels.
 *
 * ### Lifetime is the risk, and it is asserted from both sides
 *
 * A test that only counted leaks would pass a double release, which is the failure this repository
 * has actually been bitten by (`agent-rules` §14: two wrappers decrementing one pointer corrupted
 * the heap and surfaced in an unrelated test). So [theScopeHoldsThePythonCallableAndGivesItBackOnce]
 * asserts the reference count **goes up** while the scope is open, **comes back** when it closes,
 * and **does not go below the baseline** when close is called again.
 */
class PythonxCallableTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ComposableShapedFragment))
        ComposableShapedFragment.calls.clear()
        ComposableShapedFragment.clicks.clear()
        ComposableShapedFragment.contentInvocations = 0
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /**
     * The claim, in one line of Python: `Column(content=lambda: Text('hi'))`.
     *
     * Three things have to be true for this to pass and they fail in distinguishable ways. The
     * object in `Column`'s slot 1 has to be a `Function3` -- [ComposableShapedFragment] casts it
     * unchecked, so a `PyObject` there is a `ClassCastException` naming the type it got. Invoking it
     * has to run the Python body -- `contentInvocations` counts the invocation and `calls` counts
     * what the body did, so a content that was received but never called is distinguishable from one
     * that was called and did nothing. And the body has to reach `Text` through the ordinary
     * composable path, mask and all.
     */
    @Test
    fun aPythonLambdaCrossesAsAKotlinFunctionAndRunsInsideItsContainer() = withComposer {
        Python3.exec(
            """
            from pythonx.compose.foundation.layout import Column
            from pythonx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )

        assertEquals(1, ComposableShapedFragment.contentInvocations, "the container never called its content")
        assertEquals(2, ComposableShapedFragment.calls.size, "expected the Column and the Text it contains")
        val (column, text) = ComposableShapedFragment.calls
        // `Column(content=...)` writes one of two declared slots, so bit 0 (`modifier`) is set and
        // bit 1 (`content`) is not: the mask arithmetic is unchanged by the callable arriving.
        assertEquals(1L, column[4], "\$default: modifier omitted, content supplied")
        assertEquals("hi", text[0], "the Python body did not run inside the content lambda")
    }

    /**
     * **The composer the content sees is the one its container handed it**, not the one that was
     * ambient when the lambda was created.
     *
     * A wrapper that ignored its own `$composer` argument and let `pythonx.current_composer()` keep
     * answering with the outer one would pass every test that only used a single composer -- and
     * would be wrong in exactly the case Compose creates, where a container establishes a new
     * position before invoking its content. [ComposableShapedFragment] passes a distinct
     * `InnerComposer` for that reason.
     */
    @Test
    fun theContentSeesTheComposerItsContainerHandedIt() = withComposer {
        Python3.exec(
            """
            from pythonx.compose.foundation.layout import Column
            from pythonx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )

        val text = ComposableShapedFragment.calls[1]
        assertEquals(
            ComposableShapedFragment.InnerComposer,
            text[4],
            "the content ran against the ambient composer rather than the one it was passed",
        )
    }

    /**
     * Who holds the Python reference, and that they give it back exactly once.
     *
     * `sys.getrefcount` is the measurement: the callable is held in a Python global, so the count is
     * stable and the only thing that can move it is the Kotlin side taking and dropping a reference
     * of its own. The scope is the holder -- not `Column`, which has no way to be told the slot was
     * dropped, and not the composition pass, which ends long before Compose stops calling the
     * content.
     */
    @Test
    fun theScopeHoldsThePythonCallableAndGivesItBackOnce() = withAdapter {
        val scope = PythonCallables.newScope()
        Python3.exec(
            """
            import sys
            import pythonx
            from pythonx.compose.material3 import Text

            def _px_body():
                Text('hi')

            _px_base = sys.getrefcount(_px_body)
            """.trimIndent(),
        )
        val base = eval("_px_base").toInt()

        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    from pythonx.compose.foundation.layout import Column
                    Column(content=_px_body)
                    _px_held = sys.getrefcount(_px_body)
                    """.trimIndent(),
                )
            } finally {
                popComposer()
            }
        }
        val held = eval("_px_held").toInt()
        assertTrue(held > base, "nothing on the Kotlin side took a reference: $base -> $held")
        assertEquals(1, scope.liveCount, "the scope did not record the callable it created")

        assertEquals(1, scope.close(), "closing the scope released nothing")
        Python3.exec("_px_released = sys.getrefcount(_px_body)")
        assertEquals(base, eval("_px_released").toInt(), "the scope did not give the reference back")

        // The half a leak test does not cover. A second close must be a no-op, not a second decref:
        // one reference over-released here is a use-after-free somewhere unrelated later.
        assertEquals(0, scope.close(), "closing twice released something twice")
        Python3.exec("_px_twice = sys.getrefcount(_px_body)")
        assertEquals(base, eval("_px_twice").toInt(), "a second close dropped the reference again")
    }

    /**
     * A callable with nowhere to live is refused at the boundary, with a message that says so.
     *
     * The alternative -- rooting it in `HandleTable` and hoping something releases it -- is what
     * `HandleTable`'s own KDoc calls a leak by construction, and there is nothing on the Python side
     * to hang a finaliser off because the handle reaches Kotlin as a bare slot value and is never
     * given back to Python at all.
     */
    @Test
    fun aCallableWithNoScopeIsRefusedRatherThanLeaked() = withAdapter {
        // Deliberately no `PythonCallables.withScope`: a composer is in scope, so everything else
        // about the call is well formed and the scope is the only thing missing.
        pushComposer()
        val refusal = try {
            assertFails {
                Python3.exec(
                    """
                    from pythonx.compose.foundation.layout import Column
                    from pythonx.compose.material3 import Text
                    Column(content=lambda: Text('hi'))
                    """.trimIndent(),
                )
            }
        } finally {
            popComposer()
        }
        assertTrue(
            refusal.message?.contains("no pythonx callable scope") == true,
            "expected the refusal to name the missing scope, got: ${refusal.message}",
        )
        assertEquals(0, ComposableShapedFragment.contentInvocations)
    }

    /**
     * A `FunctionN` invoked after its scope closed **refuses**; it does not call through a released
     * `PyObject`.
     *
     * This is the shape the plain (non-lowered) case makes reachable: `Button`'s `onClick` is kept by
     * Kotlin and fired later, by which time the composition that created it may be gone. Reading a
     * freed `PyObject` would not raise -- it would decrement whatever now occupies that memory, which
     * is the failure mode `agent-rules` §14 describes and the reason this is a check and not a
     * comment.
     */
    @Test
    fun aFunctionInvokedAfterItsScopeClosedRefuses() = withAdapter {
        val scope = PythonCallables.newScope()
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    import pythonx
                    from pythonx.compose.material3 import Button
                    _px_clicked = []
                    Button(on_click=lambda: _px_clicked.append(1))
                    """.trimIndent(),
                )
            } finally {
                popComposer()
            }
        }
        val onClick = ComposableShapedFragment.clicks.single()
        onClick()
        assertEquals("1", eval("len(_px_clicked)"), "the click did not reach Python while the scope was open")

        scope.close()
        val refusal = assertFails { onClick() }
        assertTrue(
            refusal.message?.contains("released") == true,
            "expected a refusal naming the released scope, got: ${refusal.message}",
        )
        assertEquals("1", eval("len(_px_clicked)"), "a released callable still ran")
    }

    /**
     * What a crossing **allocates**, which is the part of its cost this can state without a clock.
     *
     * One wrapper per crossing, held until the scope closes, and nothing is interned -- so a body
     * that writes `content=lambda: ...` inside a loop, or a composition that recomposes *n* times,
     * costs *n* Python references and *n* `HandleTable` roots that are all released together at the
     * end rather than as they stop being reachable. That is a real and unbounded property of the
     * design as it stands, and it is asserted here so that it is a recorded number rather than a
     * surprise.
     *
     * **No wall-clock figure is claimed.** `agent-rules` §11 forbids fixing one on a loaded machine,
     * and the run that produced this had a load average of 3.5--8 on eight cores. Nanoseconds per
     * crossing are unmeasured.
     */
    @Test
    fun everyCrossingBuildsItsOwnWrapperAndTheScopeHoldsThemAll() = withAdapter {
        val scope = PythonCallables.newScope()
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    import pythonx
                    from pythonx.compose.foundation.layout import Column
                    from pythonx.compose.material3 import Text
                    for _ in range(REPS):
                        Column(content=lambda: Text('hi'))
                    """.trimIndent().replace("REPS", REPETITIONS.toString()),
                )
            } finally {
                popComposer()
            }
            assertEquals(REPETITIONS, ComposableShapedFragment.contentInvocations)
            assertEquals(REPETITIONS, scope.liveCount, "one wrapper per crossing, none reused")
        }
        scope.close()
    }

    private fun pushComposer() = Python3.exec(
        """
        import pythonx
        from pythonx.compose.runtime import stub_composer
        _px_composer = stub_composer()
        pythonx.push_composer(_px_composer)
        """.trimIndent(),
    )

    private fun popComposer() = Python3.exec("import pythonx\npythonx.pop_composer()\ndel _px_composer")

    /** [withAdapter], a composer, and a scope -- the three things a real composition supplies. */
    private inline fun withComposer(crossinline block: () -> Unit) = withAdapter {
        val scope = PythonCallables.newScope()
        try {
            PythonCallables.withScope(scope) {
                pushComposer()
                try {
                    block()
                } finally {
                    popComposer()
                }
            }
        } finally {
            scope.close()
        }
    }

    private inline fun withAdapter(block: () -> Unit) = PythonTestFixture.withInterpreter {
        assertTrue(
            bindUpcallOrNull("androidx.compose.runtime.stubComposer"),
            "the fixture table is not installed",
        )
        if (!publishesProxyEntryPoints) return@withInterpreter
        PythonxAdapter.install()
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()

    private companion object {
        const val REPETITIONS = 200
    }
}
