package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generated Python proxy actually running -- `docs/upcall-async-design.md` §8.6's second gap.
 *
 * `PythonProxySourceTest` (commonTest) pins what [PythonProxySource.render] emits, as text, with no
 * interpreter. This is the other half: CPython executes that text, and `await` over the generated
 * `async def` behaves the same whether the Kotlin body suspended or not. Until this existed the
 * `async def` was written by hand in `AsyncUpcallDeliveryTest` (`_await_kotlin`) and the design doc
 * recorded that as a gap.
 *
 * ### How the two paths are told apart
 *
 * The generated proxy hides what the boundary handed back -- that is the entire point of it -- so
 * `AsyncUpcallDeliveryTest`'s trick of asserting on `type(r).__name__` is not available here. The
 * loop's `create_future` is counted instead, which is a **stronger** statement about the same thing:
 * it says no `Future` was constructed at all on the fast path, not merely that one was not returned.
 */
class PythonProxyInstallTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ProxyFragment))
        ProxyFragment.parked.clear()
        // `ProxyFragment` is an `object`, so the static-property fixtures outlive a single test.
        ProxyFragment.tally = 0
        ProxyFragment.created = 0
        // Binds `_pm_resolve` and `_pm_invoke` into `__main__`. The generated module needs them and
        // refuses to install without them; which name is bound is irrelevant, only the bootstrap is.
        assertTrue(bindUpcallOrNull("demo.calc.ping"), "the fixture table is not installed")
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        ProxyFragment.parked.clear()
    }

    @Test
    fun aGeneratedProxyIsPublishedUnderItsKotlinPackageAndCallableAsAnOrdinaryFunction() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()

            // `from demo.calc import shout` -- the sys.modules injection is what makes the dotted
            // Kotlin name an importable Python name with no import hook anywhere.
            Python3.exec(
                """
                from demo.calc import shout, ping
                _p_sync = {'shout': shout('world'), 'ping': ping(), 'name': shout.__name__}
                """.trimIndent(),
            )

            assertEquals("hi world", PythonTestFixture.eval("_p_sync['shout']").toString())
            assertEquals("7", PythonTestFixture.eval("_p_sync['ping']").toString())
            assertEquals("shout", PythonTestFixture.eval("_p_sync['name']").toString())
        }

    @Test
    fun creatingAKotlinObjectFromPythonAndCallingAnInstanceMethodDispatchesThroughTheReceiverHandle() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()

            Python3.exec(
                """
                from proxycls import Counter
                c = Counter(10)
                _p_method = {'after': c.increment(5), 'again': c.increment(1)}
                """.trimIndent(),
            )

            assertEquals("15", PythonTestFixture.eval("_p_method['after']").toString())
            assertEquals("16", PythonTestFixture.eval("_p_method['again']").toString())
        }

    @Test
    fun readingAndWritingAPropertyGoesThroughTheGetterAndSetterEntries() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()

            Python3.exec(
                """
                from proxycls import Counter
                c = Counter(1)
                _p_prop = {'before': c.value, 'label_before': c.label}
                c.label = 'renamed'
                _p_prop['label_after'] = c.label
                """.trimIndent(),
            )

            assertEquals("1", PythonTestFixture.eval("_p_prop['before']").toString())
            assertEquals("counter", PythonTestFixture.eval("_p_prop['label_before']").toString())
            assertEquals("renamed", PythonTestFixture.eval("_p_prop['label_after']").toString())
        }

    @Test
    fun aStaticPropertyIsReadAndWrittenThroughTheClassObjectItselfRatherThanThroughAnInstance() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            // *After* install: a value the generator could not have baked in. A snapshot rendering
            // would report whatever `created` held while the source was being built.
            ProxyFragment.created = 5

            Python3.exec(
                """
                from proxycls import Counter
                _p_static = {'before': Counter.created, 'kind': Counter.KIND}
                Counter.created = 12
                _p_static['after'] = Counter.created
                try:
                    _p_static['on_instance'] = Counter(1).created
                except AttributeError:
                    _p_static['on_instance'] = 'AttributeError'
                """.trimIndent(),
            )

            assertEquals("5", PythonTestFixture.eval("_p_static['before']").toString())
            assertEquals("counter-class", PythonTestFixture.eval("_p_static['kind']").toString())
            assertEquals("12", PythonTestFixture.eval("_p_static['after']").toString())
            assertEquals(12L, ProxyFragment.created, "the assignment from Python has to reach Kotlin's setter")
            // Kotlin reaches a companion member through the class and never through an instance;
            // putting the descriptor on the metaclass reproduces that rather than inventing a
            // Python-only shape.
            assertEquals("AttributeError", PythonTestFixture.eval("_p_static['on_instance']").toString())
        }

    @Test
    fun aStaticPropertyWithNoExposedSetterRefusesAssignmentInsteadOfShadowingTheKotlinVal() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()

            Python3.exec(
                """
                from proxycls import Counter
                try:
                    Counter.KIND = 'overwritten'
                    _p_ro = 'assignment succeeded'
                except AttributeError:
                    _p_ro = 'AttributeError'
                _p_ro_after = Counter.KIND
                """.trimIndent(),
            )

            // Letting the assignment through would bind a plain class attribute that shadows the
            // Kotlin `val` for every later read -- silently, and only in Python.
            assertEquals("AttributeError", PythonTestFixture.eval("_p_ro").toString())
            assertEquals("counter-class", PythonTestFixture.eval("_p_ro_after").toString())
        }

    @Test
    fun aTopLevelStaticPropertyIsReadAndWrittenAsAnOrdinaryModuleAttribute() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            ProxyFragment.tally = 3

            Python3.exec(
                """
                import demo.calc
                _p_mod = {'before': demo.calc.tally, 'origin': demo.calc.origin}
                demo.calc.tally = 9
                _p_mod['after'] = demo.calc.tally
                # the module's ordinary function attributes must still resolve after the module
                # object was reclassed to carry the dynamic ones
                _p_mod['ping'] = demo.calc.ping()
                # and a name with no Kotlin declaration behind it must stay an ordinary attribute:
                # __setattr__ intercepts only what was registered, everything else falls through
                demo.calc.marker = 'plain'
                _p_mod['marker'] = demo.calc.marker
                """.trimIndent(),
            )

            assertEquals("3", PythonTestFixture.eval("_p_mod['before']").toString())
            assertEquals("kotlin", PythonTestFixture.eval("_p_mod['origin']").toString())
            assertEquals("9", PythonTestFixture.eval("_p_mod['after']").toString())
            assertEquals("7", PythonTestFixture.eval("_p_mod['ping']").toString())
            assertEquals("plain", PythonTestFixture.eval("_p_mod['marker']").toString())
            assertEquals(9L, ProxyFragment.tally, "the assignment from Python has to reach Kotlin's setter")
        }

    @Test
    fun aReadOnlyTopLevelStaticPropertyRefusesAssignmentToo() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()

            Python3.exec(
                """
                import demo.calc
                try:
                    demo.calc.origin = 'overwritten'
                    _p_mod_ro = 'assignment succeeded'
                except AttributeError:
                    _p_mod_ro = 'AttributeError'
                _p_mod_ro_after = demo.calc.origin
                """.trimIndent(),
            )

            assertEquals("AttributeError", PythonTestFixture.eval("_p_mod_ro").toString())
            assertEquals("kotlin", PythonTestFixture.eval("_p_mod_ro_after").toString())
        }

    @Test
    fun awaitingASuspendingInstanceMethodDeliversTheValueFromAKotlinThread() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            installLoopHarness()
            Python3.exec("from proxycls import Counter\nc = Counter(3)")

            val outcome = runOnLoopThread("c.fetchLater(4)")

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals("None", outcome.error, "the generated proxy raised instead of resolving")
            assertEquals("7", outcome.value)
            assertEquals(
                1,
                outcome.futuresCreated,
                "no Future was built, so this run took the fast path and proves nothing about delivery",
            )
        }

    @Test
    fun awaitingAGeneratedProxyForASuspendingCallDeliversTheValueFromAKotlinThread() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            installLoopHarness()

            val outcome = runOnLoopThread("demo.calc.doubleLater(21)")

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals("None", outcome.error, "the generated proxy raised instead of resolving")
            assertEquals("42", outcome.value)
            assertEquals(
                1,
                outcome.futuresCreated,
                "no Future was built, so this run took the fast path and proves nothing about delivery",
            )
        }

    @Test
    fun theSameGeneratedProxyBuildsNoFutureWhenTheKotlinBodyNeverSuspends() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            installLoopHarness()

            // Identical call site, identical `await`, and the difference is invisible from Python --
            // which is what the conditional await in the generated `async def` is for.
            Python3.exec("_drive(demo.calc.doubleNow(21))")

            assertEquals("None", PythonTestFixture.eval("_p['error']").toString())
            assertEquals("42", PythonTestFixture.eval("_p['value']").toString())
            assertEquals(
                "0",
                PythonTestFixture.eval("_p['created']").toString(),
                "an already-complete call must not be routed through asyncio at all",
            )
            assertTrue(ProxyFragment.parked.isEmpty(), "nothing was ever left outstanding")
        }

    @Test
    fun aKotlinFailureAfterSuspensionReachesTheAwaitOnTheGeneratedProxy() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            installLoopHarness()

            val outcome = runOnLoopThread("demo.calc.failLater()")

            assertNull(outcome.completerFailure)
            assertEquals(1, outcome.futuresCreated, "this run never reached the delivery path")
            assertEquals("RuntimeError: proxy boom", outcome.error)
        }

    // ------------------------------------------------------------------------------------ harness

    private class Outcome(
        val value: String,
        val error: String,
        val futuresCreated: Int,
        val completerFailure: Throwable?,
    )

    /**
     * A loop whose `create_future` is counted.
     *
     * The wrapper is an instance attribute on a pure-Python selector loop, so it shadows the class
     * method for this loop only and `AsyncUpcall`'s `loop.create_future()` goes through it -- there
     * is no other way to observe from Python whether the boundary took the fast path, because the
     * generated proxy deliberately makes the two indistinguishable to its caller.
     */
    private fun installLoopHarness() {
        Python3.exec(
            """
            import asyncio
            # The proxies live in modules injected into sys.modules; injecting them does not bind
            # `demo` as a name here, so the ordinary import statement still has to run. That it
            # works at all is the point of the injection -- there is no finder that could locate
            # `demo.calc` on disk.
            import demo.calc
            _p = {}
            _p['loop'] = asyncio.new_event_loop()
            _p['created'] = 0

            def _count_futures(_loop=_p['loop'], _orig=_p['loop'].create_future):
                def _wrapped():
                    _p['created'] += 1
                    return _orig()
                _loop.create_future = _wrapped

            _count_futures()

            def _drive(coro):
                _p['value'] = None
                _p['error'] = None
                try:
                    _p['value'] = _p['loop'].run_until_complete(coro)
                except BaseException as e:
                    _p['error'] = type(e).__name__ + ': ' + str(e)
            """.trimIndent(),
        )
    }

    /**
     * Drives [coroutineExpression] on this thread's loop while a Kotlin thread resumes the
     * continuation the fixture parked.
     *
     * The completer waits until a `Future` has actually been created before resuming, for the reason
     * `AsyncUpcallDeliveryTest` records: resuming while `AsyncUpcall.deliver` is still deciding what
     * to return makes `PendingCall.isDone` true early, the fast path is taken, and the run exercises
     * nothing. The counter installed above is the cheapest honest signal for that, and it is read
     * under the GIL on a thread CPython has never seen.
     */
    private fun runOnLoopThread(coroutineExpression: String): Outcome {
        var completerFailure: Throwable? = null

        val completer = Thread {
            try {
                var polls = 0
                while (polls++ < 20_000) {
                    if (withGIL { PythonTestFixture.eval("_p['created']").toString() } != "0") break
                    Thread.sleep(1)
                }
                val resume = ProxyFragment.parked.poll(20, TimeUnit.SECONDS)
                    ?: throw AssertionError("no suspending Kotlin call ever reached the fixture")
                withGIL { resume.invoke() }
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.name = "kotlin-proxy-completer"
        completer.start()

        Python3.exec("_drive($coroutineExpression)")
        completer.join(40_000)

        return Outcome(
            value = PythonTestFixture.eval("_p['value']").toString(),
            error = PythonTestFixture.eval("_p['error']").toString(),
            futuresCreated = PythonTestFixture.eval("_p['created']").toString().toInt(),
            completerFailure = completerFailure,
        )
    }
}

/**
 * Entries shaped the way KSP emits them, under names whose Kotlin package maps to a Python module
 * that is a legal identifier -- unlike `AsyncTrampolineFragment`'s `async.*`, which `sys.modules`
 * accepts but no `import` statement can spell.
 */
object ProxyFragment : FunctionTableFragment {

    override val moduleName: String = "test_python_proxy"

    val parked = LinkedBlockingQueue<() -> Unit>()

    /**
     * The Kotlin state behind the `STATIC_GETTER`/`STATIC_SETTER` entries: a top-level `var` in
     * `demo.calc` and a companion-style `var` on `proxycls.Counter`. Both are read and written from
     * the Kotlin side of the assertions, which is how "the Python assignment reached Kotlin" is
     * observed rather than assumed.
     */
    var tally: Long = 0

    var created: Long = 0

    private suspend fun doubleLater(x: Long): Long = suspendCoroutine { c -> parked.put { c.resume(x * 2) } }

    private suspend fun doubleNow(x: Long): Long = x * 2

    private suspend fun failLater(): Long =
        suspendCoroutine { c -> parked.put { c.resumeWithException(IllegalStateException("proxy boom")) } }

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "demo.calc.doubleLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleLater(args[0] as Long) } },
        ExposedCallable(
            name = "demo.calc.doubleNow",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleNow(args[0] as Long) } },
        ExposedCallable(
            name = "demo.calc.failLater",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { failLater() } },
        ExposedCallable(
            name = "demo.calc.shout",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
        ) { args -> "hi ${args[0] as String}" },
        ExposedCallable(
            name = "demo.calc.ping",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
        ) { 7L },
        // A top-level Kotlin `val`: readable as a module attribute, and not assignable.
        ExposedCallable(
            name = "demo.calc.origin",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.STATIC_GETTER,
        ) { "kotlin" },
        // A top-level Kotlin `var`: the same, plus a setter entry.
        ExposedCallable(
            name = "demo.calc.tally",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.STATIC_GETTER,
        ) { tally },
        ExposedCallable(
            name = "demo.calc.tally=",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.STATIC_SETTER,
        ) { args -> tally = args[0] as Long },
    ) + counterEntries()

    override fun classes(): List<ReflectedClass> = listOf(
        ReflectedClass(
            name = COUNTER,
            memberNames = listOf(
                "$COUNTER.<init>",
                "$COUNTER.increment",
                "$COUNTER.value",
                "$COUNTER.label",
                "$COUNTER.label=",
                "$COUNTER.fetchLater",
                "$COUNTER.KIND",
                "$COUNTER.created",
                "$COUNTER.created=",
            ),
        ),
    )

    private const val COUNTER = "proxycls.Counter"

    private fun counterEntries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "$COUNTER.<init>",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.CONSTRUCTOR,
        ) { args -> ProxyCounter(args[0] as Long) },
        ExposedCallable(
            name = "$COUNTER.increment",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.METHOD,
        ) { args -> (args[0] as ProxyCounter).increment(args[1] as Long) },
        ExposedCallable(
            name = "$COUNTER.value",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.GETTER,
        ) { args -> (args[0] as ProxyCounter).value },
        ExposedCallable(
            name = "$COUNTER.label",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.GETTER,
        ) { args -> (args[0] as ProxyCounter).label },
        ExposedCallable(
            name = "$COUNTER.label=",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER,
        ) { args -> (args[0] as ProxyCounter).label = args[1] as String },
        ExposedCallable(
            name = "$COUNTER.fetchLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.METHOD,
            isSuspend = true,
        ) { args -> PendingCall.start { (args[0] as ProxyCounter).fetchLater(args[1] as Long) } },
        // The companion-object shape: no receiver in `args`, and the class object itself is what
        // Python reads and writes. `KIND` is the `val` half, `created` the `var` half.
        ExposedCallable(
            name = "$COUNTER.KIND",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.STATIC_GETTER,
        ) { "counter-class" },
        ExposedCallable(
            name = "$COUNTER.created",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.STATIC_GETTER,
        ) { created },
        ExposedCallable(
            name = "$COUNTER.created=",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.STATIC_SETTER,
        ) { args -> created = args[0] as Long },
    )
}

/**
 * The instance fixture for [PythonProxyInstallTest]'s class-rendering tests: a receiver that
 * arrives in `args[0]` the way `CallableKind.METHOD`/`GETTER`/`SETTER` require, resolved from the
 * `self._pm_handle` integer the generated `Counter.__init__` stashes. Named differently from
 * [ProxyFragment]'s Python-visible `Counter` so a grep for the Kotlin declaration is unambiguous.
 */
class ProxyCounter(private var total: Long) {
    fun increment(by: Long): Long {
        total += by
        return total
    }

    val value: Long get() = total

    var label: String = "counter"

    suspend fun fetchLater(x: Long): Long =
        suspendCoroutine { c -> ProxyFragment.parked.put { c.resume(total + x) } }
}
