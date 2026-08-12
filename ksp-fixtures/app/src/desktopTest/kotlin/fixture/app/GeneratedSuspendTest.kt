package fixture.app

import fixture.library.SuspendingService
import python.multiplatform.ffi.upcall.PendingCall
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the generator does with `suspend`, pinned against the table KSP actually produced from
 * `ksp-fixtures/library/.../Suspending.kt`.
 *
 * The answer used to be **silently skipped** -- `BindingPolicy.isExposedFunctionShape` dropped any
 * declaration carrying `Modifier.SUSPEND`, so nothing was emitted and nothing was logged, and a
 * user exposing a `suspend fun` got an `AttributeError` from Python with no indication that the
 * function had been seen and rejected. `docs/upcall-async-design.md` §6 recorded that as
 * deliberate: the silence was held in place so that the day a convention landed, the change would
 * show up as these tests failing rather than as one silence quietly becoming another.
 *
 * It has landed. A `suspend fun` now gets an ordinary entry whose body is
 * `PendingCall.start { ... }` and which carries [ExposedCallable.isSuspend]; the trampoline reads
 * that flag and either hands back the real value (the body never suspended) or an `asyncio.Future`
 * (it did). So what every assertion below checks is the *presence* of a shape rather than the
 * absence of one -- and the controls that used to guard against "the whole scope was dropped" are
 * still here, now guarding the opposite mistake: that suspending and non-suspending declarations
 * are not being given the same treatment.
 *
 * Two things this deliberately does **not** claim:
 *
 * - Nothing here touches CPython. The entry's shape and its `PendingCall` are checkable in plain
 *   Kotlin; whether `await` works is `AsyncUpcallDeliveryTest` in `python-multiplatform`.
 * - A `suspend` *type* (`val h: suspend (Long) -> Long`) is still a different question with the
 *   same old answer, and the last three tests are unchanged.
 */
class GeneratedSuspendTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(FunctionTable.fragments)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    private fun entry(name: String): ExposedCallable {
        val handle = UpcallTable.resolve(name)
        assertTrue(handle.isValid, "$name is not in the table")
        return UpcallTable.callable(handle)
    }

    /** Calls through the table the way the trampoline does, and asserts the shape it gets back. */
    private fun startCall(name: String, vararg args: Any?): PendingCall {
        val result = UpcallTable.invoke(UpcallTable.resolve(name), arrayOf(*args))
        assertNotNull(result, "$name returned nothing")
        assertTrue(result is PendingCall, "a suspending entry must hand back a PendingCall, got $result")
        return result
    }

    // ------------------------------------------------------------ the entry, shape by shape

    @Test
    fun aSuspendingTopLevelFunctionIsInTheTableAlongsideItsControl() {
        val suspending = entry("fixture.library.suspendingTopLevel")
        assertTrue(suspending.isSuspend, "the trampoline branches on this and nothing else")
        assertEquals(CallableKind.FUNCTION, suspending.kind)
        assertEquals(1, suspending.arity)
        assertEquals(listOf(TypeTag.INT), suspending.paramTypes)
        // The *declared* return type, not the PendingCall that actually comes back: the fast path
        // marshals the real value with this tag, and so does the Future's set_result.
        assertEquals(TypeTag.INT, suspending.returnType)

        val control = entry("fixture.library.blockingTopLevel")
        assertFalse(control.isSuspend, "a non-suspending function must not be given the async path")
        assertEquals(4L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.blockingTopLevel"), arrayOf(2L)))
    }

    @Test
    fun aSuspendingMemberKeepsItsReceiverSlotAndItsClassStaysConstructible() {
        val service = UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingService.<init>"), arrayOf(7L))
        assertEquals(7L, (service as SuspendingService).id)

        val fetch = entry("fixture.library.SuspendingService.fetch")
        assertTrue(fetch.isSuspend)
        // `suspend` and "has a receiver" are orthogonal, which is why the flag is not a
        // CallableKind: this is still a METHOD, and args[0] is still the instance.
        assertEquals(CallableKind.METHOD, fetch.kind)
        assertEquals(2, fetch.expectedArgCount)
        assertEquals(TypeTag.STRING, fetch.returnType)

        assertEquals(
            "sync:k",
            UpcallTable.invoke(
                UpcallTable.resolve("fixture.library.SuspendingService.fetchBlocking"),
                arrayOf(service, "k"),
            ),
        )
    }

    @Test
    fun aSuspendingCompanionMemberJoinsTheOwnersStaticSurface() {
        val create = entry("fixture.library.SuspendingService.create")
        assertTrue(create.isSuspend)
        assertEquals(CallableKind.FUNCTION, create.kind)
        assertEquals(TypeTag.OBJECT, create.returnType)

        val made = UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingService.createBlocking"), arrayOf(3L))
        assertEquals(3L, (made as SuspendingService).id)
    }

    @Test
    fun aSuspendingInterfaceMemberIsExposedLikeAnyOtherMethod() {
        assertNotNull(ClassLookup.find("fixture.library.SuspendingSource"))
        val load = entry("fixture.library.SuspendingSource.load")
        assertTrue(load.isSuspend)
        assertEquals(CallableKind.METHOD, load.kind)
        assertEquals(1, load.expectedArgCount, "no declared parameters, but the receiver is still a slot")

        assertEquals(CallableKind.METHOD, entry("fixture.library.SuspendingSource.describe").kind)
        assertFalse(entry("fixture.library.SuspendingSource.describe").isSuspend)
    }

    @Test
    fun aSuspendingObjectMemberJoinsTheSingletonsSurface() {
        val ping = entry("fixture.library.SuspendingRegistry.ping")
        assertTrue(ping.isSuspend)
        assertEquals(CallableKind.FUNCTION, ping.kind)
        assertEquals(0, ping.expectedArgCount)

        assertEquals("pong", UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingRegistry.pingBlocking"), arrayOf()))
    }

    @Test
    fun everySuspendingMemberIsAdvertisedOnItsClassAndResolves() {
        // `ReflectedClass.memberNames` is what a Python mirror is built from. A member missing
        // from it is invisible to Python however well the table entry is formed -- which is the
        // shape the old skip took, and the thing that had to change with it.
        for ((className, suspending, control) in listOf(
            Triple("fixture.library.SuspendingService", "fetch", "fetchBlocking"),
            Triple("fixture.library.SuspendingSource", "load", "describe"),
            Triple("fixture.library.SuspendingRegistry", "ping", "pingBlocking"),
        )) {
            val members = ClassLookup.require(className).memberNames
            assertTrue(members.contains("$className.$suspending"), "$className.$suspending must be advertised")
            assertTrue(members.contains("$className.$control"), "$className.$control must stay advertised")
            for (member in members) {
                assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
            }
        }
    }

    // ------------------------------------------------- the generated body, run without an interpreter

    @Test
    fun theGeneratedBodyStartsTheCoroutineAndParksItsOutcomeInAPendingCall() {
        // `PendingCall.start { suspendingTopLevel(args[0] as Long) }` is what the generator emits.
        // Nothing in that fixture reaches a suspension point, so `docs/upcall-async-design.md` §5's
        // fast path applies: the call is already complete before `start` returned, and the boundary
        // can hand Python a real `int` with no Future and no event loop.
        val call = startCall("fixture.library.suspendingTopLevel", 21L)

        assertTrue(call.isDone, "a body with no suspension point completes on the calling thread")
        assertEquals(42L, call.value)
        assertNull(call.failure)
    }

    @Test
    fun aSuspendingMethodsGeneratedBodyReadsItsReceiverFromArgsZero() {
        val service = UpcallTable.invoke(
            UpcallTable.resolve("fixture.library.SuspendingService.<init>"),
            arrayOf(1L),
        ) as SuspendingService

        val call = startCall("fixture.library.SuspendingService.fetch", service, "k")

        assertTrue(call.isDone)
        assertEquals("async:k", call.value)
    }

    @Test
    fun aSuspendingCompanionMemberReturnsARealKotlinObjectThroughThePendingCall() {
        val call = startCall("fixture.library.SuspendingService.create", 9L)

        assertTrue(call.isDone)
        assertEquals(9L, (call.value as SuspendingService).id)
    }

    // -------------------------------------------------- a suspending *type* is a different answer

    @Test
    fun aSuspendingFunctionTypeIsStillJustAnObjectBecauseTheModifierIsOnTheTypeNotTheDeclaration() {
        // Unchanged by the async convention, and deliberately so: the policy reads
        // `Modifier.SUSPEND` on a *declaration*, and `val h: suspend (Long) -> Long` carries no
        // such modifier. There is no `PendingCall` here to start -- what crosses is a handle.
        val getter = UpcallTable.resolve("fixture.library.suspendingHandler")
        assertTrue(getter.isValid)
        assertEquals(TypeTag.OBJECT, UpcallTable.callable(getter).returnType)
        assertEquals(CallableKind.STATIC_GETTER, UpcallTable.callable(getter).kind)
        assertFalse(UpcallTable.callable(getter).isSuspend, "the getter itself does not suspend")

        val made = UpcallTable.resolve("fixture.library.makeHandler")
        assertEquals(TypeTag.OBJECT, UpcallTable.callable(made).returnType)

        val consumer = UpcallTable.resolve("fixture.library.runsHandler")
        assertEquals(listOf(TypeTag.OBJECT), UpcallTable.callable(consumer).paramTypes)
    }

    @Test
    fun theGeneratedCastToASuspendingFunctionTypeHoldsAtRuntime() {
        // The generated source for `runsHandler` is
        //     args[0] as kotlin.coroutines.SuspendFunction1<kotlin.Long, kotlin.Long>
        // -- a classifier that has no source-level declaration. It compiles on both the JVM and
        // Kotlin/Native (checked: :ksp-fixtures:library:compileKotlinDesktop and
        // compileKotlinAndroidNativeArm64), but "compiles" and "the checkcast passes" are two
        // claims, and Python can reach this path: it holds the handle `makeHandler` returned and
        // hands it straight back.
        val handler = UpcallTable.invoke(UpcallTable.resolve("fixture.library.makeHandler"), arrayOf())
        assertNotNull(handler)

        val described = UpcallTable.invoke(UpcallTable.resolve("fixture.library.runsHandler"), arrayOf(handler))
        assertTrue((described as String).isNotEmpty())

        // and a suspending lambda made here, not by the fixture, casts the same way
        val local: suspend (Long) -> Long = { it + 1 }
        assertTrue((UpcallTable.invoke(UpcallTable.resolve("fixture.library.runsHandler"), arrayOf(local)) as String).isNotEmpty())
    }

    @Test
    fun butWhatCrossesIsStillAnOpaqueHandleThatPythonCanHoldAndNotCall() {
        // Unchanged, and still the reason a suspending *type* is not a way around anything: the
        // handle has no entry of its own. Exposing `suspend fun` did not expose
        // `SuspendFunction1.invoke`, and `docs/upcall-async-design.md` §2.1 keeps that open.
        assertFalse(UpcallTable.resolve("kotlin.coroutines.SuspendFunction1.invoke").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.suspendingHandler.invoke").isValid)
    }
}
