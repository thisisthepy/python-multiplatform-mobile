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
     * `ValueClassFixtures.kt` and its `multipart` siblings, compiled as part of this module's own
     * test sourceSet -- located the same way [kotlinStdlibJar] is (`protectionDomain.codeSource`),
     * except a test run leaves this one a directory of `.class` files rather than a jar, which
     * [ArtifactScanner.scanJar] now accepts directly. They are never compiled *by* the test, only
     * looked up after the fact: the walker still meets them purely as bytecode plus `@Metadata`.
     */
    private val fixtureClasses: File
        get() {
            val location = Class.forName("fixture.artifactvalueclass.Meters").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isDirectory, "expected a directory of .class files: $it") }
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
     * `kotlin.text.trimIndent` used to be unreachable: it compiles to a public static on
     * `kotlin/text/StringsKt__IndentKt`, which is **package-private** (generated Kotlin in another
     * package cannot call it), behind the public facade `kotlin/text/StringsKt`, which declares no
     * methods of its own and which Kotlin cannot name at all -- there is no `StringsKt` in the
     * Kotlin namespace, only `kotlin.text.trimIndent`. Recovering that name, and knowing that its
     * first JVM parameter is an extension *receiver* rather than an ordinary argument, needs
     * `@Metadata`'s `d1`/`d2` payload, which `KotlinMetadata.kt` now decodes.
     *
     * The facade and part class names themselves must still never appear anywhere a caller could
     * see them -- neither has a Kotlin spelling, so a bound entry naming one would generate source
     * that cannot compile.
     */
    @Test
    fun aTopLevelExtensionBehindAMultiFileFacadeIsNowReachable() {
        val entries = ArtifactScanner.scanJar(kotlinStdlibJar, includePrefixes = listOf("kotlin.text"))
        val names = entries.map { it.name }
        assertTrue(names.none { it.startsWith("kotlin.text.StringsKt") }, "nothing may be attributed to a facade or a part: $names")

        val trimIndent = entries.single { it.name == "kotlin.text.trimIndent" }
        assertEquals(1, trimIndent.arity, "the receiver is the one argument this declaration takes")
        assertEquals(listOf("STRING"), trimIndent.paramTags)
        assertEquals("STRING", trimIndent.returnTag)
        assertEquals(listOf("import kotlin.text.trimIndent as artifact_ext_kotlin_text_trimIndent"), trimIndent.imports)
        assertEquals(
            "{ args -> ((args[0] as String).artifact_ext_kotlin_text_trimIndent()) }",
            trimIndent.lambdaBody,
            "Kotlin has no fully-qualified call syntax for an extension -- the alias from imports is load-bearing",
        )
    }

    /**
     * `getInWholeSeconds-impl` (`kotlin.time.Duration`) was the case the old `-` filter was written
     * against, and it is *still* declined -- but now because `Duration`'s constructor is `internal`,
     * a fact read from `@Metadata`, not because its JVM name contains a hyphen. `Meters` is the same
     * shape (`@JvmInline value class` wrapping a single primitive) with a public constructor, which
     * is what makes the difference observable: replacing the name filter with a blanket "value class
     * implies declined" rule would still decline this and prove nothing.
     */
    @Test
    fun aPublicValueClassRoundTripsThroughItsUnderlyingPrimitive() {
        val entries = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
        val sumMeters = entries.single { it.name == "fixture.artifactvalueclass.sumMeters" }
        assertEquals(2, sumMeters.arity)
        assertEquals(listOf("FLOAT", "FLOAT"), sumMeters.paramTags)
        assertEquals("FLOAT", sumMeters.returnTag)
        assertEquals(emptyList(), sumMeters.imports, "not an extension -- no import needed to call an ordinary top-level function")
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.sumMeters(" +
                "fixture.artifactvalueclass.Meters((args[0] as Double)), " +
                "fixture.artifactvalueclass.Meters((args[1] as Double)))).value) }",
            sumMeters.lambdaBody,
        )
    }

    /** `kotlin.time.Duration.getInWholeSeconds-impl(J)J`'s exact shape, reproduced with a
     * constructor the walker *is* allowed to call: a JVM parameter (the unboxed receiver) with no
     * declared Kotlin parameter behind it. Declined by the arity check in `ArtifactScanner
     * .kotlinCandidates`, not by [aPublicValueClassRoundTripsThroughItsUnderlyingPrimitive]'s
     * constructor-visibility check -- both have to hold for a value class to be usable at all. */
    @Test
    fun aValueClasssOwnMemberIsDeclinedForItsImplicitReceiverNotItsVisibility() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass.Seconds")).map { it.name }
        assertEquals(emptyList(), names, "Seconds.doubled is a true member: its receiver has no declared Kotlin parameter to bind")
    }

    /** Same Kotlin name, unrelated JVM shape (one mangled by a value-class parameter, one not):
     * grouping has to key off the *resolved* Kotlin name for this to be caught at all -- keying off
     * the raw JVM/ASM name would never have seen these two as the same declaration. */
    @Test
    fun overloadsThatCollideOnlyAfterResolvingTheKotlinNameAreStillDropped() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.addMeters" !in names, "two overloads named addMeters must both be dropped: $names")
    }

    /** An extension whose receiver is itself the value class -- unwrap-on-the-way-in for the
     * receiver, wrap-on-the-way-out for the return, in the one declaration. */
    @Test
    fun anExtensionOnAValueClassUnwrapsTheReceiverAndWrapsTheReturn() {
        val doubled = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
            .single { it.name == "fixture.artifactvalueclass.doubled" }
        assertEquals(1, doubled.arity)
        assertEquals(listOf("FLOAT"), doubled.paramTags)
        assertEquals("FLOAT", doubled.returnTag)
        assertEquals(
            listOf("import fixture.artifactvalueclass.doubled as artifact_ext_fixture_artifactvalueclass_doubled"),
            doubled.imports,
        )
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.Meters((args[0] as Double))" +
                ".artifact_ext_fixture_artifactvalueclass_doubled()).value) }",
            doubled.lambdaBody,
        )
    }

    /** `suspend` is declined explicitly, from `@Metadata`, per CLAUDE.md's "제외한 것을 조용히
     * 빠뜨리지 마라" -- not merely absent as a side effect of [boundaryTypeOf] rejecting a
     * `Continuation` parameter it never even has to see. */
    @Test
    fun aSuspendFunctionIsExplicitlyDeclined() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.neverBound" !in names, "suspend must never reach the table: $names")
    }

    /**
     * `@JvmMultifileClass` split across two files, reproducing `kotlin.text.trimIndent`'s own shape
     * with a fixture the test controls: a plain top-level function in one part, and -- combining the
     * two hard cases at once -- an extension function in the other.
     */
    @Test
    fun aMultiFileFacadesPartsAreAllReachableUnderThePackageName() {
        val entries = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass.multipart"))
        val names = entries.map { it.name }
        assertTrue(names.none { "MultiFacadeKt" in it }, "the facade's own JVM name has no Kotlin spelling: $names")

        val greeting = entries.single { it.name == "fixture.artifactvalueclass.multipart.partGreeting" }
        assertEquals("{ (fixture.artifactvalueclass.multipart.partGreeting()) }", greeting.lambdaBody)

        val shout = entries.single { it.name == "fixture.artifactvalueclass.multipart.shoutViaFacade" }
        assertEquals(
            listOf("import fixture.artifactvalueclass.multipart.shoutViaFacade as artifact_ext_fixture_artifactvalueclass_multipart_shoutViaFacade"),
            shout.imports,
        )
    }

    /** `internal` is JVM-public but not a name generated Kotlin may call; filtered from `@Metadata`'s
     * own visibility, not from the JVM `ACC_PUBLIC` bit every one of these methods also carries. */
    @Test
    fun anInternalTopLevelFunctionIsDeclinedRegardlessOfItsJvmVisibility() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.secretlyInternal" !in names, "internal must not reach the table: $names")
    }

    /**
     * The exploratory count `docs/ecosystem.md` §5b's second producer was written to answer, run
     * against real, locally-cached Compose Multiplatform desktop jars. Skipped (not failed) when
     * this machine's Gradle cache does not have them, since the path is local-machine state, not
     * something a clean checkout can be expected to have -- see `ArtifactScannerTest`'s own KDoc for
     * why the jars this test *does* control (junit, kotlin-stdlib) are found the same way rather
     * than pinned as a path.
     *
     * The honest result this pins, run 2026-08-14 against Compose Multiplatform 1.6.11: **zero**
     * `androidx.compose.foundation.layout` declarations are reachable, `Modifier.padding` included.
     * Not a bug in this walker's cross-jar resolution (which is what made `ui.unit`'s result below
     * possible) -- `Modifier.padding(Dp): Modifier` has `Modifier` as *both* its receiver and its
     * return, and `Modifier` is an ordinary interface, not a value class or a primitive.
     * `resolveKotlinType` has nothing to resolve it to: `boundaryTypeOf`'s KDoc explains why there is
     * no `OBJECT` fallback, and that reasoning is unchanged by this walker now knowing `Modifier`'s
     * real Kotlin name -- a name is not a boundary representation. Reaching `Modifier` itself is a
     * different, larger piece of work than this one (an object-handle boundary type), not something
     * `resolveKotlinType`'s value-class case was ever going to cover.
     *
     * `androidx.compose.ui.unit`, by contrast, *is* mostly `Dp` extensions of `Dp` -- receiver,
     * parameter and return all the same value class -- and eight of them (`coerceAtLeast`, `lerp`,
     * `max`, ...) are reachable, proving the cross-jar classifier resolution this task added: `Dp`'s
     * own class lives in this same jar, but a Compose consumer's `Modifier` extensions would need it
     * resolved from `foundation-layout`, which is the shape [ArtifactClasspath] exists for.
     *
     * `docs/ecosystem.md` §5b's own target (calling a composable) is out of scope here on purpose --
     * this only counts what the walker's *declarations* pass, not whether Compose's runtime would
     * accept a call built this way.
     */
    @Test
    fun androidxComposeDeclarationCountsAreHonestAboutWhatValueClassSupportDoesAndDoesNotReach() {
        val gradleCaches = listOf(
            File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
            File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
        ).firstOrNull { it.isDirectory }
        if (gradleCaches == null) return // no local Gradle cache found at either known location

        fun jarUnder(group: String, artifact: String): File? = gradleCaches.resolve(group).resolve(artifact)
            .walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

        val layoutJar = jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop") ?: return
        val unitJar = jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop") ?: return
        val classpath = listOfNotNull(
            layoutJar,
            unitJar,
            jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
            jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
            kotlinStdlibJar,
        )

        val layoutEntries = ArtifactScanner.scanJar(layoutJar, includePrefixes = listOf("androidx.compose.foundation.layout"), classpath = classpath)
        assertEquals(
            emptyList(),
            layoutEntries.map { it.name },
            "Modifier itself has no boundary representation yet -- see this test's KDoc for why that is expected",
        )

        val unitEntries = ArtifactScanner.scanJar(unitJar, includePrefixes = listOf("androidx.compose.ui.unit"), classpath = classpath)
        assertTrue(unitEntries.isNotEmpty(), "expected at least one Dp-only declaration in androidx.compose.ui.unit to be reachable")
    }
}
