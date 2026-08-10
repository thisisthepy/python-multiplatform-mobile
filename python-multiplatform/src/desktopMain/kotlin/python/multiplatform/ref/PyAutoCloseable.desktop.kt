package python.multiplatform.ref

import python.native.ffi.NativePointer
import java.lang.ref.Cleaner


actual abstract class PyAutoCloseable actual constructor(pointer: NativePointer): AutoCloseable {
    private companion object {
        /**
         * One Cleaner for the whole process.
         *
         * Cleaner.create() starts a dedicated OS thread. Calling it once per instance --
         * as this class used to -- spawns a thread per PyObject and kills the process with
         * "OutOfMemoryError: unable to create native thread" after a few thousand objects.
         * Registering with an existing Cleaner is cheap; creating one is not.
         *
         * Surfaced by BenchmarkTest.testAttributeAccess, which only became runnable once
         * the desktop target could execute at all.
         */
        val SHARED_CLEANER: Cleaner = Cleaner.create()
    }

    /**
     * NOTE: this lambda captures `this`, so the Cleanable keeps the instance strongly
     * reachable and GC-driven cleanup never fires -- today only an explicit close() runs
     * clean(). Fixing that requires the cleanup action to close over the raw pointer
     * rather than the instance, which changes the expect/actual shape on every platform.
     * Left as-is here deliberately; it is a separate problem from the thread explosion.
     */
    private val cleanable: Cleaner.Cleanable = SHARED_CLEANER.register(this) {
        clean()
    }

    actual abstract fun clean()

    override fun close() {
        cleanable.clean()
    }
}
