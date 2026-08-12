package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.ReflectedClassKind
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
    fun kindsThatNeedAReceiverAreLeftUnrenderedWithNoClassDescriptor() {
        // Rendering a METHOD as a module function would put the receiver handle in the caller's
        // hands as a bare int. A METHOD/GETTER/SETTER only gets a Python-visible surface when its
        // owning ReflectedClass is passed too (see the `classes` tests below). A STATIC_GETTER
        // needs no receiver and does get a surface -- as an attribute, never as a callable, which
        // is what the `static` tests below pin.
        val source = PythonProxySource.render(
            listOf(
                entry("p.Thing.method", arity = 1, kind = CallableKind.METHOD),
                entry("p.Thing.prop", kind = CallableKind.GETTER),
                entry("p.Thing", kind = CallableKind.CONSTRUCTOR),
                entry("p.plain"),
            ),
        )

        assertEquals(1, Regex("^_pm_h_\\d+ = _pm_bind", RegexOption.MULTILINE).findAll(source).count())
        assertContains(source, "_pm_bind('p.plain')")
        assertFalse(source.contains("p.Thing.method"), "a method must not be rendered as a module function")
        // Not `contains("class ")`: the support half now carries the `_PmModule` ModuleType
        // subclass, so the question is whether a *proxy* class was rendered, not whether the
        // keyword appears at all.
        assertFalse(source.contains("class Thing"), "no ReflectedClass was passed, so no class can be rendered")
        assertFalse(source.contains("_pm_module('p.Thing')"), "and nothing may be published under its name either")
    }

    // -------------------------------------------------------------------------- static properties

    @Test
    fun aTopLevelStaticPropertyBecomesALiveModuleAttributeRatherThanACallOrASnapshot() {
        // The two wrong answers this pins out: `mutableCounter()` would make the Python surface
        // disagree with the Kotlin declaration, and a plain `setattr(module, name, value)` at
        // install time would freeze a `var` at whatever it held when the table was installed.
        val source = PythonProxySource.render(
            listOf(
                entry("fixture.library.mutableCounter", kind = CallableKind.STATIC_GETTER),
                entry("fixture.library.mutableCounter=", arity = 1, kind = CallableKind.STATIC_SETTER),
            ),
        )

        assertContains(source, "_pm_h_0 = _pm_bind('fixture.library.mutableCounter')")
        assertContains(source, "_pm_h_1 = _pm_bind('fixture.library.mutableCounter=')")
        assertContains(
            source,
            "_pm_static_property(_pm_module('fixture.library'), 'mutableCounter', _pm_h_0, _pm_h_1)",
        )
        assertFalse(source.contains("def _pm_f_"), "a property must not be rendered as a callable")
    }

    @Test
    fun aReadOnlyTopLevelStaticPropertyRegistersNoSetterHandle() {
        val source = PythonProxySource.render(
            listOf(entry("fixture.library.libraryVersion", kind = CallableKind.STATIC_GETTER)),
        )

        assertContains(
            source,
            "_pm_static_property(_pm_module('fixture.library'), 'libraryVersion', _pm_h_0, None)",
        )
        assertEquals(1, Regex("^_pm_h_\\d+ = _pm_bind", RegexOption.MULTILINE).findAll(source).count())
    }

    @Test
    fun aStaticPropertyWithNoPackageOfItsOwnLandsInTheRootModule() {
        val source = PythonProxySource.render(listOf(entry("standalone", kind = CallableKind.STATIC_GETTER)))
        assertContains(source, "_pm_static_property(_pm_module('kotlin'), 'standalone', _pm_h_0, None)")
    }

    @Test
    fun aStaticPropertyOwnedByARenderedClassGoesOnAMetaclassSoTheClassAttributeItselfIsLive() {
        // A `property` in the class body answers `instance.x`, not `Foo.x` -- for the class object
        // to be the thing that reads and writes, the descriptor has to live on the class's type.
        // That is what a metaclass is, and Kotlin's own rule agrees: a companion member is reached
        // through the class, never through an instance.
        val getter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val setter = entry("p.Foo.count=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(getter.name, setter.name))

        val source = PythonProxySource.render(listOf(getter, setter), listOf(cls))

        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):").find(source)?.groupValues?.get(1)
        assertTrue(metaclass != null, "a class with static members needs a metaclass to hold them:\n$source")
        assertContains(source, "class Foo(metaclass=$metaclass):")
        assertContains(source, "    def count(cls):")
        assertContains(source, "    @count.setter")
        assertContains(source, "    def count(cls, a0):")
        // No receiver: a STATIC_SETTER's args[0] is the new value, not `cls`.
        assertContains(source, "_pm_invoke(_pm_h_1, (a0,))")
        assertFalse(source.contains("(cls._pm_handle"), "a static member has no receiver to pass")
        assertFalse(
            source.contains("_pm_static_property(_pm_module('p.Foo')"),
            "a class's static member must not also be published as an attribute of a module named after the class",
        )
    }

    @Test
    fun aReadOnlyClassStaticGetsAMetaclassPropertyWithNoSetter() {
        val getter = entry("p.Foo.TAG", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(getter.name))

        val source = PythonProxySource.render(listOf(getter), listOf(cls))

        assertContains(source, "    def TAG(cls):")
        assertFalse(source.contains(".setter"), "no STATIC_SETTER entry means no setter to render")
    }

    @Test
    fun aClassWithNoStaticMembersGetsNoMetaclassAtAll() {
        // The metaclass is a cost -- an extra type object per class, and a shape a reader has to
        // account for -- so it is only paid where there is something to put on it.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(ctor), listOf(cls))

        assertContains(source, "class Foo:")
        assertFalse(source.contains("(type):"), "nothing static to hold, so no metaclass")
    }

    @Test
    fun aStaticPropertyOfAKotlinObjectStaysOnTheModuleTheObjectsFunctionsAlreadyUse() {
        // A `ReflectedClassKind.OBJECT` is not rendered as a Python class -- its functions already
        // go through the ordinary function path into a module named after the object -- so its
        // properties have to land in the same place or `Registry.ping()` and `Registry.size` would
        // resolve against two different objects.
        val getter = entry("p.Registry.size", kind = CallableKind.STATIC_GETTER)
        val setter = entry("p.Registry.size=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val cls = ReflectedClass(
            name = "p.Registry",
            memberNames = listOf(getter.name, setter.name),
            kind = ReflectedClassKind.OBJECT,
        )

        val source = PythonProxySource.render(listOf(getter, setter), listOf(cls))

        assertContains(source, "_pm_static_property(_pm_module('p.Registry'), 'size', _pm_h_0, _pm_h_1)")
        assertFalse(source.contains("(type):"), "an object is not rendered as a class, so it has no metaclass")
    }

    @Test
    fun aTableHoldingNothingButStaticPropertiesStillRendersThem() {
        // Before they were rendered this table produced the "nothing to render" comment, which is
        // now a wrong answer rather than an honest one.
        val source = PythonProxySource.render(listOf(entry("p.version", kind = CallableKind.STATIC_GETTER)))
        assertFalse(source.contains("# no CallableKind.FUNCTION entries"))
        assertContains(source, "_pm_bind('p.version')")
    }

    @Test
    fun theSupportHalfCarriesTheModuleSubclassThatMakesAModuleAttributeWritable() {
        // PEP 562's module `__getattr__` covers the read half only; there is no module-level
        // `__setattr__` hook, so the module object itself has to be a ModuleType subclass.
        assertContains(PythonProxySource.support, "class _PmModule(_pm_types.ModuleType):")
        assertContains(PythonProxySource.support, "def __getattr__(self, _n):")
        assertContains(PythonProxySource.support, "def __setattr__(self, _n, _v):")
        assertContains(PythonProxySource.support, "def _pm_static_property(_mod, _name, _get, _set):")
    }

    @Test
    fun handlesStayUniqueWhenFunctionsClassStaticsAndModuleStaticsAreRenderedTogether() {
        val fn = entry("p.greet", arity = 1)
        val moduleStatic = entry("p.version", kind = CallableKind.STATIC_GETTER)
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val classGetter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val classSetter = entry("p.Foo.count=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val instanceGetter = entry("p.Foo.value", kind = CallableKind.GETTER)
        val cls = ReflectedClass(
            name = "p.Foo",
            memberNames = listOf(ctor.name, classGetter.name, classSetter.name, instanceGetter.name),
        )

        val source = PythonProxySource.render(
            listOf(fn, moduleStatic, ctor, classGetter, classSetter, instanceGetter),
            listOf(cls),
        )

        val handleNames = Regex("^(_pm_h_\\d+) = _pm_bind", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(handleNames.distinct(), handleNames, "every bound handle name must be unique")
        assertEquals(6, handleNames.size, "one handle per entry, and every entry is reachable")

        val metaclassNames = Regex("^class (_pm_t_\\d+)\\(type\\):", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(1, metaclassNames.size, "one class with statics, so exactly one metaclass")
        assertContains(source, "class Foo(metaclass=${metaclassNames[0]}):")
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
