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
 * ### What is still not rendered
 *
 * | kind | why not |
 * |---|---|
 * | `STATIC_GETTER`, `STATIC_SETTER` | a property, not a callable. Rendering it as `libraryVersion()` would make the Python surface disagree with the Kotlin declaration, and rendering it as a value would freeze a `var` at install time. Doing this right needs a module-level `__getattr__`/`__setattr__` (PEP 562), which nothing here emits yet |
 * | a `TypeTag.OBJECT` value returned from an arbitrary [CallableKind.FUNCTION] or [CallableKind.METHOD] | still crosses as the bare handle integer, not wrapped in the class rendered for it. Only a value that came from *this* proxy's own `__init__` -- i.e. something Python itself constructed -- gets the class. A factory function that should hand back a `Counter` today hands back an `int` |
 *
 * These are skipped silently *here* because the skip is a property of this stage, not a policy
 * decision -- `docs/binding-policy.md` already decided they are exposed, and they remain reachable
 * through the raw boundary. What is missing is only the sugar.
 */
object PythonProxySource {

    /** The name [AsyncUpcall] resolves out of `__main__` to settle a `Future` safely. */
    internal const val SETTLE_FUNCTION = "_pm_settle"

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
     *   function path above with no receiver, and an `ENUM`'s instances come from
     *   [CallableKind.STATIC_GETTER] entries this stage does not render either.
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
        val functions = entries.filter { it.kind == CallableKind.FUNCTION }
        val renderableClasses = classes.filter {
            it.kind == ReflectedClassKind.CLASS || it.kind == ReflectedClassKind.INTERFACE
        }

        return buildString {
            appendLine(support)
            appendLine()
            if (functions.isEmpty() && renderableClasses.isEmpty()) {
                // Not an error: a table can legitimately hold nothing this stage can render (only
                // static members, say). Saying so in the generated source beats emitting an empty
                // file that reads like a generator failure.
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
                // SETTER is picked up alongside its GETTER below; CONSTRUCTOR was handled above;
                // FUNCTION/STATIC_GETTER/STATIC_SETTER members of this class (an object's or a
                // companion's) already went through the ordinary function path in `render`.
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

            val setter = byName["${cls.name}.$propName="]
            if (setter != null) {
                val setterHandle = bindHandle()
                binds.appendLine("$setterHandle = _pm_bind(${setter.name.quoted()})")
                body.appendLine("    @$propName.setter")
                body.appendLine("    def $propName(self, a0):")
                body.appendLine("        _pm_invoke($setterHandle, (self._pm_handle, a0))")
                body.appendLine()
            }
        }

        val dot = cls.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else cls.name.substring(0, dot)
        val className = if (dot < 0) cls.name else cls.name.substring(dot + 1)

        val source = buildString {
            append(binds)
            appendLine()
            appendLine("class $className:")
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

    private fun String.quoted(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
