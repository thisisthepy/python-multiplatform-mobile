package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.UpcallStub
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole path, end to end, in one interpreter: a jar the build resolved -> ASM -> a generated
 * `FunctionTableFragment` -> `UpcallTable` -> `PythonProxySource` -> `from junit.runner.Version
 * import id`.
 *
 * `docs/ecosystem.md` §5b settles that the Python surface is an *import statement*, not a resolve
 * call, and that a Kotlin fully-qualified name means the original code rather than a wrapper of ours
 * wearing its name. This is that claim, for a third-party binary artefact: `junit.runner.Version` is
 * JUnit's own package path, and `id()` answers `"4.13.2"` -- a string that exists nowhere except
 * inside `junit/runner/Version.class`.
 *
 * The two names the generated proxies need (`_pm_resolve`, `_pm_invoke`) are bound the way
 * `python-multiplatform`'s own desktop tests bind them: `ctypes.CFUNCTYPE` over the Panama upcall
 * stubs `UpcallStub` already builds. That part is per-platform and is not what this test is about;
 * it is here because a consumer module has no other route to it today (see ROADMAP §16).
 */
class WalkedArtifactPythonImportTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${UpcallStub.invokeWithArgsStubAddr}
            )
            """.trimIndent(),
        )
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The import statement, and the value it returns.
     *
     * Asserted inside Python rather than marshalled back out: `Python3.exec` raises a Kotlin
     * `PyException` carrying the Python error, so a failed `assert` here fails the test with the
     * Python-side message intact -- and the check then costs no boundary crossing of its own that
     * could be the thing that actually worked.
     */
    @Test
    fun aWalkedArtefactIsImportableByItsOwnPackagePath() {
        Python3.exec(
            """
            from junit.runner.Version import id

            _observed = id()
            assert _observed == '4.13.2', 'junit.runner.Version.id() answered ' + repr(_observed)
            """.trimIndent(),
        )
    }

    /** Arguments cross too, and come back: `truncate` is JUnit's own string shortener, so a result
     * that ends in `"..."` could only have come from the jar. */
    @Test
    fun aWalkedDeclarationTakesArgumentsAndReturnsAValue() {
        Python3.exec(
            """
            from junit.runner.BaseTestRunner import truncate

            _observed = truncate('x' * 2000)
            assert _observed.endswith('...'), 'truncate answered ' + repr(_observed[-20:])
            assert len(_observed) < 2000, 'truncate did not truncate: ' + str(len(_observed))
            """.trimIndent(),
        )
    }

    /**
     * Both producers answer in one interpreter, under the names their own languages give them.
     *
     * `fixture.artifact` is this module's Kotlin package, reached through KSP's `FunctionTable`;
     * `junit.runner.Version` is a jar's package, reached through the walker's `ArtifactTable`.
     * Nothing in the Python source distinguishes them, which is the point of them sharing one
     * `UpcallTable`.
     */
    @Test
    fun kspAndTheWalkerShareOnePythonNamespace() {
        Python3.exec(
            """
            from fixture.artifact import whichSideAmIFrom
            from junit.runner.Version import id

            assert whichSideAmIFrom() == 'ksp'
            assert id() == '4.13.2'
            """.trimIndent(),
        )
    }

    /** The module object exists under the jar's own dotted path, not only the leaf name -- which is
     * what makes `import junit.runner.Version` work as well as `from ... import ...`. */
    @Test
    fun theJarsPackagePathIsARealModuleTree() {
        Python3.exec(
            """
            import sys

            assert 'junit.runner.Version' in sys.modules
            assert 'junit.runner' in sys.modules
            assert 'junit' in sys.modules
            assert sys.modules['junit'].runner.Version.id() == '4.13.2'
            """.trimIndent(),
        )
    }

    /**
     * `kotlin.text.trimIndent`: a top-level Kotlin extension function, reached through a
     * `MULTI_FILE_CLASS_FACADE` (`StringsKt`) into a package-private part (`StringsKt__IndentKt`) --
     * exactly the shape `ArtifactScanner`'s KDoc names as unreachable before it read `@Metadata`.
     * Called under the name `kotlin.text.trimIndent`, never under either JVM class name, which
     * Kotlin (and so Python) has no way to spell.
     */
    @Test
    fun aTopLevelExtensionBehindAMultiFileFacadeIsCallableFromPython() {
        Python3.exec(
            """
            from kotlin.text import trimIndent

            _observed = trimIndent('    line one\n    line two')
            assert _observed == 'line one\nline two', 'trimIndent answered ' + repr(_observed)
            """.trimIndent(),
        )
    }

    /**
     * `fixture.valueclass.sumMeters`: both parameters and the return are a public
     * `@JvmInline value class` (`Meters`), from `:ksp-fixtures:artifact-valueclass` -- a jar built
     * for exactly this proof, since `kotlin.time.Duration` (the real-world case) has an `internal`
     * constructor and so can never round-trip. `12.0` is not producible except by unwrapping two
     * `Meters` arguments, adding them, and wrapping the sum back up.
     */
    @Test
    fun aValueClassParameterAndReturnRoundTripThroughPython() {
        Python3.exec(
            """
            from fixture.valueclass import sumMeters

            _observed = sumMeters(5.0, 7.0)
            assert _observed == 12.0, 'sumMeters answered ' + repr(_observed)
            """.trimIndent(),
        )
    }

    /** A declined declaration going out of scope must fail the same way any other assertion does,
     * not "unexpectedly pass" because the negative case was never actually reached. */
    @Test
    fun theWholeStringsKtFacadeItselfIsUnreachable() {
        assertEquals(
            false,
            UpcallTable.resolve("kotlin.text.StringsKt.trimIndent").isValid,
            "the facade's own JVM name has no Kotlin spelling and must never be a bound name",
        )
    }

    /** A declaration the walker declined is absent rather than broken: `assertEquals` has eight
     * bindable overloads and the walker refuses to pick one (see `ArtifactScannerTest`). */
    @Test
    fun aDeclinedDeclarationIsSimplyNotThere() {
        assertEquals(
            false,
            UpcallTable.resolve("org.junit.Assert.assertEquals").isValid,
            "an ambiguous overload must not be silently bound to one arbitrary body",
        )
    }
}
