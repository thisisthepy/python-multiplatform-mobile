package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.callKotlinFromPython
import org.thisisthepy.python.multiplatform.demo.bindings.installGeneratedUpcallTable
import org.thisisthepy.python.multiplatform.demo.bindings.upcallTableSummary

/**
 * The table is generated and installed here exactly as on desktop -- same processor, discovered
 * through a `.klib`. The call itself is made from Kotlin, because the Python-side boundary shim
 * is desktop-only; `bindings/UpcallDemo.ios.kt` next door says what is missing.
 */
actual object UpcallDemo {
    actual val available: Boolean = true
    actual val entryName: String = UPCALL_ENTRY_NAME
    actual fun install() = installGeneratedUpcallTable()
    actual fun press() = DemoCounter.press()
    actual fun callFromPython(): String = callKotlinFromPython()
    actual fun tableSummary(): String = upcallTableSummary()
    actual fun optOutHeld(): Boolean = org.thisisthepy.python.multiplatform.demo.bindings.optOutHeld()
}
