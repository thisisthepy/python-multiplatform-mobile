package fixture.library

import python.multiplatform.ffi.PyObject
import python.multiplatform.reflection.PythonInternal

fun add(a: Long, b: Long): Long = a + b

fun greet(name: String): String = "Hello, $name!"

fun scale(value: Int, by: Int): Int = value * by

fun noArgs() {
    // exercises the Unit-return path
}

@PythonInternal
fun hiddenFromPython(): String = "must not appear in the generated table"

class Counter(var count: Long = 0) {
    fun increment(by: Long): Long {
        count += by
        return count
    }

    fun label(prefix: String): String = "$prefix$count"

    @PythonInternal
    fun hiddenMethod(): Long = -1L
}

/** Holds Python references directly, the way a real exposed class would -- exercises the
 * generator's `tp_traverse` field-detection path (docs/object-lifetime.md). */
class RefHolder(var primary: PyObject?, var secondary: PyObject?)

@PythonInternal
class HiddenClass {
    fun shouldNeverBeReachable(): Int = 0
}
