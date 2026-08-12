package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.TypeTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generated Python proxy module, pinned as *text*.
 *
 * This is in `commonTest` and touches no interpreter, which is the payoff of making
 * [PythonProxySource.render] a pure function of the table: the thing that decides what Python sees
 * is checkable on all five targets, and `PythonProxyInstallTest` (desktopTest) is then only
 * responsible for showing that the text does what it says when a real CPython runs it.
 *
 * The assertions are about the two decisions that are easy to get silently wrong -- whether a
 * suspending entry gets an `async def` with the awaitable check, and whether a one-argument call
 * gets a tuple rather than a bare value -- rather than about exact formatting, which would make
 * every comment edit a test failure.
 */
class PythonProxySourceTest {

    private fun entry(
        name: String,
        arity: Int = 0,
        kind: CallableKind = CallableKind.FUNCTION,
        isSuspend: Boolean = false,
    ) = ExposedCallable(
        name = name,
        arity = arity,
        paramTypes = List(arity) { TypeTag.INT },
        returnType = TypeTag.INT,
        kind = kind,
        isSuspend = isSuspend,
    ) { 0L }

    @Test
    fun aSuspendingEntryBecomesAnAsyncDefThatAwaitsOnlyWhatIsAwaitable() {
        val source = PythonProxySource.render(listOf(entry("fixture.library.fetchLater", arity = 1, isSuspend = true)))

        assertContains(source, "async def _pm_f_0(a0):")
        assertContains(source, "if hasattr(_pm_r, '__await__'):")
        assertContains(source, "return await _pm_r")
        // The whole point of the conditional await: an already-complete call must reach the caller
        // as its value, not wrapped in anything. `docs/upcall-async-design.md` §5.
        assertContains(source, "return _pm_r")
    }

    @Test
    fun aNonSuspendingEntryStaysAPlainDefWithNoAwaitMachineryAtAll() {
        val source = PythonProxySource.render(listOf(entry("fixture.library.greet", arity = 1)))

        assertContains(source, "def _pm_f_0(a0):")
        assertFalse(source.contains("async def _pm_f_0"), "a synchronous entry must not become a coroutine function")
        assertFalse(source.contains("__await__"), "nothing about a synchronous entry involves awaiting")
    }

    @Test
    fun aSingleArgumentCallIsPassedAsAOneTupleAndNotAsABareValue() {
        // `(a0)` is `a0`. The trampoline would then call PyTuple_Size on whatever was passed and
        // reject it -- or worse, succeed on something that happens to have a size.
        val source = PythonProxySource.render(listOf(entry("p.one", arity = 1)))
        assertContains(source, "_pm_invoke(_pm_h_0, (a0,))")

        val zero = PythonProxySource.render(listOf(entry("p.none")))
        assertContains(zero, "_pm_invoke(_pm_h_0, ())")

        val two = PythonProxySource.render(listOf(entry("p.two", arity = 2)))
        assertContains(two, "_pm_invoke(_pm_h_0, (a0, a1))")
    }

    @Test
    fun anEntryIsPublishedUnderItsKotlinPackageSoTheDottedNameIsImportable() {
        val source = PythonProxySource.render(listOf(entry("fixture.library.greet")))

        assertContains(source, "_pm_h_0 = _pm_bind('fixture.library.greet')")
        assertContains(source, "setattr(_pm_module('fixture.library'), 'greet', _pm_f_0)")
        assertContains(source, "_pm_f_0.__name__ = 'greet'")
    }

    @Test
    fun aNameWithNoPackageOfItsOwnLandsInTheRootModule() {
        // Kotlin's default package produces such names and they have nowhere else to go.
        val source = PythonProxySource.render(listOf(entry("standalone")))
        assertContains(source, "setattr(_pm_module('kotlin'), 'standalone', _pm_f_0)")

        val custom = PythonProxySource.render(listOf(entry("standalone")), rootModule = "myapp")
        assertContains(custom, "setattr(_pm_module('myapp'), 'standalone', _pm_f_0)")
    }

    @Test
    fun kindsThatNeedAReceiverOrAnAttributeAreLeftToTheProxyTypeAndNotRenderedHere() {
        // Rendering a METHOD as a module function would put the receiver handle in the caller's
        // hands as a bare int, and rendering a STATIC_GETTER as `libraryVersion()` would make the
        // Python surface disagree with the Kotlin declaration. Both belong to §7's proxy type.
        val source = PythonProxySource.render(
            listOf(
                entry("p.Thing.method", arity = 1, kind = CallableKind.METHOD),
                entry("p.Thing.prop", kind = CallableKind.GETTER),
                entry("p.version", kind = CallableKind.STATIC_GETTER),
                entry("p.version=", arity = 1, kind = CallableKind.STATIC_SETTER),
                entry("p.Thing", kind = CallableKind.CONSTRUCTOR),
                entry("p.plain"),
            ),
        )

        assertEquals(1, Regex("^_pm_h_\\d+ = _pm_bind", RegexOption.MULTILINE).findAll(source).count())
        assertContains(source, "_pm_bind('p.plain')")
        assertFalse(source.contains("p.Thing.method"), "a method must not be rendered as a module function")
        assertFalse(source.contains("p.version"), "a property must not be rendered as a callable")
    }

    @Test
    fun anEmptyTableRendersSomethingRunnableRatherThanNothing() {
        val source = PythonProxySource.render(emptyList())
        assertContains(source, "def _pm_settle(")
        assertContains(source, "# no CallableKind.FUNCTION entries to proxy")
        assertFalse(source.contains("_pm_bind('"), "there is nothing to bind")
    }

    @Test
    fun theSupportHalfCarriesTheGuardThatMakesADeliveryToACancelledFutureSafe() {
        // `AsyncUpcall` schedules this function rather than `Future.set_result` itself, and the
        // `done()` check inside it is the only part of that path that is a guarantee -- the check
        // Kotlin makes before scheduling is separated from the callback by a thread hand-off.
        assertContains(PythonProxySource.support, "def _pm_settle(_fut, _ok, _payload):")
        assertContains(PythonProxySource.support, "if _fut.done():")
        assertContains(PythonProxySource.support, "return")
        assertTrue(
            PythonProxySource.support.indexOf("if _fut.done():") <
                PythonProxySource.support.indexOf("_fut.set_result(_payload)"),
            "the guard has to come before the set, or it is not a guard",
        )
    }

    @Test
    fun theGeneratedModuleRefusesToInstallWithoutTheRawEntryPointsBound() {
        // Defining proxies over an unbound `_pm_invoke` would turn one missing bootstrap step into
        // a NameError at each individual call site, arbitrarily later.
        val source = PythonProxySource.render(listOf(entry("p.plain")))
        assertContains(source, "if '_pm_resolve' not in globals() or '_pm_invoke' not in globals():")
        assertTrue(
            source.indexOf("not in globals()") < source.indexOf("_pm_bind('p.plain')"),
            "the guard has to run before the first bind",
        )
    }
}
