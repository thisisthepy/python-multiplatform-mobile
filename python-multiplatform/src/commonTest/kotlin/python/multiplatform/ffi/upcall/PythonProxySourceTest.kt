package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.ReflectedClass
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
    fun kindsThatNeedAReceiverOrAnAttributeAreLeftUnrenderedWithNoClassDescriptor() {
        // Rendering a METHOD as a module function would put the receiver handle in the caller's
        // hands as a bare int, and rendering a STATIC_GETTER as `libraryVersion()` would make the
        // Python surface disagree with the Kotlin declaration. A METHOD/GETTER/SETTER only gets a
        // Python-visible surface when its owning ReflectedClass is passed too (see the `classes`
        // tests below); STATIC_GETTER/STATIC_SETTER have no rendering path at all yet.
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
        assertFalse(source.contains("class "), "no ReflectedClass was passed, so no class can be rendered")
    }

    // ------------------------------------------------------------------------------- classes

    @Test
    fun aConstructorAndAnInstanceMethodBecomeAPythonClassThatDispatchesThroughTheHandle() {
        val ctor = entry("fixture.library.Counter.<init>", arity = 1, kind = CallableKind.CONSTRUCTOR)
        val increment = entry("fixture.library.Counter.increment", arity = 1, kind = CallableKind.METHOD)
        val cls = ReflectedClass(
            name = "fixture.library.Counter",
            memberNames = listOf(ctor.name, increment.name),
        )

        val source = PythonProxySource.render(listOf(ctor, increment), listOf(cls))

        assertContains(source, "class Counter:")
        assertContains(source, "def __init__(self, a0):")
        assertContains(source, "self._pm_handle = _pm_invoke(")
        assertContains(source, "def increment(self, a0):")
        assertContains(source, "return _pm_invoke(")
        assertContains(source, "(self._pm_handle, a0)")
        assertContains(source, "setattr(_pm_module('fixture.library'), 'Counter', Counter)")
    }

    @Test
    fun aPropertyBecomesAPythonPropertyRatherThanAMethod() {
        val getter = entry("fixture.library.Counter.value", kind = CallableKind.GETTER)
        val setter = entry("fixture.library.Counter.value=", arity = 1, kind = CallableKind.SETTER)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(getter.name, setter.name))

        val source = PythonProxySource.render(listOf(getter, setter), listOf(cls))

        assertContains(source, "@property")
        assertContains(source, "def value(self):")
        assertContains(source, "(self._pm_handle,)")
        assertContains(source, "@value.setter")
        assertContains(source, "def value(self, a0):")
        assertFalse(source.contains("value()"), "a property must not be called like a method")
    }

    @Test
    fun aReadOnlyPropertyGetsNoSetter() {
        val getter = entry("fixture.library.Counter.value", kind = CallableKind.GETTER)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(getter.name))

        val source = PythonProxySource.render(listOf(getter), listOf(cls))

        assertContains(source, "@property")
        assertFalse(source.contains(".setter"), "no SETTER entry means no setter to render")
    }

    @Test
    fun aSuspendingInstanceMethodBecomesAnAwaitableTheSameWayASuspendingFunctionDoes() {
        val method = entry("fixture.library.Worker.fetch", arity = 1, kind = CallableKind.METHOD, isSuspend = true)
        val cls = ReflectedClass(name = "fixture.library.Worker", memberNames = listOf(method.name))

        val source = PythonProxySource.render(listOf(method), listOf(cls))

        assertContains(source, "async def fetch(self, a0):")
        assertContains(source, "if hasattr(_pm_r, '__await__'):")
        assertContains(source, "return await _pm_r")
        assertFalse(source.contains("def fetch(self, a0):\n        return _pm_invoke"), "a suspending method must not be a plain def")
    }

    @Test
    fun aClassWithNoPackageOfItsOwnLandsInTheRootModule() {
        val ctor = entry("Standalone.<init>", kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "Standalone", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(ctor), listOf(cls))
        assertContains(source, "setattr(_pm_module('kotlin'), 'Standalone', Standalone)")
    }

    @Test
    fun functionsAndClassesRenderTogetherWithoutHandleNameCollisions() {
        val fn = entry("fixture.library.greet", arity = 1)
        val ctor = entry("fixture.library.Counter.<init>", arity = 1, kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(fn, ctor), listOf(cls))

        val handleNames = Regex("^(_pm_h_\\d+) = _pm_bind", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(handleNames.distinct(), handleNames, "every bound handle name must be unique")
        assertEquals(2, handleNames.size)
    }

    @Test
    fun anEmptyTableRendersSomethingRunnableRatherThanNothing() {
        val source = PythonProxySource.render(emptyList())
        assertContains(source, "def _pm_settle(")
        assertContains(source, "# no CallableKind.FUNCTION entries or proxy classes to render")
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
