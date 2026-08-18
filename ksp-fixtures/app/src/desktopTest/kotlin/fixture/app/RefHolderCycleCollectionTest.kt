package fixture.app

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.UpcallStub
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROADMAP §7's last open item, proved against a *real* KSP-exposed class rather than the
 * hand-built proxy `CycleCollectionTest` (python-multiplatform desktopTest) constructs through
 * direct C-API calls.
 *
 * `fixture.library.RefHolder(var primary: PyObject?, var secondary: PyObject?)` was written
 * specifically to exercise the generator's `tp_traverse` field-detection path
 * (`GeneratedTableTest.aClassWithPyObjectFieldsGetsAGeneratedTraverseThatVisitsTheirRawPointers`
 * proves the generator's half). What that test does not prove is whether the *rendered proxy
 * class* -- `PythonProxySource`'s `class RefHolder(_PmObject):` -- ever lets CPython's collector
 * reach that traverse function at all. It does not: `_PmObject` is a plain Python class with no
 * `tp_traverse` override, so a cycle that closes only through the Kotlin object's own `primary`
 * field (as opposed to through a Python-visible attribute) is invisible to `gc.collect()` and
 * leaks forever. `ProxyHandleLifetimeTest.aProxyInsideAReferenceCycleStillGivesItsHandleBackWhen...`
 * does not catch this: its cycle (`c.box -> box -> c`) closes entirely through ordinary Python
 * attributes that `subtype_traverse` already walks, so it passes today without exercising the gap
 * at all.
 *
 * This test builds the cycle the other one cannot: `_rh.primary = [_rh]`, a reference back to
 * `_rh` stored inside a plain Python list that only the *Kotlin* object's own field points at.
 * The edge `list -> _rh` is ordinary and every collector already walks it; the edge that decides
 * this test is `_rh -> (opaque handle) -> Kotlin field -> list`, which no Python-visible attribute
 * carries. (A direct `_rh.primary = _rh` was tried first and hit a different, pre-existing bug:
 * `_pm_unwrap` unwraps *any* `_PmObject`-derived argument to its raw handle before a call, which
 * is right for an owned-result argument but wrong for a `PyObject`-typed parameter -- passing `_rh`
 * itself resolved to the underlying Kotlin object and failed the property's own cast. That bug is
 * independent of this one and is not fixed here; wrapping the back-reference in a plain `list`
 * avoids it entirely, since a `list` is never an `_PmObject` and so is never unwrapped.)
 */
class RefHolderCycleCollectionTest {

    @BeforeTest
    fun install() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        HandleTable.releaseAll()
        UpcallTable.install(FunctionTable.fragments)
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${UpcallStub.invokeWithArgsStubAddr}
            )
            _pm_release = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)(${UpcallStub.releaseObjectStubAddr})
            """.trimIndent(),
        )
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        Python3.exec("import gc\n_rh = None\ngc.collect()")
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    private fun settledBaseline(): Int {
        Python3.exec("import gc\n_rh = None\ngc.collect()")
        return HandleTable.liveCount
    }

    @Test
    fun aSelfCycleThroughARealKspExposedClassIsCollected() {
        assertTrue(
            ClassLookup.require("fixture.library.RefHolder").hasTraverse,
            "premise: RefHolder must carry a generated traverse function or this proves nothing",
        )

        val baseline = settledBaseline()

        // The cycle: _rh (a Python proxy) -> its own _pm_handle -> the Kotlin RefHolder object ->
        // .primary (a PyObject field) -> a plain list -> back to _rh. Nothing here is reachable
        // from _rh through an ordinary Python attribute -- the only edge back to _rh's Kotlin
        // object lives on the Kotlin heap, invisible to a traverse that does not reach it.
        Python3.exec(
            """
            from fixture.library import RefHolder
            _rh = RefHolder(None, None)
            _rh.primary = [_rh]
            """.trimIndent(),
        )
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "constructing one RefHolder must root exactly one Kotlin object",
        )

        Python3.exec("_rh = None\nimport gc\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "a self-cycle closing through the Kotlin object's own PyObject field must be collected " +
                "by CPython's cyclic collector, not leaked forever",
        )
    }

    /** Negative control: no cycle, so refcounting alone -- not tp_traverse -- must release it. */
    @Test
    fun aNonCyclicRefHolderIsReleasedByOrdinaryRefcountingAlone() {
        val baseline = settledBaseline()

        Python3.exec(
            """
            from fixture.library import RefHolder
            _rh = RefHolder(None, None)
            """.trimIndent(),
        )
        assertEquals(baseline + 1, HandleTable.liveCount)

        // No gc.collect() here on purpose: if this needed the collector to run, the cycle-closing
        // test above would prove nothing about the ordinary path being intact.
        Python3.exec("_rh = None")
        assertEquals(
            baseline, HandleTable.liveCount,
            "an object with no cycle must be released by refcounting alone",
        )
    }

    /** Negative control: an unregistered class's proxy is unaffected -- nothing here should ever
     * touch the ordinary (non-`hasTraverse`) proxy shape. */
    @Test
    fun anOrdinaryClassWithNoPyObjectFieldsKeepsRenderingOnTheOldOwner() {
        val source = PythonProxySource.render(UpcallTable.entries(), ClassLookup.all())
        assertTrue(
            source.contains("class Counter(_PmObject):"),
            "Counter has no PyObject-typed field, so hasTraverse is false and it must keep the " +
                "plain owner -- changing this would widen the blast radius far past RefHolder:\n$source",
        )
    }
}
