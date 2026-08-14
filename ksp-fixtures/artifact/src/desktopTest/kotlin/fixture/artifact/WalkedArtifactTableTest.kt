package fixture.artifact

import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the artefact walker put in the table, checked against the jar it was walked from.
 *
 * The fixed set is `ArtifactScannerTest`'s, arrived at from the other end: that test walks the jar
 * directly in a plugin unit test, this one reads what the *build* produced, compiled and installed.
 * If they ever disagree, the wiring between them is what broke.
 */
class WalkedArtifactTableTest {

    @BeforeTest
    fun startFromAnEmptyTable() {
        UpcallTable.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun theWalkerBoundExactlyTheJarsDeclarationsItCouldCarry() {
        ArtifactTable.registerInto()
        assertEquals(
            listOf(
                "junit.framework.Assert.failSame",
                "junit.framework.TestCase.failSame",
                "junit.runner.BaseTestRunner.getFilteredTrace",
                "junit.runner.BaseTestRunner.savePreferences",
                "junit.runner.BaseTestRunner.setPreference",
                "junit.runner.BaseTestRunner.truncate",
                "junit.runner.Version.id",
            ),
            UpcallTable.entries().map { it.name }.sorted(),
        )
    }

    /**
     * The call actually reaches the jar.
     *
     * `"4.13.2"` is JUnit 4's own version string, compiled into `junit/runner/Version.class`. No
     * generator, no table and no test fixture can produce it -- only the artefact can, which is why
     * this is the one declaration the Python-side test reads back too.
     */
    @Test
    fun aBoundDeclarationCallsIntoTheArtefact() {
        ArtifactTable.registerInto()
        val id = UpcallTable.resolve("junit.runner.Version.id")
        assertTrue(id.isValid, "the walker's entry is not in the table")
        assertEquals("4.13.2", UpcallTable.invoke(id, emptyArray()))
    }

    /**
     * The join: two aggregators, one table.
     *
     * `FunctionTable` is what KSP built out of this module's own source; `ArtifactTable` is what the
     * walker built out of the jars the build resolved. They are separate lists on purpose -- see
     * `ArtifactTableRenderingTest` -- and the install site is where they meet.
     */
    @Test
    fun bothProducersLandInOneTable() {
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)

        assertTrue(
            UpcallTable.resolve("fixture.artifact.whichSideAmIFrom").isValid,
            "KSP's half is missing from the joint install",
        )
        assertTrue(
            UpcallTable.resolve("junit.runner.Version.id").isValid,
            "the walker's half is missing from the joint install",
        )
        assertEquals("ksp", UpcallTable.invoke(UpcallTable.resolve("fixture.artifact.whichSideAmIFrom"), emptyArray()))
        assertEquals("4.13.2", UpcallTable.invoke(UpcallTable.resolve("junit.runner.Version.id"), emptyArray()))
    }

    /**
     * `FunctionTable` still means what it meant.
     *
     * The alternative design put walked fragments into KSP's own aggregator. This is the assertion
     * that would have had to change for it, and the reason it was not chosen: a consumer's table
     * must not grow because they added a dependency.
     */
    @Test
    fun theKspAggregatorDoesNotAbsorbWalkedArtefacts() {
        UpcallTable.install(FunctionTable.fragments)
        assertTrue(UpcallTable.resolve("fixture.artifact.whichSideAmIFrom").isValid)
        assertFalse(
            UpcallTable.resolve("junit.runner.Version.id").isValid,
            "FunctionTable absorbed a walked artefact; the two producers are supposed to keep separate lists",
        )
    }

    /** `registerInto` adds; it does not reinstall. A second call must not double the table, which is
     * `UpcallTable.register`'s own `moduleName` guard doing the work. */
    @Test
    fun registeringTwiceIsANoOp() {
        ArtifactTable.registerInto()
        val once = UpcallTable.callableCount
        ArtifactTable.registerInto()
        assertEquals(once, UpcallTable.callableCount)
    }
}
