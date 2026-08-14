package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The artefact walker against two real jars: `junit:junit:4.13.2` (Java) and `kotlin-stdlib`
 * (Kotlin).
 *
 * `docs/ecosystem.md` §5b names two producers of bindings, split by *what they look at* -- KSP sees
 * the consumer's own source, the artefact walker sees everything the build resolves. This is the
 * second one, and a jar the test never compiled is the only honest input for it.
 *
 * Neither jar is on this test's classpath. The JUnit 4 one arrives as a path in a system property
 * (see this build's `walkerFixtureJar` configuration); `kotlin-stdlib` is located through its own
 * `CodeSource`, since it necessarily *is* on the classpath of anything written in Kotlin.
 */
class ArtifactScannerTest {

    private val junitJar: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureJar")
            assertNotNull(path, "the walkerFixtureJar system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    private val kotlinStdlibJar: File
        get() {
            val location = Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isFile, "not a file: $it") }
        }

    /**
     * The fixed text: every declaration the walker takes out of the whole JUnit 4 jar, with no
     * include filter at all.
     *
     * Seven entries out of a 380-class jar, and the smallness is the point -- see
     * [overloadedNamesAreDroppedRatherThanArbitrated] and [JvmDescriptorsTest] for the two filters
     * that produce it. `junit.runner.Version.id` is the one the end-to-end fixture calls from
     * Python, because its answer (`"4.13.2"`) is the jar's own version and so cannot be produced by
     * anything except the jar's own code.
     */
    @Test
    fun theWholeJUnitJarYieldsExactlyTheseDeclarations() {
        val entries = ArtifactScanner.scanJar(junitJar, includePrefixes = emptyList())
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
            entries.map { it.name },
        )
    }

    @Test
    fun anEntryCarriesTheTagsAndTheCallItsDeclarationImplies() {
        val entries = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.runner.Version"))
        assertEquals(1, entries.size)
        val id = entries.single()
        assertEquals("junit.runner.Version.id", id.name)
        assertEquals(0, id.arity)
        assertEquals(emptyList(), id.paramTags)
        assertEquals("STRING", id.returnTag)
        assertEquals("{ (junit.runner.Version.id()) }", id.lambdaBody)

        val setPreference = ArtifactScanner
            .scanJar(junitJar, includePrefixes = listOf("junit.runner.BaseTestRunner"))
            .single { it.name.endsWith(".setPreference") }
        assertEquals(2, setPreference.arity)
        assertEquals(listOf("STRING", "STRING"), setPreference.paramTags)
        assertEquals("UNIT", setPreference.returnTag)
        assertEquals(
            "{ args -> (junit.runner.BaseTestRunner.setPreference((args[0] as String), (args[1] as String))) }",
            setPreference.lambdaBody,
        )
    }

    @Test
    fun anIncludePrefixMatchesAPackageOrAClassButNotAPrefixOfEither() {
        val byPackage = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.runner"))
        assertEquals(
            listOf(
                "junit.runner.BaseTestRunner.getFilteredTrace",
                "junit.runner.BaseTestRunner.savePreferences",
                "junit.runner.BaseTestRunner.setPreference",
                "junit.runner.BaseTestRunner.truncate",
                "junit.runner.Version.id",
            ),
            byPackage.map { it.name },
        )

        // `junit.run` is a prefix of the string `junit.runner` and of nothing else: a package
        // filter that matched it would be matching text rather than a namespace.
        assertEquals(emptyList(), ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.run")).map { it.name })
    }

    /**
     * The one place this deliberately parts company with `PyREPL`'s generator.
     *
     * PyREPL groups a class's methods by name and emits one `def name(self, *args, **kwargs): ...`
     * per group, which is exactly right for a `.pyi` stub: a stub describes a name, and Python has
     * no overloading to describe. A *binding* has to pick a body, and `org.junit.Assert.assertEquals`
     * has eight the walker can bind. Picking one -- even deterministically -- means
     * `assertEquals(3, 3)` from Python silently calls whichever the sort order happened to put
     * first, which for that method is the deprecated `(double, double)` that always fails.
     *
     * `FragmentScanner.distinctByName` keeps the first for Kotlin *source*, and that is not the same
     * decision: the collisions it resolves are duplicate views of one declaration (an instance and a
     * companion property of the same name, an `expect`/`actual` pair seen twice), not eight
     * different functions.
     *
     * Ambiguity is counted over what the walker *would bind*, not over what the class declares:
     * `getFilteredTrace` has two overloads, one taking `Throwable`, and only one of them survives
     * [JvmDescriptorsTest]'s type filter -- so there is nothing to arbitrate and it is kept.
     */
    @Test
    fun overloadedNamesAreDroppedRatherThanArbitrated() {
        val names = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("org.junit")).map { it.name }
        assertEquals(emptyList(), names, "org.junit.Assert and org.junit.Assume are overloads all the way down")

        val runner = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.runner.BaseTestRunner"))
            .map { it.name.substringAfterLast('.') }
        assertTrue("getFilteredTrace" in runner, "one bindable overload is not an ambiguity")
        assertTrue("getPreference" !in runner, "two bindable overloads is")
    }

    /**
     * What a Kotlin jar's `@Metadata` says, read straight off the real `kotlin-stdlib`.
     *
     * This is the fact that decides how far ASM alone can go, so it is pinned against the actual
     * artefact rather than against a class this test synthesised. See
     * [kotlinFileFacadesAreSkippedBecauseKotlinCannotNameThem].
     */
    @Test
    fun kotlinMetadataKindsAreReadableWithAsmAlone() {
        val kinds = ArtifactScanner.metadataKinds(
            kotlinStdlibJar,
            listOf("kotlin/text/StringsKt", "kotlin/text/StringsKt__IndentKt", "kotlin/text/Regex"),
        )
        assertEquals(4, kinds["kotlin/text/StringsKt"], "a multi-file class facade")
        assertEquals(5, kinds["kotlin/text/StringsKt__IndentKt"], "a multi-file class part")
        assertEquals(1, kinds["kotlin/text/Regex"], "an ordinary class")

        val noMetadata = ArtifactScanner.metadataKinds(junitJar, listOf("junit/runner/Version"))
        assertNull(noMetadata["junit/runner/Version"], "a Java class carries no kotlin.Metadata")
    }

    /**
     * Why walking `kotlin.text` produces no `trimIndent`, and why that is a limit of ASM rather than
     * a decision.
     *
     * `kotlin.text.trimIndent` compiles to a public static on `kotlin/text/StringsKt__IndentKt`,
     * which is **package-private** -- generated Kotlin in another package cannot call it. The public
     * name is the facade `kotlin/text/StringsKt`, which declares no methods of its own (it inherits
     * them) and which Kotlin cannot name at all: there is no `StringsKt` in the Kotlin namespace,
     * only `kotlin.text.trimIndent`. Recovering that name, and knowing that its first JVM parameter
     * is an extension *receiver* rather than an ordinary argument, means decoding `@Metadata`'s
     * `d1`/`d2` -- i.e. `kotlin-metadata-jvm`, not ASM.
     *
     * So the walker binds only what is callable from Kotlin by its JVM shape: a Java static, or a
     * Kotlin `@JvmStatic`. `docs/ecosystem.md` §5b's target (`androidx.compose.material3`) is on the
     * far side of this line, and this test is the statement of how far away it is.
     */
    @Test
    fun kotlinFileFacadesAreSkippedBecauseKotlinCannotNameThem() {
        val names = ArtifactScanner.scanJar(kotlinStdlibJar, includePrefixes = listOf("kotlin.text")).map { it.name }
        assertTrue(
            names.none { it.endsWith(".trimIndent") || it.endsWith(".trimMargin") },
            "a multi-file part's statics must not be bound: $names",
        )
        assertTrue(
            names.none { it.startsWith("kotlin.text.StringsKt") },
            "nothing may be attributed to a facade or a part: $names",
        )
    }
}
