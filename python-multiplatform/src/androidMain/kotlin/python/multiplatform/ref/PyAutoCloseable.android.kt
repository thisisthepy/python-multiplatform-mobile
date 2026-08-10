package python.multiplatform.ref

import android.os.Build
import android.os.Build.VERSION.SDK_INT
import python.multiplatform.currentPlatform
import python.native.ffi.NativePointer
import java.lang.ref.Cleaner
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap


actual abstract class PyAutoCloseable actual constructor(pointer: NativePointer): AutoCloseable {
    private val cleanable: Cleaner.Cleanable?
    private var phantomRef: PhantomCleanupReference?

    companion object {
        /**
         * One Cleaner for the whole process, on the API levels that have Cleaner at all.
         *
         * Cleaner.create() starts a dedicated OS thread. Calling it once per instance --
         * as this class used to -- spawns a thread per PyObject and exhausts the process
         * thread limit ("OutOfMemoryError: unable to create native thread") after a few
         * thousand objects. Registering with an existing Cleaner is cheap; creating one
         * is not. The same bug was found and fixed on desktop.
         */
        private val sharedCleaner: Cleaner? =
            if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) Cleaner.create() else null

        private val referenceQueue = ReferenceQueue<PyAutoCloseable>()
        // TODO: activeReferences가 계속 커질 위험이 있음
        // TODO: 성능 오버헤드: 모든 객체마다 HashMap 엔트리 생성
        // TODO: 여러 이유로 TIRAMISU 미만 버전에서는 이 방법 말고 다른 방법으로 객체 소멸을 감지해야할 듯
        private val activeReferences = ConcurrentHashMap<PhantomCleanupReference, () -> Unit>()

        init {
            Thread {
                while (true) {
                    try {
                        val ref = referenceQueue.remove() as? PhantomCleanupReference
                        ref?.let {
                            activeReferences.remove(it)?.invoke()
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }.apply {
                isDaemon = true
                name = "PyAutoCloseable-Cleaner"
            }.start()
        }
    }

    init {
        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cleanable = sharedCleaner?.register(this) {
                clean()
            }
            phantomRef = null
        } else {
            cleanable = null
            phantomRef = PhantomCleanupReference(this, referenceQueue).also {
                ref -> activeReferences[ref] = { clean() }
            }
        }
    }

    actual abstract fun clean()

    override fun close() {
        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cleanable?.clean()
        } else {
            phantomRef?.let {
                ref -> activeReferences.remove(ref)
                clean()
            }
            phantomRef = null
        }
    }

    private class PhantomCleanupReference(
        referent: PyAutoCloseable,
        queue: ReferenceQueue<PyAutoCloseable>
    ): PhantomReference<PyAutoCloseable>(referent, queue)
}
