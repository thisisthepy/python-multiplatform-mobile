package python.multiplatform.ref

import android.os.Build
import android.os.Build.VERSION.SDK_INT
import python.native.ffi.NativePointer
import java.lang.ref.Cleaner
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

actual interface PlatformCleaner : AutoCloseable {
    actual override fun close()
}

private val sharedCleaner: Cleaner? =
    if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) Cleaner.create() else null

private val referenceQueue = ReferenceQueue<PlatformCleaner>()
private val activeReferences = ConcurrentHashMap<PhantomCleanupReference, () -> Unit>()

private val cleanerThread = Thread {
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

actual fun registerCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
): PlatformCleaner {
    return AndroidCleaner(pointer, closeAction)
}

private class AndroidCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
) : PlatformCleaner {
    private val cleanable: Cleaner.Cleanable?
    private var phantomRef: PhantomCleanupReference?
    private val api26CloseAction: (() -> Unit)?

    init {
        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cleanable = sharedCleaner?.register(this, CleanupAction(pointer, closeAction))
            phantomRef = null
            api26CloseAction = null
        } else {
            cleanable = null
            val flag = AtomicBoolean(false)
            val actionBlock = {
                if (flag.compareAndSet(false, true)) {
                    closeAction(pointer)
                }
            }
            api26CloseAction = actionBlock
            phantomRef = PhantomCleanupReference(this, referenceQueue).also { ref ->
                activeReferences[ref] = actionBlock
            }
        }
    }

    override fun close() {
        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cleanable?.clean()
        } else {
            val ref = phantomRef
            if (ref != null) {
                val actionBlock = activeReferences.remove(ref)
                actionBlock?.invoke()
                api26CloseAction?.invoke()
                phantomRef = null
            } else {
                api26CloseAction?.invoke()
            }
        }
    }
}

private class CleanupAction(
    private val pointer: NativePointer,
    private val action: (NativePointer) -> Unit
) : Runnable {
    override fun run() {
        action(pointer)
    }
}

private class PhantomCleanupReference(
    referent: PlatformCleaner,
    queue: ReferenceQueue<PlatformCleaner>
): PhantomReference<PlatformCleaner>(referent, queue)
