package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Red-phase functional tests for the conversion layer (`ConversionStrategy`,
 * `PyContext`, `PyValue`).
 *
 * [PyValue] itself is a thin, already-functional holder (it just stores
 * whatever was handed to its constructor), so the "already populated" case
 * genuinely passes; everything that requires *deriving* a conversion from a
 * live [python.multiplatform.ffi.PyObject] (via [PyContext]) is `TODO` and
 * is expected to fail.
 */
class ConversionTest {

    @Test
    fun conversionStrategyHasTheFiveSketchedVariants() {
        val names = ConversionStrategy.entries.map { it.name }.toSet()
        assertEquals(setOf("DEFAULT", "UNMANAGED", "RAW", "TYPED", "NATIVE"), names)
    }

    @Test
    fun pyValueReturnsAPreSuppliedNativeValueWithoutTouchingPython() {
        val obj = PythonTestFixture.eval("123") // still requires a live interpreter to build a PyObject to hold
        val value = PyValue(obj, initialNativeValue = 123L)
        assertEquals(123L, value.asNative())
    }

    @Test
    fun pyValueThrowsWhenNoNativeValueWasEverComputed() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("123")
        val value = PyValue<Long>(obj)
        // No native value was supplied and PyProxy's lazy conversion is not implemented yet.
        assertFailsWith<Throwable> {
            value.asNative()
        }
    }

    @Test
    fun contextConvertValueConvertsAccordingToActiveStrategy() = PythonTestFixture.withInterpreter {
        val context = PyContext(ConversionStrategy.NATIVE)
        val obj = PythonTestFixture.eval("42")
        context.convertValue(obj)
    }

    @Test
    fun withContextTemporarilySwitchesStrategy() = PythonTestFixture.withInterpreter {
        val context = PyContext(ConversionStrategy.DEFAULT)
        context.withContext(ConversionStrategy.RAW) {
            assertEquals(ConversionStrategy.RAW, context.activeStrategy)
        }
        assertEquals(ConversionStrategy.DEFAULT, context.activeStrategy)
    }

    @Test
    fun rawAndNativeStrategiesProduceDifferentlyShapedResults() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val raw = PyContext(ConversionStrategy.RAW).convertValue(obj)
        val native = PyContext(ConversionStrategy.NATIVE).convertValue(obj)
        // Once implemented: RAW should hand back a PyObject/pointer-ish value, NATIVE a Kotlin List.
        assertNotEquals(raw, native)
    }
}
