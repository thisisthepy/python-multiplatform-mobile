package org.thisisthepy.python.multiplatform.demo

import python.multiplatform.currentPlatform
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.collections.PyList

/**
 * Everything the demo screen calls, with no Compose in sight.
 *
 * Sections 1 and 2 are pure `commonMain` against the library's object model -- that is the point
 * of them. Section 3 (upcalls) goes through [UpcallDemo], which is `expect`/`actual` because KSP
 * writes `python.multiplatform.generated.FunctionTable` into each *target's* own compilation, so
 * `commonMain` -- compiled by every target and seeing none of their generated directories --
 * cannot name it.
 */
object PythonDemo {

    /** `Py_eval_input`: compile an *expression* rather than a sequence of statements. */
    private const val PY_EVAL_INPUT: Int = 258

    /** Seeded into `__main__` by [start] so the default expression has something to chew on. */
    private val kotlinNumbers = listOf(2L, 3L, 5L, 7L, 11L)

    /** The expression the screen starts with. */
    const val DEFAULT_EXPRESSION: String = "sum(kotlin_numbers) * 2"

    private var started = false

    /**
     * Brings up the interpreter, publishes a Kotlin-built object into Python, and installs the
     * generated upcall table. Idempotent; the platform entry points differ in when they can call
     * it (Android has to unpack a stdlib first).
     */
    fun start() {
        if (started) return
        Python3.initialize()

        // Kotlin -> Python through the object model rather than through a formatted string: a
        // real `list` of real `int`s, handed to `__main__` as a module attribute so that ordinary
        // Python code sees it as a global.
        //
        // No `close()` anywhere below, and none is missing. ROADMAP §4 is closed: each wrapper
        // registers a cleaner in its constructor, and the collector releases the CPython
        // reference once the Kotlin object becomes unreachable.
        val numbers = PyList.fromList(kotlinNumbers.map { PyInt.from(it) })
        Python3.import("__main__").setAttr("kotlin_numbers", numbers)

        UpcallDemo.install()
        started = true
    }

    /** One line naming what is actually loaded, so a broken bring-up is visible immediately. */
    fun runtimeSummary(): String = buildString {
        append(Python3.version.substringBefore(' '))
        append("  ·  sys.platform=").append(Python3.platform)
        append("  ·  ").append(currentPlatform.name)
    }

    /**
     * Evaluates [expression] against `__main__`'s globals and describes the result *as Python
     * sees it* -- the type name comes from the object's own `PyType`, not from a Kotlin guess.
     */
    fun evaluate(expression: String): String = try {
        val globals = Python3.import("__main__").dict
        val result: PyObject = Python3.eval(expression, PY_EVAL_INPUT, globals, globals)
        "${result.Type.name}: $result"
    } catch (t: Throwable) {
        // A Python-level failure arrives as a PyException carrying the real exception type and
        // message: Python3.exec deliberately avoids PyErr_Print, which would swallow both.
        "${t::class.simpleName}: ${t.message}"
    }
}

/**
 * The upcall half, one `actual` per target because the generated table is per-target.
 *
 * Every member is a one-line delegation on every platform. It was not always: Android's actual
 * used to answer "unavailable" to all of them, because the bindings plugin could not be applied to
 * a module carrying an Android plugin below AGP 8.10 (ROADMAP §13). What still differs by platform
 * is *who makes the call* -- only desktop reaches Kotlin from inside the interpreter.
 */
expect object UpcallDemo {

    /** Whether the generated table is reachable from this target at all. */
    val available: Boolean

    /** The Python-visible name of the entry [callFromPython] resolves. */
    val entryName: String

    fun install()

    /** Bumps the Kotlin counter Python reads back. */
    fun press()

    fun callFromPython(): String

    fun tableSummary(): String

    /** Whether `@PythonInternal` kept an annotated declaration out of the generated table. */
    fun optOutHeld(): Boolean
}
