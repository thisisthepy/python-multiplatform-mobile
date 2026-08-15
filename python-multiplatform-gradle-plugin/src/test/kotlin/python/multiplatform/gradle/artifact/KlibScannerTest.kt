package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The klib counterpart of `ArtifactScannerTest`, against two real klibs: `kotlin-stdlib` (bundled
 * with the Kotlin/Native distribution this build's `.konan` cache already stages, so no extra
 * download) and `kotlinx-coroutines-core-androidnativearm64:1.8.1` (a real download, the klib
 * counterpart of `ArtifactScannerTest`'s `junit:junit:4.13.2`).
 *
 * ROADMAP §16e: reading a klib is the *easier* half of the two producers, not the harder one --
 * `LibraryAbiReader` gives exact Kotlin types directly, with no facade problem to work around. What
 * still needs proving is that this walker matches on the right thing and disambiguates the same way
 * the JVM walker does; both real klibs below are chosen to exercise exactly that.
 */
class KlibScannerTest {

    /**
     * `kotlin-stdlib`'s own klib, staged by the Kotlin/Native distribution this build already pins
     * (`kotlin-compiler-embeddable:2.0.20` here; the `.konan` prebuilt toolchain is the matching
     * version). `~/.konan` is this workspace's own symlink onto external storage -- CLAUDE.md's
     * "캐시 ... 홈에서 심볼릭 링크" -- so this path is host-relative, not hardcoded to a volume.
     *
     * Guarded rather than asserted: a machine that has never run a Kotlin/Native build has no
     * `.konan` cache yet, and that is a setup gap this test should report plainly rather than fail
     * opaquely on. See each test's own early return.
     */
    private val konanStdlibKlib: File
        get() = File(
            System.getProperty("user.home") +
                "/.konan/kotlin-native-prebuilt-macos-aarch64-2.0.20/klib/common/stdlib",
        )

    private val coroutinesKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureKlib")
            assertNotNull(path, "the walkerFixtureKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    /**
     * The bug this pins: `AbiQualifiedName.toString()` joins package and relative name with `/`
     * (`kotlin.math/abs`), not `.`. An earlier version of [KlibScanner.matchesInclude] matched the
     * *full* qualified name against `"kotlin.math."` (the JVM producer's dotted-class rule, reused
     * unchanged) -- which a declaration directly inside the `kotlin.math` package can never satisfy,
     * because the character after the prefix is `/`. Every one of `kotlin.math`'s bindable
     * declarations is directly in that package (there is no `kotlin.math.trig` sub-package), so the
     * old rule bound **zero** of them; matching on `packageName` alone (this test) is what makes the
     * 68 below reachable at all.
     */
    @Test
    fun aPackageAnIncludePrefixNamesExactlyIsMatchedNotOnlyItsSubPackages() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val entries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
        assertTrue(entries.isNotEmpty(), "kotlin.math yielded nothing; the package-prefix match regressed")
        assertEquals(68, entries.size)
    }

    /**
     * `kotlin.math.max`/`min` are each declared four times (`Int`, `Long`, `Float`, `Double`), and a
     * klib's ABI carries the exact Kotlin type for each -- the same fact `@Metadata` gives the JVM
     * producer, so [ArtifactScanner.disambiguateOverloads]'s `name__<types>` rule applies unchanged
     * (this test calls the *shared* function, not a klib-local copy). An earlier version of
     * [KlibScanner] grouped by bare name and kept only groups of size one, silently dropping `max`,
     * `min` and every other overloaded `kotlin.math` name; suffixing instead of dropping is what
     * [ArtifactScannerTest.theWholeJUnitJarYieldsExactlyTheseUnsuffixedDeclarations]'s KDoc calls "a
     * third option between arbitrate and drop", applied here to a klib for the first time.
     */
    @Test
    fun overloadedNamesAreSuffixedNotDropped() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val names = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math")).map { it.name }
        assertEquals(
            listOf("kotlin.math.max__Double_Double", "kotlin.math.max__Float_Float", "kotlin.math.max__Int_Int", "kotlin.math.max__Long_Long"),
            names.filter { it.startsWith("kotlin.math.max__") }.sorted(),
        )
        // The bare name must be absent -- exactly one binding may ever answer to it, and here there
        // are four, none of which this walker may pick for the others.
        assertTrue("kotlin.math.max" !in names)
        assertTrue("kotlin.math.min" !in names)
    }

    /** One entry, its tags and its call, pinned the same way
     * [ArtifactScannerTest.anEntryCarriesTheTagsAndTheCallItsDeclarationImplies] pins a JVM one. */
    @Test
    fun anEntryCarriesTheTagsItsDeclarationImplies() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val entries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
        val absInt = entries.single { it.name == "kotlin.math.abs__Int" }
        assertEquals(1, absInt.arity)
        assertEquals(listOf("INT"), absInt.paramTags)
        assertEquals("INT", absInt.returnTag)
        assertEquals(listOf("kotlin.Int"), absInt.paramTypeNames)
    }

    /**
     * A real, separately-published klib -- the klib counterpart of `ArtifactScannerTest`'s JUnit jar
     * -- scanned with no include filter, over all ~400 of its top-level declarations. Proves the
     * walker does not crash on a large, diverse artefact: `suspend`, extension functions, value
     * classes and `@PublishedApi internal` declarations are all present in `kotlinx-coroutines-core`
     * and must all be *declined*, not mishandled.
     *
     * **Zero bindable, and that is the correct number, not a regression.** An earlier version of this
     * test pinned one -- `checkIndexOverflow`, `kotlinx.coroutines.flow.internal`'s bounds check -- as
     * bound. It was wrong: `checkIndexOverflow` is `@PublishedApi internal`, binary-visible so an
     * inline call site elsewhere in the *same* library can resolve it, but not source-visible outside
     * that module. `KlibScanWorkAction`'s KDoc records how this was found -- `:ksp-fixtures
     * :klib-artifact:compileKotlinAndroidNativeArm64` failing to compile the generated fragment with
     * "it is internal in file", the first time this walker's output was ever actually compiled rather
     * than only unit-tested. `KlibScanner`'s `PUBLISHED_API` constant has the rest. Every other candidate in
     * this klib's ~400 top-level declarations is an extension function (declined for the reason
     * [aDeclinedDeclarationIsRecordedWithItsReasonRatherThanVanishing] pins) or `suspend` -- coroutines'
     * entire public surface operates on `CoroutineScope`/`Job`/`Flow` through extensions, and none of
     * those are bindable boundary types today. `kotlin.math` above is the richer case; this one is the
     * "does it survive a real, messy artefact, and decline what it must decline" case.
     */
    @Test
    fun aRealThirdPartyKlibIsScannedWithoutCrashingAndDeclinesEveryDeclaration() {
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = emptyList())
        assertEquals(emptyList(), entries.map { it.name })
    }

    /** The specific bug [aRealThirdPartyKlibIsScannedWithoutCrashingAndDeclinesEveryDeclaration] pins
     * the fix for, isolated to one declaration and its reason. */
    @Test
    fun aPublishedApiInternalDeclarationIsDeclinedWithItsOwnReason() {
        val declarations = KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = emptyList())
        val checkIndexOverflow = declarations.single { it.simpleName == "checkIndexOverflow" }
        assertEquals(null, checkIndexOverflow.bindingName)
        assertTrue(
            checkIndexOverflow.declineReason?.contains("PublishedApi") == true,
            "expected a @PublishedApi decline reason, got ${checkIndexOverflow.declineReason}",
        )
    }

    /**
     * A namespace include prefix means "this package or a sub-package of it", not "a text prefix of
     * the package string". `kotlin.collections`, `kotlin.concurrent` and `kotlin.comparisons` are all
     * real `kotlin-stdlib` packages that literally start with the seven characters `kotlin.co` --
     * proof that [KlibScanner.matchesInclude] checks a `.`-bounded namespace and not
     * `String.startsWith` on its own, using packages that actually exist rather than an invented
     * negative case.
     */
    @Test
    fun anIncludePrefixIsANamespaceNotATextPrefix() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val entries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.co"))
        assertEquals(emptyList(), entries, "\"kotlin.co\" is a text prefix of real packages but not their namespace")
    }

    // ------------------------------------------------------------------- the declaration model half

    /**
     * `docs/pyi-generation-design.md` §2.2's "what is declined stays visible", for the klib producer.
     *
     * Before this, [KlibScanner] dropped an extension, a `suspend` or an unbindable-typed declaration
     * at a `filter`, so nothing downstream could say it had ever been seen. `kotlin.math` is the case
     * that makes this concrete: `Double.pow`, `Double.withSign` and the rest of its extension surface
     * are the *majority* of the package, and a stub product that simply omitted them would read as if
     * `kotlin.math` were 68 functions wide.
     */
    @Test
    fun aDeclinedDeclarationIsRecordedWithItsReasonRatherThanVanishing() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val declarations = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
        val bound = declarations.filter { it.bindingName != null }
        val declined = declarations.filter { it.bindingName == null }

        assertEquals(68, bound.size, "the bound half must still be exactly what scanKlib returns")
        assertTrue(declined.isNotEmpty(), "kotlin.math's extensions must be recorded, not dropped")
        assertTrue(
            declined.all { it.declineReason != null },
            "a declined declaration with no reason is the silent drop this replaces",
        )
        // The one klib cannot answer and the JVM producer can: the receiver's own type.
        val pow = declined.filter { it.simpleName == "pow" }
        assertTrue(pow.isNotEmpty(), "kotlin.math.pow is an extension on Double and must appear declined")
        assertTrue(
            pow.all { it.declineReason!!.contains("extension receiver") },
            "expected an extension-receiver reason, got ${pow.map { it.declineReason }}",
        )
        assertTrue(pow.all { it.receiver == null }, "klib cannot name the receiver, so it must not claim one")
    }

    /** The two halves come out of one walk, exactly as `DeclarationModelTest
     * .theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk` pins for the JVM producer. */
    @Test
    fun theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val bound = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math")).map { it.name }
        val modelled = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            .mapNotNull { it.bindingName }
        assertEquals(bound.sorted(), modelled.sorted())
    }

    /**
     * The overload rule is *one* implementation shared with the JVM producer, and the model sees its
     * result: `kotlin.math.max`'s four members reach the table under `max__Int_Int` and friends, and
     * the [python.multiplatform.gradle.model.DeclarationModel] for each carries that suffixed key as
     * its `bindingName` while keeping `max` as its `simpleName`. A stub generator needs both -- the
     * key to name the attribute, the simple name to say what Kotlin called it.
     */
    @Test
    fun theSharedOverloadRuleReachesTheModelsBindingName() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val max = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            .filter { it.simpleName == "max" && it.bindingName != null }
        assertEquals(
            listOf(
                "kotlin.math.max__Double_Double",
                "kotlin.math.max__Float_Float",
                "kotlin.math.max__Int_Int",
                "kotlin.math.max__Long_Long",
            ),
            max.map { it.bindingName!! }.sorted(),
        )
        assertTrue(max.all { it.simpleName == "max" }, "the Kotlin name must survive the table key")
    }

    /**
     * §3.2's rule, reached for a third reason. A klib's `AbiValueParameter` has a `type`, a
     * `hasDefaultArg` and no **name** -- `kotlin-compiler-embeddable` 2.0.20's interface declares
     * exactly five members and none of them is one (checked with `javap`, not assumed). So every klib
     * declaration is positional-only, the same as a Java jar compiled without `-parameters`, and
     * `parameterNamesKnown` is what says so rather than a synthesised `arg0`.
     */
    @Test
    fun aKlibSuppliesTypesAndDefaultsButNoParameterNames() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val absInt = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            .single { it.bindingName == "kotlin.math.abs__Int" }
        assertEquals(false, absInt.parameterNamesKnown)
        assertEquals(listOf<String?>(null), absInt.parameters.map { it.name })
        assertEquals(listOf("kotlin.Int"), absInt.parameters.map { it.type.qualifiedName })
        assertEquals(listOf("INT"), absInt.parameters.map { it.boundaryTag })
        assertEquals("kotlin.Int", absInt.returnType.qualifiedName)
        assertEquals("INT", absInt.returnBoundaryTag)
        assertEquals("kotlin.math", absInt.owner)
        assertEquals(false, absInt.ownerIsClass)
    }

    /**
     * A nullable primitive is declined, the same rule `resolveKotlinType` applies to the JVM
     * producer's `Int?` -- and for the same reason: `TypeTag.INT` carries a `Long` and the generated
     * read is `(args[0] as Long).toInt()`, so a Python `None` would fail inside the cast rather than
     * reach the declaration.
     *
     * This is not a rule the klib walker used to have. `AbiType.Simple.nullability` exists on 2.0.20
     * and the first version of this file never read it, so `String?` mapped to `STRING` and would have
     * generated `(args[0] as String)` for a parameter that accepts null.
     */
    @Test
    fun aNullablePrimitiveIsDeclinedTheWayTheJvmProducerDeclinesOne() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val declarations = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = NULLABLE_PROBE_PACKAGES)
        val nullablePrimitiveTyped = declarations.filter { declaration ->
            declaration.parameters.any { it.type.isNullable && it.type.qualifiedName in KLIB_PRIMITIVE_NAMES }
        }
        assertTrue(
            nullablePrimitiveTyped.isNotEmpty(),
            "no declaration in $NULLABLE_PROBE_PACKAGES has a nullable primitive parameter; " +
                "this test's fixture assumption is wrong, not the rule",
        )
        assertTrue(
            nullablePrimitiveTyped.all { it.bindingName == null },
            "bound with a nullable primitive: ${nullablePrimitiveTyped.filter { it.bindingName != null }.map { it.bindingName }}",
        )
    }

    private companion object {
        /** Packages searched for a real nullable-primitive parameter; see the test that uses them. */
        val NULLABLE_PROBE_PACKAGES = listOf("kotlin.text", "kotlin.collections", "kotlin.io", "kotlin")

        val KLIB_PRIMITIVE_NAMES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
            "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.String", "kotlin.ByteArray",
        )
    }
}
