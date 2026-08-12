package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.ReflectedClassKind
import python.multiplatform.reflection.UpcallTable

/**
 * The Python half of `docs/upcall-async-design.md` §8.6's second gap: the `async def` proxy that
 * makes `await kotlin_fn(x)` read the same whether the Kotlin body suspended or not.
 *
 * Until this existed, `AsyncUpcallDeliveryTest` wrote that proxy by hand (`_await_kotlin`) and the
 * design doc said so. The shape is not optional and not a convenience: `AsyncUpcall.deliver` returns
 * **two different Python types for one Kotlin declaration** -- the real value when the coroutine had
 * already finished, an `asyncio.Future` when it had not -- because the fast path must not be made to
 * pay for an event loop it does not need. Something has to reconcile that, and an `async def` that
 * awaits its result only when the result is awaitable is the only place it can be done without
 * giving the fast path a `Future` too.
 *
 * ### Where this is generated, and why not at build time
 *
 * The two candidates were a `.py` file emitted by KSP and shipped as a resource, and Python source
 * rendered at run time and `exec`'d. This is the second, for three reasons:
 *
 * 1. **There is no resource path to put a `.py` on.** Kotlin/Native has no `getResourceAsStream`,
 *    and the interpreter's `sys.path` on iOS, androidNative and wasm points at the per-platform
 *    CPython trees under `src/nativeInterop/cinterop/lib/...`. Getting one generated file into each
 *    of those, and onto `sys.path` before the first import, is a per-platform packaging problem this
 *    repository has not solved for anything. `Python3.exec` already works on all five.
 * 2. **KSP does not know what will be installed.** A fragment is per Kotlin module; the table is
 *    assembled at run time by `UpcallTable.install`, and an application may install a subset (every
 *    test in this repository does). Build-time Python would describe a table that may never exist,
 *    and would have to be re-aggregated across klibs at run time anyway -- which is this, with less
 *    information.
 * 3. **`ExposedCallable` already carries everything.** [ExposedCallable.isSuspend],
 *    [ExposedCallable.arity] and [ExposedCallable.kind] are exactly the inputs, and they are on the
 *    entry KSP already emits. Emitting Python from KSP as well would duplicate that knowledge into a
 *    second generator that can disagree with the first.
 *
 * What is kept from the build-time option is the part that actually mattered: [render] is a **pure
 * function from entries to source text**, so it is pinned by `PythonProxySourceTest` in `commonTest`
 * with no interpreter anywhere, and the generated module is a readable artefact rather than a
 * behaviour. [install] is the only part that needs CPython, and it does nothing but `exec` what
 * [render] returned.
 *
 * ### GraalVM native image
 *
 * `CLAUDE.md` requires upcalls to survive a closed-world native image, which is why §7 chose
 * build-time tables over run-time reflection. Nothing here reintroduces that risk: this generates a
 * *Python* string from data that is already in a statically-emitted Kotlin table. There is no
 * reflection, no class loading and no dynamic Kotlin code -- the closed world stays closed.
 * (Unverified here: no native image was built for this change.)
 *
 * ### Instance surfaces: [CallableKind.METHOD], [CallableKind.GETTER], [CallableKind.SETTER]
 *
 * These need a receiver, which a bare module function has nowhere to put. [render] takes the
 * matching [ReflectedClass] descriptors and, for each one, emits a plain Python `class` whose
 * `__init__` (if a `CONSTRUCTOR` entry exists) calls through to Kotlin and stashes the resulting
 * handle in `self._pm_handle`, and whose methods and properties pass that handle as `args[0]` --
 * exactly the shape `pm_invoke` already expects for a receiver-carrying entry.
 *
 * This deliberately does **not** go through [python.multiplatform.ffi.ProxyTypeFactory]'s
 * `PyType_FromSpec` type. That type exists for one thing: giving a Kotlin-held Python reference a
 * `tp_traverse`/`tp_clear`/`tp_dealloc` slot so CPython's cyclic collector can see through it
 * (`CycleCollectionTest`). It carries no `tp_call`, `tp_methods` or `tp_getset` -- nothing that
 * would let Python *call into* Kotlin -- and nothing in this codebase wires a `TypeTag.OBJECT`
 * return into an instance of it; [UpcallTrampoline.marshalResult] hands back a bare
 * [python.multiplatform.reflection.HandleTable] integer today. A receiver in this design is
 * therefore just that integer, and a plain Python class holding one in an attribute is sufficient
 * -- no C type, no new slot, no new platform code.
 *
 * ### Static surfaces: [CallableKind.STATIC_GETTER], [CallableKind.STATIC_SETTER], and a
 * companion's [CallableKind.FUNCTION]
 *
 * A static property is an **attribute**, not a callable: rendering it as `libraryVersion()` would
 * make the Python surface disagree with the Kotlin declaration, and rendering it as a plain
 * assignment at install time would freeze a `var` at whatever it held then. So it has to be a
 * descriptor, and the only question is what object carries it. There are two answers because there
 * are two owners:
 *
 * - **A static member of a class that gets a Python `class`** (a `companion object` member, which
 *   `FragmentScanner` folds into the owner's name) goes on a **metaclass** rendered beside the
 *   class. A `property` in the class body would answer `instance.x`, not `Foo.x`; a descriptor
 *   answers for the object whose *type* carries it, so for the class object itself to read and
 *   write, the descriptor must live on the class's type. This also reproduces Kotlin's own rule
 *   for free -- a companion member is reached through the class and **not** through an instance,
 *   and a metaclass attribute is invisible to instances for exactly the same reason.
 * - **Everything else static** -- a top-level property, and the members of a `ReflectedClassKind`
 *   this stage does not render as a class ([ReflectedClassKind.OBJECT], [ReflectedClassKind.ENUM])
 *   -- becomes a live attribute of the module its name points at. That is not a second design so
 *   much as the same one applied to the module: the module object is reclassed to `_PmModule`, a
 *   `ModuleType` subclass whose `__getattr__`/`__setattr__` route registered names through the
 *   table. It has to be a subclass rather than PEP 562's module-level `__getattr__` because PEP 562
 *   covers the read half only and there is no `__setattr__` counterpart.
 *
 * Putting an `object`'s properties on the module rather than on a class of its own is what keeps
 * `Registry.ping()` and `Registry.size` resolving against the same Python object: `ping` is a
 * [CallableKind.FUNCTION] entry named `..Registry.ping` and has always gone through the module
 * path, so its sibling property has to follow it there.
 *
 * #### A companion **function** goes on the metaclass too
 *
 * `FragmentScanner.companionEntries` emits a companion function as a [CallableKind.FUNCTION] named
 * `pkg.Owner.fn` -- the same shape an `object`'s function has, and indistinguishable from it by
 * name alone. Sent down the module path it landed in a module `pkg.Owner`, which [renderClass] then
 * overwrote on the parent module with the class of that name. **Observed, in a real interpreter:**
 * `Counter.make(3)` raised `type object 'Counter' has no attribute 'make'` while a
 * `sys.modules['proxycls.Counter']` that still held `make` was left behind -- one Kotlin name with
 * two contradictory Python answers, only one of which anything pointed at.
 *
 * The judgement is that **one Python name cannot be both a module and a class**, so one of the two
 * has to move, and it is the function: the class is the thing an instance surface needs. That puts
 * it on the metaclass beside the static properties, which also gets Kotlin's own rule for free.
 * Rejected:
 *
 * | alternative | why not |
 * |---|---|
 * | a `staticmethod`/`classmethod` in the **class body** | reachable through an *instance* (`Counter(1).make(3)` resolves), which is a shape Kotlin does not have -- and it would contradict the sibling static *property*, which is on the metaclass precisely so instances cannot see it. The two halves of one companion would then disagree about what a companion is |
 * | merging the module into the class (the class absorbs the module's attributes) | the collision is only ever between a class and a module named after that class, i.e. between a class and its own companion. Building a general module/class merge for one case buys nothing the metaclass does not, and gives `pkg.Owner` a `__getattr__` that has to arbitrate between two namespaces |
 * | reversing the publication order so the module wins | the same bug pointed the other way: `Counter(1)` would stop working, and an instance surface is what the class rendering exists for. It also leaves the loser in `sys.modules`/on the parent module rather than removing it |
 *
 * An `object`'s function is deliberately **not** moved: a [ReflectedClassKind.OBJECT] is not
 * rendered as a Python class, so nothing overwrites its module and `Registry.ping()` and
 * `Registry.size` keep resolving against the same module object.
 *
 * #### What this cost, and the alternatives it was chosen over
 *
 * The price is one extra type object per class that has static members, and one more shape in the
 * generated source for a reader to account for. It is paid only where there is something to put on
 * it -- a class with no statics still renders as a bare `class Foo:`. The alternatives:
 *
 * | alternative | why not |
 * |---|---|
 * | accessor pair (`Foo.get_count()` / `Foo.set_count(v)`) | no metaclass, but it is the same disagreement between the Python surface and the Kotlin declaration that ruled out rendering the property as a callable in the first place. Rejecting it for a `val` and accepting it for a companion `val` would be arbitrary |
 * | module-level accessors (`fixture.library.WithCompanion_count`) | mangles the name, and `WithCompanion` is already taken as a module by the companion's *functions*, so the two halves of one companion would live in different places |
 * | a C type with `tp_getset` via [python.multiplatform.ffi.ProxyTypeFactory] | new platform C on five targets, for something a pure-Python descriptor already does. The same argument the instance section above makes against that type |
 *
 * The setter half follows `docs/binding-policy.md` rather than restating it: a `val`, or a `var`
 * whose setter is `private`/`protected`/`internal`, simply has no `STATIC_SETTER` entry, so this
 * renders a `property` with no `fset` (and, on a module, a `__setattr__` branch that raises). The
 * assignment fails with `AttributeError` instead of silently binding a plain attribute that would
 * shadow the Kotlin declaration for every later read.
 *
 * ### What is still not rendered
 *
 * | kind | why not |
 * |---|---|
 * | a `TypeTag.OBJECT` value returned from an arbitrary [CallableKind.FUNCTION] or [CallableKind.METHOD] | still crosses as the bare handle integer, not wrapped in the class rendered for it. Only a value that came from *this* proxy's own `__init__` -- i.e. something Python itself constructed -- gets the class. A factory function that should hand back a `Counter` today hands back an `int` |
 *
 * These are skipped silently *here* because the skip is a property of this stage, not a policy
 * decision -- `docs/binding-policy.md` already decided they are exposed, and they remain reachable
 * through the raw boundary. What is missing is only the sugar.
 */
object PythonProxySource {

    /** The name [AsyncUpcall] resolves out of `__main__` to settle a `Future` safely. */
    internal const val SETTLE_FUNCTION = "_pm_settle"

    /** The name [AsyncUpcall] resolves out of `__main__` to arm a `Future`'s cancellation notice. */
    internal const val WATCH_FUNCTION = "_pm_watch"

    /** The `(long) -> int` entry point `_pm_watch` routes a cancellation to; per-platform. */
    internal const val CANCEL_ENTRY_POINT = "_pm_cancel"

    /** The `(long) -> int` entry point that gives a [python.multiplatform.reflection.HandleTable]
     * handle back; already bound by every target's bootstrap, and reused here rather than
     * duplicated -- the handle `_pm_watch` carries is an ordinary object handle. */
    internal const val RELEASE_ENTRY_POINT = "_pm_release"

    /** Where names with no package of their own land; `docs/upcall-table-design.md` §Runtime. */
    const val DEFAULT_ROOT_MODULE: String = "kotlin"

    /**
     * The part that does not depend on any table: the guarded settle, the module-tree builder, and
     * name resolution.
     *
     * Installed on its own by [ensureSupportInstalled] because [AsyncUpcall] needs `_pm_settle`
     * whether or not anybody ever generated a proxy -- the delivery path exists independently of the
     * sugar on top of it.
     */
    val support: String = """
        # GENERATED by python-multiplatform. Do not edit.
        import sys as _pm_sys
        import types as _pm_types


        def _pm_settle(_fut, _ok, _payload):
            # Why this exists rather than scheduling `fut.set_result` directly: the Future can be
            # cancelled at any point, including between the moment Kotlin checks it and the moment
            # this callback runs, and `set_result` on a settled Future raises InvalidStateError.
            # Raised inside a loop callback that goes to `call_exception_handler`, which logs a
            # failure the application never asked for and cannot act on -- the value it describes
            # was abandoned on purpose. Measured before this guard existed:
            # AsyncUpcallCancellationTest saw exactly one 'InvalidStateError: invalid state'.
            if _fut.done():
                return
            if _ok:
                _fut.set_result(_payload)
            else:
                _fut.set_exception(_payload)


        def _pm_watch(_fut, _handle):
            # Early cancellation notice, and the only scheduled release of the handle that carries
            # it. Both halves are one callback because `add_done_callback` fires on *every* way a
            # Future can settle -- cancelled, resolved, or rejected -- which is exactly the set of
            # moments at which Kotlin either needs to be told something or no longer needs the
            # handle.
            #
            # Without this, the only point at which Kotlin could observe a cancellation was when its
            # coroutine finished and the delivery found the Future already settled: correct, and far
            # too late for a body that was still checking `ensureActive()` in a loop.
            #
            # Scheduled, not immediate: `Future.cancel()` settles the Future synchronously but hands
            # its callbacks to `call_soon`, so the notice arrives on the loop's next turn. That is
            # still arbitrarily earlier than completion, which is the whole claim.
            def _pm_done(_f, _h=_handle):
                try:
                    if _f.cancelled():
                        _pm_cancel(_h)
                finally:
                    # Unconditional, and safe to be beaten to it: a handle table release is
                    # generational, so a second one -- from Kotlin's own completion path -- is a
                    # no-op rather than a slot handed to somebody else.
                    _pm_release(_h)
            _fut.add_done_callback(_pm_done)


        def _pm_module(_name):
            # Exposed names are Kotlin fully-qualified names, so `fixture.library.greet` should be
            # reachable as `from fixture.library import greet`. Injecting into sys.modules is what
            # makes that work with no import hook: CPython's import machinery returns a sys.modules
            # hit for the full dotted name before it looks at any finder.
            _m = _pm_sys.modules.get(_name)
            if _m is None:
                _m = _pm_types.ModuleType(_name)
                _pm_sys.modules[_name] = _m
                if '.' in _name:
                    _parent, _, _leaf = _name.rpartition('.')
                    setattr(_pm_module(_parent), _leaf, _m)
            return _m


        def _pm_bind(_name):
            _h = _pm_resolve(_name.encode('utf-8'))
            if _h == -1:
                raise AttributeError('no exposed Kotlin declaration named ' + _name)
            return _h


        class _PmModule(_pm_types.ModuleType):
            # A Kotlin top-level `val`/`var` is a module *attribute* in Python, and it has to stay
            # live in both directions: a read has to call Kotlin's getter (a plain assignment at
            # install time would freeze a `var` at whatever it held then), and an assignment has to
            # reach Kotlin's setter rather than rebinding the name in the module dict. PEP 562's
            # module-level `__getattr__` covers the read half only -- there is no `__setattr__`
            # counterpart -- so the module object itself is an instance of this subclass.
            #
            # Only names registered through `_pm_static_property` are intercepted; everything else
            # on the module (the rendered functions and classes) stays an ordinary attribute, which
            # is why `__getattr__` (consulted only after normal lookup fails) is enough on the read
            # side and `__setattr__` has to fall through to `object.__setattr__` on the write side.

            def __getattr__(self, _n):
                _p = self.__dict__.get('_pm_props')
                if _p is not None and _n in _p:
                    return _pm_invoke(_p[_n][0], ())
                raise AttributeError(_n)

            def __setattr__(self, _n, _v):
                _p = self.__dict__.get('_pm_props')
                if _p is not None and _n in _p:
                    _s = _p[_n][1]
                    if _s is None:
                        # `docs/binding-policy.md`: a `val`, or a `var` whose setter is not public
                        # API, has no setter entry. Letting the assignment through would bind a
                        # plain module attribute that shadows the Kotlin declaration for every
                        # later read -- silently, and only in Python.
                        raise AttributeError(
                            'the Kotlin declaration behind ' + self.__name__ + '.' + _n +
                            ' has no exposed setter'
                        )
                    _pm_invoke(_s, (_v,))
                    return
                object.__setattr__(self, _n, _v)


        def _pm_static_property(_mod, _name, _get, _set):
            # `__getattr__`/`__setattr__` are looked up on the type, never on the instance, so the
            # module object has to be reclassed rather than decorated. Assigning `__class__` is the
            # documented way to do that to a module and is what PEP 562's own rationale describes.
            _mod.__class__ = _PmModule
            _p = _mod.__dict__.get('_pm_props')
            if _p is None:
                _p = {}
                object.__setattr__(_mod, '_pm_props', _p)
            _p[_name] = (_get, _set)
    """.trimIndent()

    /**
     * Renders the proxy module for [entries] and [classes].
     *
     * @param entries what the table holds; [UpcallTable.entries] is the usual source.
     * @param classes the class descriptors whose [CallableKind.METHOD]/[CallableKind.GETTER]/
     *   [CallableKind.SETTER]/[CallableKind.CONSTRUCTOR] entries in [entries] should get a Python
     *   class rather than being left unreachable; [ClassLookup.all] is the usual source. Only
     *   [ReflectedClassKind.CLASS] and [ReflectedClassKind.INTERFACE] are rendered -- an `object`'s
     *   members are already [CallableKind.FUNCTION]/[CallableKind.STATIC_GETTER] under the
     *   object's name (`FragmentScanner`'s `staticFunctionEntry`), so they go through the ordinary
     *   function and module-attribute paths with no receiver, and an `ENUM`'s instances come from
     *   [CallableKind.STATIC_GETTER] entries that land as module attributes for the same reason.
     *   Passing a class here also *claims* its [ReflectedClass.memberNames]: a static member of a
     *   rendered class goes on that class's metaclass instead of onto a module.
     * @param rootModule where a name with no dot in it goes. Kotlin's default package produces
     *   such names, and they have nowhere else to live.
     * @return Python source. Deterministic, and safe to `exec` more than once -- every statement is
     *   an assignment, a `def`, or a `class`.
     */
    fun render(
        entries: List<ExposedCallable>,
        classes: List<ReflectedClass> = emptyList(),
        rootModule: String = DEFAULT_ROOT_MODULE,
    ): String {
        val byName = entries.associateBy { it.name }
        val renderableClasses = classes.filter {
            it.kind == ReflectedClassKind.CLASS || it.kind == ReflectedClassKind.INTERFACE
        }
        // A static member of a class that gets a Python `class` of its own belongs on that class's
        // metaclass, not on a module named after the class -- the two would collide, since
        // `renderClass` publishes the class under exactly that name. Everything else static (a
        // top-level property or function, and an `object`'s or an `enum`'s, neither of which is
        // rendered as a Python class) is a module attribute, which is where its siblings went.
        val claimedByClasses = renderableClasses.flatMapTo(HashSet()) { it.memberNames }
        // A companion function is a FUNCTION named `pkg.Owner.fn`, so the module path below would
        // publish it into a *module* `pkg.Owner` -- which `renderClass` then overwrites with the
        // class of that name. Observed, not inferred: `Counter.make` raised
        // "type object 'Counter' has no attribute 'make'" while a half-populated
        // `sys.modules['proxycls.Counter']` held it, an answer nothing pointed at any more.
        val functions = entries.filter {
            it.kind == CallableKind.FUNCTION && it.name !in claimedByClasses
        }
        val moduleStatics = entries.filter {
            it.kind == CallableKind.STATIC_GETTER && it.name !in claimedByClasses
        }

        return buildString {
            appendLine(support)
            appendLine()
            if (functions.isEmpty() && renderableClasses.isEmpty() && moduleStatics.isEmpty()) {
                // Not an error: a table can legitimately hold nothing this stage can render (only
                // instance members with no class descriptor, say). Saying so in the generated
                // source beats emitting an empty file that reads like a generator failure.
                appendLine("# no CallableKind.FUNCTION entries or proxy classes to render")
                return@buildString
            }
            appendLine(ENTRY_POINT_GUARD)
            appendLine()
            var index = 0
            functions.forEach { entry ->
                appendLine(renderOne(index, entry, rootModule))
                index++
            }
            moduleStatics.forEach { getter ->
                val (source, nextIndex) = renderModuleStatic(index, getter, byName, rootModule)
                appendLine(source)
                index = nextIndex
            }
            renderableClasses.forEach { cls ->
                val (source, nextIndex) = renderClass(index, cls, byName, rootModule)
                appendLine(source)
                index = nextIndex
            }
        }
    }

    /**
     * Renders the proxies for whatever [UpcallTable] and [ClassLookup] currently hold and `exec`s
     * them.
     *
     * The caller has to have bound the two raw entry points (`_pm_resolve` and `_pm_invoke`) into
     * `__main__` first; how that is done is per-platform and is the one part of this path that is
     * not `commonMain` (desktop reaches them through `ctypes` over a Panama upcall stub, native
     * through the `PyMethodDef` surface in `UpcallEntry.kt`). The generated source checks for them
     * and raises rather than defining proxies that would fail one by one at call time.
     *
     * @return the source that was executed, so a caller can log or inspect exactly what ran.
     */
    fun install(rootModule: String = DEFAULT_ROOT_MODULE): String {
        val source = render(UpcallTable.entries(), ClassLookup.all(), rootModule)
        Python3.exec(source)
        return source
    }

    /**
     * Makes sure `_pm_settle` exists and hands it back.
     *
     * Looked up rather than cached in a Kotlin field on purpose: a `PyObject` held across an
     * interpreter that gets finalized and re-initialised is a dangling pointer, and this
     * repository's test fixture finalizes between tests. The lookup is a `sys.modules` hit plus one
     * attribute read, on the slow path only -- a call that genuinely suspended is not counting
     * attribute lookups, which is the same argument [AsyncUpcall.runningLoop] makes for itself.
     */
    internal fun settleFunction(): PyObject {
        val main = Python3.import("__main__")
        main.getAttrOrNull(SETTLE_FUNCTION)?.let { return it }
        Python3.exec(support)
        return main.getAttr(SETTLE_FUNCTION)
    }

    /**
     * Makes sure `_pm_watch` exists and hands it back, or `null` if arming it would not work.
     *
     * `null` is not a failure. `_pm_watch` calls [CANCEL_ENTRY_POINT] and [RELEASE_ENTRY_POINT],
     * which are per-platform bindings a host installs alongside `_pm_resolve`/`_pm_invoke`; a host
     * that has not installed them gets the behaviour that existed before early notice was possible
     * -- cancellation observed at completion -- rather than a `NameError` raised inside a loop
     * callback, where nobody would see it and nobody could act on it.
     *
     * Both names are checked here rather than inside the Python callback for a second reason: a
     * `_pm_watch` that could not be armed must not be paid for. [AsyncUpcall] registers the handle
     * only when this returns non-null, so a target without the bindings roots nothing and leaks
     * nothing.
     *
     * Looked up rather than cached for the reason [settleFunction] gives: a `PyObject` held across
     * an interpreter that gets finalized and re-initialised is a dangling pointer.
     */
    internal fun watchFunctionOrNull(): PyObject? {
        val main = Python3.import("__main__")
        if (main.getAttrOrNull(CANCEL_ENTRY_POINT) == null) return null
        if (main.getAttrOrNull(RELEASE_ENTRY_POINT) == null) return null
        main.getAttrOrNull(WATCH_FUNCTION)?.let { return it }
        Python3.exec(support)
        return main.getAttrOrNull(WATCH_FUNCTION)
    }

    // ------------------------------------------------------------------------------- rendering

    private val ENTRY_POINT_GUARD = """
        if '_pm_resolve' not in globals() or '_pm_invoke' not in globals():
            raise RuntimeError(
                'the raw upcall entry points are not bound: define _pm_resolve(name_bytes) -> handle '
                'and _pm_invoke(handle, args_tuple) -> result before installing proxies'
            )
    """.trimIndent()

    /**
     * A parenthesised Python tuple literal for [elements], with the one-element trailing comma
     * that turns `(a0)` (just `a0`) into an actual tuple -- the trampoline calls `PyTuple_Size` on
     * whatever it is handed, and a bare non-tuple argument fails that call rather than being
     * politely rejected.
     */
    private fun tupleOf(elements: List<String>): String = when (elements.size) {
        0 -> "()"
        1 -> "(${elements[0]},)"
        else -> "(${elements.joinToString(", ")})"
    }

    private fun params(arity: Int): List<String> = (0 until arity).map { "a$it" }

    private fun renderOne(index: Int, entry: ExposedCallable, rootModule: String): String {
        val handle = "_pm_h_$index"
        val function = "_pm_f_$index"
        val paramList = params(entry.arity).joinToString(", ")
        val argsTuple = tupleOf(params(entry.arity))
        val dot = entry.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else entry.name.substring(0, dot)
        val leaf = if (dot < 0) entry.name else entry.name.substring(dot + 1)

        val body = if (entry.isSuspend) {
            """
            |async def $function($paramList):
            |    _pm_r = _pm_invoke($handle, $argsTuple)
            |    # Two return types for one declaration: the real value when the Kotlin body never
            |    # reached a suspension point, an asyncio.Future when it did. Only the second costs
            |    # an await, which is what keeps the fast path free of the event loop.
            |    if hasattr(_pm_r, '__await__'):
            |        return await _pm_r
            |    return _pm_r
            """.trimMargin()
        } else {
            """
            |def $function($paramList):
            |    return _pm_invoke($handle, $argsTuple)
            """.trimMargin()
        }

        return """
            |$handle = _pm_bind(${entry.name.quoted()})
            |
            |
            |$body
            |
            |
            |$function.__name__ = ${leaf.quoted()}
            |$function.__qualname__ = ${entry.name.quoted()}
            |setattr(_pm_module(${module.quoted()}), ${leaf.quoted()}, $function)
        """.trimMargin()
    }

    /**
     * The setter entry paired with [getter], if the table has one.
     *
     * `null` is the answer for a Kotlin `val` **and** for a `var` whose setter is not public API
     * (`private`/`protected`/`internal set`) -- `FragmentScanner` emits no setter entry for either,
     * and this stage must not invent one. What it renders instead is a read-only attribute that
     * *raises* on assignment; see `_PmModule.__setattr__` and, for the class case, the
     * setter-less `property` on the metaclass.
     */
    private fun setterFor(
        getter: ExposedCallable,
        byName: Map<String, ExposedCallable>,
        kind: CallableKind,
    ): ExposedCallable? = byName["${getter.name}="]?.takeIf { it.kind == kind }

    /**
     * Renders one static property as a live attribute of the module its name points at.
     *
     * @return the source, and the next unused index -- one handle for the getter, and one more
     *   for the setter when there is one.
     */
    private fun renderModuleStatic(
        startIndex: Int,
        getter: ExposedCallable,
        byName: Map<String, ExposedCallable>,
        rootModule: String,
    ): Pair<String, Int> {
        var index = startIndex
        val getterHandle = "_pm_h_${index++}"
        val dot = getter.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else getter.name.substring(0, dot)
        val leaf = if (dot < 0) getter.name else getter.name.substring(dot + 1)

        val source = buildString {
            appendLine("$getterHandle = _pm_bind(${getter.name.quoted()})")
            val setter = setterFor(getter, byName, CallableKind.STATIC_SETTER)
            val setterHandle = if (setter == null) "None" else "_pm_h_${index++}"
            if (setter != null) appendLine("$setterHandle = _pm_bind(${setter.name.quoted()})")
            append(
                "_pm_static_property(_pm_module(${module.quoted()}), ${leaf.quoted()}, " +
                    "$getterHandle, $setterHandle)",
            )
        }
        return source to index
    }

    /**
     * Renders one Python class for [cls], plus the `_pm_bind` calls its members need.
     *
     * @param startIndex the first unused `_pm_h_N` / `_pm_f_N` suffix; shared with [render]'s
     *   function loop so a class's handles never collide with a module function's.
     * @return the source, and the next unused index -- the same threading [render] does across
     *   its function loop, extended across classes too.
     */
    private fun renderClass(
        startIndex: Int,
        cls: ReflectedClass,
        byName: Map<String, ExposedCallable>,
        rootModule: String,
    ): Pair<String, Int> {
        var index = startIndex
        fun bindHandle(): String {
            val handle = "_pm_h_$index"
            index++
            return handle
        }

        val binds = StringBuilder()
        val body = StringBuilder()

        val ctor = byName["${cls.name}.<init>"]
        if (ctor != null) {
            val handle = bindHandle()
            binds.appendLine("$handle = _pm_bind(${ctor.name.quoted()})")
            val paramList = params(ctor.arity).joinToString(", ")
            val callParams = if (paramList.isEmpty()) "self" else "self, $paramList"
            val argsTuple = tupleOf(params(ctor.arity))
            body.appendLine("    def __init__($callParams):")
            body.appendLine("        self._pm_handle = _pm_invoke($handle, $argsTuple)")
            body.appendLine()
        }

        // Preserves [ReflectedClass.memberNames] order (declaration order) for methods, but
        // collects property names into a set first: a GETTER and its SETTER are two entries with
        // one Python name between them, and rendering the getter as soon as its name is seen would
        // split `@property`/`@x.setter` across wherever each entry happened to fall in the member
        // list instead of keeping them adjacent, which is what a reader expects of one property.
        val propertyNames = LinkedHashSet<String>()
        val staticNames = LinkedHashSet<String>()
        val staticFunctionNames = LinkedHashSet<String>()
        for (memberName in cls.memberNames) {
            val entry = byName[memberName] ?: continue
            when (entry.kind) {
                CallableKind.METHOD -> {
                    val handle = bindHandle()
                    binds.appendLine("$handle = _pm_bind(${entry.name.quoted()})")
                    body.append(renderMethodBody(entry.name.substringAfterLast('.'), handle, entry))
                    body.appendLine()
                }
                CallableKind.GETTER -> propertyNames += entry.name.substringAfterLast('.')
                CallableKind.STATIC_GETTER -> staticNames += entry.name.substringAfterLast('.')
                // A companion's function. `render` no longer publishes it as a module function --
                // the module it would land in is the one this class overwrites -- so the metaclass
                // below is now the only place it exists.
                CallableKind.FUNCTION -> staticFunctionNames += entry.name.substringAfterLast('.')
                // SETTER and STATIC_SETTER are picked up alongside their getters below;
                // CONSTRUCTOR was handled above.
                else -> {}
            }
        }

        for (propName in propertyNames) {
            val getter = byName["${cls.name}.$propName"] ?: continue
            val getterHandle = bindHandle()
            binds.appendLine("$getterHandle = _pm_bind(${getter.name.quoted()})")
            body.appendLine("    @property")
            body.appendLine("    def $propName(self):")
            body.appendLine("        return _pm_invoke($getterHandle, (self._pm_handle,))")
            body.appendLine()

            val setter = setterFor(getter, byName, CallableKind.SETTER)
            if (setter != null) {
                val setterHandle = bindHandle()
                binds.appendLine("$setterHandle = _pm_bind(${setter.name.quoted()})")
                body.appendLine("    @$propName.setter")
                body.appendLine("    def $propName(self, a0):")
                body.appendLine("        _pm_invoke($setterHandle, (self._pm_handle, a0))")
                body.appendLine()
            }
        }

        // The metaclass body. A `property` in the class body answers `instance.x`; a static member
        // has no instance, and Kotlin reaches a companion member through the *class* and never
        // through an instance. A descriptor answers for the object whose *type* carries it, so for
        // the class object itself to be the reader and writer, the descriptor has to live on the
        // class's type -- which is what a metaclass is. See the KDoc's "Static surfaces" section
        // for the alternatives this was chosen over.
        val metaBody = StringBuilder()
        // A companion *function* goes on the same metaclass its companion *properties* do, for the
        // same two reasons: one Python name cannot be both a module and a class, and Kotlin reaches
        // a companion member through the class and never through an instance. A `staticmethod` in
        // the class body would answer the first but not the second -- Python resolves a
        // `staticmethod` through an instance quite happily, which is a shape Kotlin does not have.
        for (staticFunctionName in staticFunctionNames) {
            val entry = byName["${cls.name}.$staticFunctionName"] ?: continue
            val handle = bindHandle()
            binds.appendLine("$handle = _pm_bind(${entry.name.quoted()})")
            metaBody.append(renderStaticFunctionBody(staticFunctionName, handle, entry))
            metaBody.appendLine()
            metaBody.appendLine()
        }
        for (staticName in staticNames) {
            val getter = byName["${cls.name}.$staticName"] ?: continue
            val getterHandle = bindHandle()
            binds.appendLine("$getterHandle = _pm_bind(${getter.name.quoted()})")
            metaBody.appendLine("    @property")
            metaBody.appendLine("    def $staticName(cls):")
            // No receiver: a STATIC_GETTER's args are empty and a STATIC_SETTER's args[0] is the
            // new value, so `cls` is a Python-side formality and never crosses the boundary.
            metaBody.appendLine("        return _pm_invoke($getterHandle, ())")
            metaBody.appendLine()

            val setter = setterFor(getter, byName, CallableKind.STATIC_SETTER)
            if (setter != null) {
                val setterHandle = bindHandle()
                binds.appendLine("$setterHandle = _pm_bind(${setter.name.quoted()})")
                metaBody.appendLine("    @$staticName.setter")
                metaBody.appendLine("    def $staticName(cls, a0):")
                metaBody.appendLine("        _pm_invoke($setterHandle, (a0,))")
                metaBody.appendLine()
            }
        }
        // A class with nothing static pays nothing: no extra type object, and no shape a reader of
        // the generated source has to account for.
        val metaclassName = if (metaBody.isEmpty()) null else "_pm_t_${index++}"

        val dot = cls.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else cls.name.substring(0, dot)
        val className = if (dot < 0) cls.name else cls.name.substring(dot + 1)

        val source = buildString {
            append(binds)
            appendLine()
            if (metaclassName != null) {
                appendLine("class $metaclassName(type):")
                appendLine()
                append(metaBody)
                appendLine()
            }
            appendLine(if (metaclassName == null) "class $className:" else "class $className(metaclass=$metaclassName):")
            if (body.isEmpty()) appendLine("    pass") else append(body)
            appendLine()
            appendLine("$className.__qualname__ = ${cls.name.quoted()}")
            appendLine("setattr(_pm_module(${module.quoted()}), ${className.quoted()}, $className)")
        }
        return source to index
    }

    /** The method half of [renderClass]: like [renderOne]'s body, but `self._pm_handle` is
     * always the first element of the args tuple. */
    private fun renderMethodBody(name: String, handle: String, entry: ExposedCallable): String {
        val paramList = params(entry.arity).joinToString(", ")
        val callParams = if (paramList.isEmpty()) "self" else "self, $paramList"
        val argsTuple = tupleOf(listOf("self._pm_handle") + params(entry.arity))

        return if (entry.isSuspend) {
            """
            |    async def $name($callParams):
            |        _pm_r = _pm_invoke($handle, $argsTuple)
            |        if hasattr(_pm_r, '__await__'):
            |            return await _pm_r
            |        return _pm_r
            """.trimMargin()
        } else {
            """
            |    def $name($callParams):
            |        return _pm_invoke($handle, $argsTuple)
            """.trimMargin()
        }
    }

    /**
     * The companion-function half of [renderClass]'s metaclass: like [renderMethodBody], but the
     * first parameter is the class object and it is **not** passed across the boundary.
     *
     * A `CallableKind.FUNCTION` emitted for a companion member takes no receiver -- its arguments
     * start at `args[0]`, exactly as a top-level function's do -- so `cls` is a Python-side
     * formality, the same one [renderClass]'s static properties already have.
     */
    private fun renderStaticFunctionBody(name: String, handle: String, entry: ExposedCallable): String {
        val paramList = params(entry.arity).joinToString(", ")
        val callParams = if (paramList.isEmpty()) "cls" else "cls, $paramList"
        val argsTuple = tupleOf(params(entry.arity))

        return if (entry.isSuspend) {
            """
            |    async def $name($callParams):
            |        _pm_r = _pm_invoke($handle, $argsTuple)
            |        if hasattr(_pm_r, '__await__'):
            |            return await _pm_r
            |        return _pm_r
            """.trimMargin()
        } else {
            """
            |    def $name($callParams):
            |        return _pm_invoke($handle, $argsTuple)
            """.trimMargin()
        }
    }

    private fun String.quoted(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
