package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.generated.FunctionTable

/**
 * One line, repeated once per iOS target, because KSP writes `FunctionTable` into the leaf
 * target's own source set and `iosMain` -- which the leaves depend on -- cannot see it. See
 * `UpcallDemo.ios.kt`.
 */
actual fun installGeneratedUpcallTable() {
    FunctionTable.installInto()
}
