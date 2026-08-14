package python.multiplatform.ref

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `PhantomReference` release path — ROADMAP §4's one uncovered corner.
 *
 * `java.lang.ref.Cleaner` arrived in **Android API 33**. Every device below that runs a different
 * mechanism, and §4 has said "Android below API 33 uses the `PhantomReference` path and is not
 * covered yet" for as long as the section has existed. It stayed uncovered for a structural reason
 * rather than an oversight: the code lived in `androidMain`, so the only way to run it was on a
 * device, and the emulators in this project are API 36 — which takes the `Cleaner` branch and never
 * touches the fallback at all. The fallback could have been arbitrarily broken and every test in
 * the repository would still have passed.
 *
 * [PhantomCleanerRegistry] therefore lives in `jvmMain`, where `desktopTest` can reach it. Nothing
 * in it is Android-specific — `java.lang.ref.PhantomReference` and `ReferenceQueue` are Java 1.2 —
 * so running it on a desktop JVM exercises the same class an API 26 device would.
 *
 * **Both failure directions are here on purpose.** A test that only checks "the release eventually
 * runs" passes a mechanism that runs it twice, and running a CPython decref twice frees an object
 * that is still in use; this repository has already paid for that once, as a segfault inside
 * `_PyObject_ClearFreeLists` in a test that had nothing to do with the code at fault. So every
 * case below pins an exact count, never a lower bound.
 */
class PhantomCleanerRegistryTest {

    /** Stands in for the wrapper whose collection is supposed to trigger a release. */
    private class Owner

    /**
     * Registers a release against an owner that is unreachable the moment this returns.
     *
     * The owner is deliberately created *and* dropped inside a separate frame: leaving it in the
     * test method's own frame lets a local slot keep it alive for the rest of the method on some
     * JVMs, and the test would then be waiting for a collection that is not allowed to happen.
     *
     * The action closes over [counter] and nothing else. An action that could reach its own owner
     * would keep that owner strongly reachable through the registry's entry table, and the release
     * would never run — which is the single way to write this class that disables it silently.
     */
    private fun registerAndDrop(counter: AtomicInteger, throwing: Boolean = false) {
        val owner = Owner()
        PhantomCleanerRegistry.register(owner, Runnable {
            counter.incrementAndGet()
            if (throwing) throw RuntimeException("this release action fails on purpose")
        })
    }

    /** Registers, releases by hand, and drops the owner — all inside one frame, as above. */
    private fun registerCleanAndDrop(counter: AtomicInteger, cleanTimes: Int = 1) {
        val owner = Owner()
        val cleanable = PhantomCleanerRegistry.register(owner, Runnable { counter.incrementAndGet() })
        repeat(cleanTimes) { cleanable.clean() }
    }

    /** Bounded wait for the collector plus the drain thread; both are asynchronous. */
    private fun awaitCount(counter: AtomicInteger, target: Int, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) return true
            System.gc()
            Thread.sleep(20)
        }
        return counter.get() >= target
    }

    /** Gives the collector every chance to run something it must not run. */
    private fun insistOnCollection(rounds: Int = 25) {
        repeat(rounds) {
            System.gc()
            Thread.sleep(20)
        }
    }

    @Test
    fun aDroppedOwnerHasItsReleaseRunExactlyOnce() {
        val counter = AtomicInteger()
        registerAndDrop(counter)

        assertTrue(
            awaitCount(counter, 1),
            "the release for a dropped owner never ran (count: ${counter.get()}). On a device below " +
                "API 33 this is every reference the collector reclaims, leaked."
        )
        // The drain thread has already handed this entry back; nothing may hand it back again.
        insistOnCollection()
        assertEquals(
            1, counter.get(),
            "the release ran more than once. As a CPython decref that frees an object still in use."
        )
    }

    @Test
    fun twoDroppedOwnersRunTwoReleases() {
        // Positive control for the case above. `assertEquals(1, ...)` after an explicit clean()
        // proves nothing unless a second release would actually have been counted -- a counter
        // nobody increments passes that assertion trivially, and the "not covered yet" state this
        // test closes is exactly the state where such a mistake survives. Two owners, two releases.
        val counter = AtomicInteger()
        registerAndDrop(counter)
        registerAndDrop(counter)

        assertTrue(
            awaitCount(counter, 2),
            "two dropped owners should produce two releases, got ${counter.get()}"
        )
        assertEquals(2, counter.get(), "and no more than two")
    }

    @Test
    fun anExplicitCleanIsNotRunAgainByTheCollector() {
        val counter = AtomicInteger()
        registerCleanAndDrop(counter)

        assertEquals(1, counter.get(), "clean() should release immediately, not eventually")

        insistOnCollection()
        assertEquals(
            1, counter.get(),
            "the collector released a reference that close() had already given back. This is the " +
                "double free of ROADMAP §1: two owners for one reference, CPython's free lists " +
                "corrupted, and the crash landing in an unrelated test."
        )
    }

    @Test
    fun cleanIsIdempotent() {
        val counter = AtomicInteger()
        registerCleanAndDrop(counter, cleanTimes = 3)

        assertEquals(
            1, counter.get(),
            "close() must be idempotent; `RefCountTest.closingTwiceReleasesOnlyOnce` asserts the " +
                "same thing one layer up, against a real refcount"
        )
    }

    @Test
    fun aReleaseThatThrowsDoesNotStopLaterReleases() {
        // The drain loop used to catch InterruptedException and nothing else. A release action that
        // threw therefore propagated out of the loop, out of the Runnable, and killed the thread --
        // after which every later release on the whole process silently leaked, with no exception
        // anywhere to say so. `java.lang.ref.Cleaner`'s own thread swallows Throwable for this
        // reason, so the Cleaner path (API 33+) never had the problem and the fallback did.
        val failing = AtomicInteger()
        registerAndDrop(failing, throwing = true)
        assertTrue(
            awaitCount(failing, 1),
            "the throwing release should still have been reached (count: ${failing.get()})"
        )

        val later = AtomicInteger()
        registerAndDrop(later)
        assertTrue(
            awaitCount(later, 1),
            "a release that threw took the drain thread with it: the next release never ran " +
                "(count: ${later.get()}). Everything registered from here on leaks, on every " +
                "device below API 33."
        )
        assertTrue(
            PhantomCleanerRegistry.draining,
            "the drain loop is no longer running, so nothing registered from here on will ever be " +
                "released"
        )
        assertTrue(
            PhantomCleanerRegistry.failed >= 1,
            "the failure should have been counted, not swallowed without trace " +
                "(failed: ${PhantomCleanerRegistry.failed})"
        )
    }

    @Test
    fun anOwnerHeldAliveIsNotReleased() {
        // The other half of the contract, and the one that a leak-only test cannot state: a live
        // wrapper must keep its reference. If the entry table held the owner weakly in the wrong
        // place -- or if `register` enqueued eagerly -- this would release a pointer still in use.
        val counter = AtomicInteger()
        val owner = Owner()
        PhantomCleanerRegistry.register(owner, Runnable { counter.incrementAndGet() })

        insistOnCollection()

        assertEquals(
            0, counter.get(),
            "a reachable owner's reference was released while it was still alive"
        )
        // Without this the JIT is entitled to treat `owner` as dead from its last use -- the
        // `register` call above -- and collect it during `insistOnCollection()`, which would turn
        // this case into one that passes or fails on the optimiser's mood rather than on the
        // registry's behaviour.
        java.lang.ref.Reference.reachabilityFence(owner)
    }
}
