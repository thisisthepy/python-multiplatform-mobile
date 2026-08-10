package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Red-phase functional tests for [PyModule]'s metadata accessors
 * (`name`/`doc`/`dict`). Attribute access itself (inherited from
 * [python.multiplatform.ffi.PyObject]) already works; `name`/`doc`/`file`/`dict`
 * are new `TODO` stubs.
 */
class PyModuleTest {

    @Test
    fun nameMatchesTheImportedModuleName() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        assertEquals("math", math.name)
    }

    @Test
    fun getDelegatesToAttributeLookup() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        assertEquals(math.getAttr("pi").toString(), math.get("pi").toString())
    }

    @Test
    fun dictExposesTheModuleNamespace() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        val piFromDict = math.dict[PythonTestFixture.eval("'pi'")]
        assertEquals(math.getAttr("pi").toString(), piFromDict?.toString())
    }

    @Test
    fun docCanBeNullForUndocumentedModules() = PythonTestFixture.withInterpreter {
        Python3.exec("import types\n_undocumented_module_for_test = types.ModuleType('_undocumented_module_for_test')")
        val module = PythonTestFixture.eval("_undocumented_module_for_test")
        val wrapped = PyModule(module.pointer, false)
        assertNull(wrapped.doc)
    }
}
