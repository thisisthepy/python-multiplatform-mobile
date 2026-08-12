package fixture.app

import fixture.library.SuspendingService
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the generator does with `suspend` today, pinned against the table KSP actually produced
 * from `ksp-fixtures/library/.../Suspending.kt`.
 *
 * The answer is **silently skipped**, and "silently" is the part worth a test:
 * `BindingPolicy.isExposedFunctionShape` drops any declaration carrying `Modifier.SUSPEND`
 * before it reaches the scanner, so nothing is emitted, nothing is logged, and the enclosing
 * class keeps every other member. A user exposing a `suspend fun` gets an `AttributeError` from
 * Python with no indication that the function was seen and rejected.
 *
 * Every assertion of an absence here is paired with a control in the same scope, so the test
 * cannot pass by the whole declaration having been dropped for some unrelated reason.
 *
 * `docs/upcall-async-design.md` is why the skip is the right behaviour for now and what would
 * replace it. When an async convention lands, these tests are what has to change -- deliberately,
 * with the new names asserted here, rather than the silence changing shape unnoticed.
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

    // ------------------------------------------------------------------ the skip, shape by shape

    @Test
    fun aSuspendingTopLevelFunctionIsNotInTheTableAndItsControlIs() {
        assertFalse(
            UpcallTable.resolve("fixture.library.suspendingTopLevel").isValid,
            "a suspend fun has no synchronous return value to hand back to the C frame that called it",
        )
        assertEquals(4L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.blockingTopLevel"), arrayOf(2L)))
    }

    @Test
    fun aSuspendingMemberIsDroppedButItsClassStaysExposedAndConstructible() {
        val service = UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingService.<init>"), arrayOf(7L))
        assertEquals(7L, (service as SuspendingService).id)

        assertFalse(UpcallTable.resolve("fixture.library.SuspendingService.fetch").isValid)
        assertEquals(
            "sync:k",
            UpcallTable.invoke(
                UpcallTable.resolve("fixture.library.SuspendingService.fetchBlocking"),
                arrayOf(service, "k"),
            ),
        )
    }

    @Test
    fun aSuspendingCompanionMemberIsDroppedFromTheOwnersStaticSurface() {
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingService.create").isValid)
        val made = UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingService.createBlocking"), arrayOf(3L))
        assertEquals(3L, (made as SuspendingService).id)
    }

    @Test
    fun aSuspendingInterfaceMemberIsDroppedAndTheInterfaceKeepsTheRest() {
        assertNotNull(ClassLookup.find("fixture.library.SuspendingSource"))
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingSource.load").isValid)
        assertEquals(CallableKind.METHOD, UpcallTable.callable(UpcallTable.resolve("fixture.library.SuspendingSource.describe")).kind)
    }

    @Test
    fun aSuspendingObjectMemberIsDroppedFromTheSingletonsSurface() {
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingRegistry.ping").isValid)
        assertEquals("pong", UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingRegistry.pingBlocking"), arrayOf()))
    }

    @Test
    fun aDroppedSuspendingMemberIsAbsentFromTheClassesMemberNamesToo() {
        // `ReflectedClass.memberNames` is what a Python mirror is built from. A name advertised
        // there but missing from the callable table resolves to nothing at attribute access.
        for ((className, dropped, kept) in listOf(
            Triple("fixture.library.SuspendingService", "fetch", "fetchBlocking"),
            Triple("fixture.library.SuspendingSource", "load", "describe"),
            Triple("fixture.library.SuspendingRegistry", "ping", "pingBlocking"),
        )) {
            val members = ClassLookup.require(className).memberNames
            assertFalse(members.contains("$className.$dropped"), "$className.$dropped must not be advertised")
            assertTrue(members.contains("$className.$kept"), "$className.$kept must stay advertised")
            for (member in members) {
                assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
            }
        }
    }

    // -------------------------------------------------- a suspending *type* is a different answer

    @Test
    fun aSuspendingFunctionTypeIsNotSkippedAtAllBecauseTheModifierIsOnTheTypeNotTheDeclaration() {
        // The policy reads `Modifier.SUSPEND` on a *declaration*. `val h: suspend (Long) -> Long`
        // carries no such modifier, so it is exposed like any other non-primitive: as an OBJECT
        // handle. Observed, not assumed -- and it is the one async-adjacent thing that crosses
        // today.
        val getter = UpcallTable.resolve("fixture.library.suspendingHandler")
        assertTrue(getter.isValid)
        assertEquals(TypeTag.OBJECT, UpcallTable.callable(getter).returnType)
        assertEquals(CallableKind.STATIC_GETTER, UpcallTable.callable(getter).kind)

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
    fun butWhatCrossesIsAnOpaqueHandleThatPythonCanHoldAndNotCall() {
        // The consequence of the previous test, and the reason it is not a workaround for the
        // skip: the handle has no entry of its own. `invoke` on a suspending function type is not
        // in the table under any name, so Python can pass the thing back to Kotlin and nothing
        // else. See docs/upcall-async-design.md.
        assertFalse(UpcallTable.resolve("kotlin.coroutines.SuspendFunction1.invoke").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.suspendingHandler.invoke").isValid)
    }
}
