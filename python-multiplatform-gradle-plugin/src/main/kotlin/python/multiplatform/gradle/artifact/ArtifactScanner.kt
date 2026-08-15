package python.multiplatform.gradle.artifact

import kotlin.metadata.jvm.KotlinClassMetadata
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import java.io.File
import java.util.jar.JarFile

/**
 * `docs/ecosystem.md` §5b's second producer: bindings taken out of the artefacts a build resolves,
 * rather than out of the source it compiles.
 *
 * ### Why this exists at all
 *
 * KSP only sees declarations being compiled, so a third-party binary -- `androidx.compose.material3`
 * being the case that motivated it -- can never have a fragment. `PyREPL` already solved the reading
 * half of this: `app/build.gradle.kts` makes a resolvable copy of each source set's
 * `implementation`/`api` configurations and walks the resulting jars with ASM. This is that walk,
 * pointed at a table instead of at `.pyi` stubs.
 *
 * ### Where this parts company with PyREPL's filters
 *
 * PyREPL drops private and protected members, `<init>`, `Companion`, and any name containing `-`.
 * Judged against what a *binding* needs rather than what a stub needs:
 *
 * | PyREPL's filter | here | why |
 * |---|---|---|
 * | not `private`/`protected` | **not enough** — must be `public`, and Kotlin-`public` rather than only JVM-`public` | PyREPL keeps package-private members, which is harmless in a stub; `internal` is JVM-public but not a name generated Kotlin may call |
 * | drop `<init>` | kept | a constructor needs a `ReflectedClass` and a receiver handle, which is the next step |
 * | drop `Companion` | subsumed | only statics are bound, and `Companion` is an instance field |
 * | drop names containing `-` | **not kept** | see below |
 * | (none) | drop non-`static` | an instance method needs a receiver; see [ArtifactCallable] |
 * | (none) | drop unbindable types | see [boundaryTypeOf] and `resolveKotlinType` |
 * | (none) | **rename** ambiguous overloads rather than drop them | see [disambiguateOverloads] |
 * | (none) | drop `suspend` | its JVM shape takes a `Continuation`, and `resolveKotlinType`/`@Metadata` both know this before `boundaryTypeOf` would have to discover it by rejecting the parameter |
 *
 * **Why the `-` filter is gone rather than kept.** A hyphen in a JVM method name is Kotlin's
 * value-class mangling suffix, and it was dropped on the reasoning that a value class *erases to the
 * type it wraps*: `Duration` is a `long`, so `getInWholeSeconds-impl(J)J` would pass [boundaryTypeOf]'s
 * type filter while meaning something entirely different from `long -> long`. That reasoning is sound
 * only if the walker calls the JVM method by its mangled name -- which it never does. It generates
 * *Kotlin source* (`renderArtifactFragmentSource`) and lets `kotlinc` compile it, the same as a
 * human would write `Modifier.padding(16.dp)`; mangling is something the compiler does on the way
 * out, not a name this walker's own generated code ever spells. Filtering on it discarded the
 * declaration a value class exists to describe correctly, for a reason that only applied to a
 * different (rejected) design.
 *
 * What replaced it: `resolveKotlinType` (`KotlinMetadata.kt`), which reads `@Metadata`'s `d1`/`d2`
 * payload to learn the *Kotlin* type a parameter or return actually has -- `Meters`, not the `Double`
 * it erases to -- and, when that type is a value class, whether its constructor and its accessor are
 * public enough to construct and unwrap from generated code outside the module.
 * `kotlin.time.Duration` (`getInWholeSeconds-impl`, the case that motivated the old filter) is
 * *still* declined under the new rule, but for a fact rather than a heuristic: `Duration`'s
 * constructor is `internal`, so no Kotlin source outside `kotlin-stdlib` can ever build one, in
 * either direction. See `ValueClassFixtures.kt`'s `Meters` for the same shape with a public
 * constructor, and `ArtifactScannerTest` for both proved against real bytecode.
 *
 * ### What it takes to reach Compose, and what each piece was worth
 *
 * `docs/kotlin-extensions-in-python.md` §3 measured this walker binding **zero** declarations from
 * all 19 Compose Multiplatform desktop jars, and identified three separate rules each of which
 * produced that zero on its own. All three are now gone, and the measurement that replaced it --
 * `ArtifactScannerTest.composeModifierExtensionsSurviveBothGates`, over the same jars -- is
 * **314 declarations, 104 of them `Modifier` extensions**:
 *
 * | rule | what it cost | where it went |
 * |---|---|---|
 * | the metadata-kind gate: only `k=1` (an ordinary class) was dispatched on | **all 1,411** public top-level Compose functions, because a Kotlin top-level function compiles into a file facade (`k=2`) or a multi-file part (`k=5`) | the `when` in [scanClassNode], which now dispatches on `FileFacade` and `MultiFileClassFacade` too |
 * | the type gate: no boundary type for an ordinary object | every remaining declaration, `Modifier` being an interface | `resolveKotlinType`'s object-handle case (`KotlinMetadata.kt`) |
 * | drop a name carried by more than one binding | 33 of `Modifier`'s 130 names, `padding`/`size`/`background`/`border`/`clickable` among them | [disambiguateOverloads] |
 *
 * Of the 45 public top-level `Modifier` extensions still declined, **43 declare a function-typed
 * parameter** (counted by the same test): a Python callable cannot become a Kotlin `FunctionN` at
 * this boundary -- `UpcallTrampoline.toKotlinObject` would hand the cast a `PyObject` -- so binding
 * them would produce entries that always fail. That is the honest remaining limit, and it is not a
 * metadata problem.
 *
 * ### Facades, parts, and extension receivers
 *
 * Compose's public surface is not reached by widening [boundaryTypeOf] alone. Two more ASM-only
 * limits stood in the way, and `KotlinMetadata.kt` is what removes them:
 *
 * 1. **Kotlin top-level functions hide behind a facade.** `kotlin.text.trimIndent` compiles onto
 *    the package-private `StringsKt__IndentKt`; the public class `StringsKt` (a
 *    `MULTI_FILE_CLASS_FACADE`) declares no methods of its own, only a list of part class names.
 *    ASM alone can see that the facade exists and that it is public, and nothing past that --
 *    `ArtifactScannerTest.theWholeJUnitJarYieldsExactlyTheseDeclarations`'s Kotlin-jar counterpart
 *    used to pin exactly this limit. `KotlinClassMetadata.MultiFileClassFacade.partClassNames`
 *    supplies the part names; each part's own `@Metadata` (`MultiFileClassPart.kmPackage`) supplies
 *    the real declarations and their true (unmangled) Kotlin names.
 * 2. **An extension receiver is invisible in a JVM descriptor.** `fun Modifier.padding(Dp): Modifier`
 *    compiles to a static method whose *first* JVM parameter is the receiver, indistinguishable by
 *    descriptor alone from an ordinary parameter of the same erased type. `KmFunction
 *    .receiverParameterType` is the only place that survives. Kotlin has no fully-qualified call
 *    syntax for an extension (see [ArtifactCallable.imports]'s KDoc), so a bound extension is called
 *    `receiver.alias(...)` after an `import owner.name as alias` -- the only Kotlin can address it.
 *
 * ### What is still declined, on purpose
 *
 * A value-class *receiver* on a true member of that class (not an extension declared elsewhere) --
 * `Duration.inWholeSeconds` is one -- is declined by [resolveKotlinType] refusing an unreadable
 * constructor, but even a value class with a public one would still be declined here: a member
 * function's own JVM shape puts the (unboxed) instance in JVM parameter position 0 with **no**
 * declared Kotlin parameter to account for it, which the arity check in [scanClassNode] catches as a
 * mismatch. Only a class's plain statics (`@JvmStatic`, a Java `static`) and package-level top-level
 * declarations are bound; instance dispatch is `docs/ecosystem.md` §5b's next step, same as before.
 * `suspend` is declined explicitly via `@Metadata` rather than relying on [boundaryTypeOf] rejecting
 * its `Continuation` parameter, per CLAUDE.md's "제외한 것을 조용히 빠뜨리지 마라".
 */
/**
 * One declaration, seen by both consumers of a walk at the moment it is read.
 *
 * [callable] is `null` exactly when the binder declined it, and [declaration] says why. Keeping them
 * together up to the end of the walk is what makes `docs/pyi-generation-design.md` §2.2's "the two
 * renderers cannot drift" a property of the code rather than a convention.
 *
 * **Top-level, not nested in [ArtifactScanner], because there are two producers.** [KlibScanner]
 * builds these too and hands them to [ArtifactScanner.disambiguateOverloads], so that the `name__<types>`
 * spelling has exactly one implementation: both producers' output lands in one
 * `python.multiplatform.generated.artifacts.ArtifactTable`, and a consumer that had to learn two
 * meanings for `__` depending on which walker found the declaration would be reading a table that is
 * only accidentally consistent. It is also what gives a klib declaration a
 * [DeclarationModel] and therefore a `.pyi` stub.
 */
internal data class Candidate(
    val callable: ArtifactCallable?,
    val declaration: DeclarationModel,
    /**
     * Set only by [ArtifactScanner]'s own Kotlin path, and only until
     * [ArtifactScanner.applyDefaultOmission] has consumed it.
     *
     * Whether a defaulted argument may be left out of a generated call is not decidable where the
     * call is built -- see [ArtifactScanner.DefaultOmissionPlan] -- so the body is built twice: once
     * passing everything, and again once the whole walk is visible. A candidate that reaches
     * [ArtifactScanner.disambiguateOverloads] still carrying one has not been through that pass;
     * that constructor drops it, which is correct, because by then the body is settled.
     */
    val plan: ArtifactScanner.DefaultOmissionPlan? = null,
)

internal object ArtifactScanner {

    /** `kotlin.Metadata` is `RUNTIME`-retained, so ASM reads it as an ordinary visible annotation --
     * no metadata library needed for the *kind*, only for the payload. */
    private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

    /** `k = 1`: an ordinary class, object or interface. Every other kind is a compiler-invented
     * carrier -- 2 file facade, 3 synthetic class, 4 multi-file facade, 5 multi-file part -- whose
     * JVM name has no Kotlin spelling. Used only by [metadataKinds], which reads just the kind byte
     * without decoding the rest of the payload; [scanClassNode] dispatches on the full
     * `KotlinClassMetadata` instead. */
    private const val KOTLIN_KIND_CLASS = 1

    /**
     * Every binding this jar (or, for a test fixture, this directory of `.class` files -- see
     * `ArtifactClasspath`) offers, sorted by name.
     *
     * @param includePrefixes package or class names, each matched as a *namespace*: `junit.runner`
     *   covers `junit.runner.Version` but `junit.run` covers nothing. Empty means every class,
     *   which is what the type filter makes affordable.
     * @param classpath every root a value-class parameter or an extension receiver might need to be
     *   resolved against, [target] included. Defaults to `[target]` alone, which is correct whenever
     *   a value class used by [target] is also declared in [target] -- true for this walker's own
     *   test fixtures, false for `androidx.compose.foundation.layout`, whose `Dp` lives in
     *   `androidx.compose.ui.unit`. [PythonArtifactBindingsTask] passes the whole resolved
     *   configuration.
     */
    fun scanJar(target: File, includePrefixes: List<String> = emptyList(), classpath: List<File> = listOf(target)): List<ArtifactCallable> =
        scan(target, includePrefixes, classpath).mapNotNull { it.callable }.sortedBy { it.name }

    /**
     * The same walk, with the half `docs/pyi-generation-design.md` §2.2 asks for kept: one
     * [DeclarationModel] per public declaration this walker considered, **including the ones it
     * declined**, each carrying the table key the binder gave it or the reason there is none.
     *
     * Not a second scan. §2.2 rejects "two readers of the same jars that can disagree", so every
     * model here is built at the point its binding is built, from the same `ResolvedFunction`; the
     * two results of one walk are separated only at the end. `DeclarationModelTest
     * .theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk` pins that they cannot drift.
     */
    fun scanDeclarations(
        target: File,
        includePrefixes: List<String> = emptyList(),
        classpath: List<File> = listOf(target),
    ): List<DeclarationModel> = scan(target, includePrefixes, classpath)
        .map { it.declaration }
        .sortedWith(compareBy({ it.owner }, { it.simpleName }, { it.bindingName ?: "" }))

    private fun scan(target: File, includePrefixes: List<String>, classpath: List<File>): List<Candidate> {
        val artifactClasspath = ArtifactClasspath(classpath)
        val entries = mutableListOf<Candidate>()
        forEachClassEntry(target) { relativePath, bytes ->
            // A nested class's binary name uses `$`, which is not how Kotlin spells the qualified
            // name (`Outer.Inner`), and `$1` anonymous classes have no Kotlin name at all. Skipped
            // wholesale, as PyREPL does.
            if ('$' in relativePath) return@forEachClassEntry
            val qualified = binaryNameToQualified(relativePath.removeSuffix(".class"))
            if (!matchesInclude(qualified, includePrefixes)) return@forEachClassEntry
            val node = readClassNodeOrNull(bytes) ?: return@forEachClassEntry
            entries += scanClassNode(node, artifactClasspath)
        }
        // Both passes are over the whole walk rather than per class, and for the same reason: an
        // overload set is a property of a *package*, and Kotlin lets one live in two files.
        // `docs/kotlin-extensions-in-python.md` §2.5 counts 11 such pairs in Compose alone -- two
        // file facades, two `ClassNode`s, one Kotlin name -- which a per-class grouping cannot see.
        // It would have emitted them twice under one table key, and it would have generated calls
        // that do not compile (see [applyDefaultOmission]).
        //
        // Defaults first: [disambiguateOverloads] renames entries, and the sibling test needs the
        // Kotlin name they still share.
        return assignThunkIndices(disambiguateOverloads(applyDefaultOmission(entries)))
    }

    /**
     * The `k` of each named class's `kotlin.Metadata`, or absent from the map when the class carries
     * none. Exposed for `ArtifactScannerTest` to pin against a real Kotlin artefact, since it is the
     * fact that bounds what [scanClassNode] can dispatch on.
     *
     * @param binaryNames internal names, e.g. `kotlin/text/StringsKt`.
     */
    fun metadataKinds(jar: File, binaryNames: List<String>): Map<String, Int> {
        val wanted = binaryNames.toSet()
        val kinds = mutableMapOf<String, Int>()
        JarFile(jar).use { file ->
            wanted.forEach { name ->
                val entry = file.getJarEntry("$name.class") ?: return@forEach
                val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                kotlinMetadataKind(node)?.let { kinds[name] = it }
            }
        }
        return kinds
    }

    private fun forEachClassEntry(root: File, action: (relativePath: String, bytes: ByteArray) -> Unit) {
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { file -> action(file.relativeTo(root).invariantSeparatorsPath, file.readBytes()) }
        } else {
            JarFile(root).use { jarFile ->
                jarFile.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry -> action(entry.name, jarFile.getInputStream(entry).use { it.readBytes() }) }
            }
        }
    }

    private fun kotlinMetadataKind(node: ClassNode): Int? {
        val annotation = node.visibleAnnotations?.firstOrNull { it.desc == KOTLIN_METADATA_DESCRIPTOR } ?: return null
        val values = annotation.values ?: return KOTLIN_KIND_CLASS
        // `values` is a flat name/value list. `k` is absent when it holds its default, which is 1.
        for (i in 0 until values.size - 1 step 2) {
            if (values[i] == "k") return values[i + 1] as? Int
        }
        return KOTLIN_KIND_CLASS
    }

    private fun binaryNameToQualified(binaryName: String): String = binaryName.replace('/', '.')

    private fun packageNameOf(binaryName: String): String =
        binaryNameToQualified(binaryName.substringBeforeLast('/', missingDelimiterValue = ""))

    /** A namespace match, not a text one: `junit.run` is a prefix of the string `junit.runner` and
     * of no package or class. */
    private fun matchesInclude(qualifiedName: String, includePrefixes: List<String>): Boolean =
        includePrefixes.isEmpty() || includePrefixes.any {
            qualifiedName == it || qualifiedName.startsWith("$it.")
        }

    private fun scanClassNode(node: ClassNode, classpath: ArtifactClasspath): List<Candidate> {
        if (!node.access.hasFlag(Opcodes.ACC_PUBLIC)) return emptyList()
        if (node.access.hasFlag(Opcodes.ACC_SYNTHETIC) or node.access.hasFlag(Opcodes.ACC_ANNOTATION)) return emptyList()

        // Ambiguity is counted over what *would be bound*, over the whole group a facade
        // contributes -- not per class -- so ambiguity introduced by splitting one Kotlin name
        // across two multi-file parts is still caught. See this object's KDoc.
        val candidates: List<Candidate> = when (val metadata = kotlinClassMetadataOf(node)) {
            // No `kotlin.Metadata` at all: a Java class (or a Kotlin class whose metadata payload
            // this library could not decode -- declined the same way unparsable bytecode is, not
            // fatal). Bound the same way this walker always has, by JVM descriptor alone.
            null -> javaStaticCandidates(node)
            is KotlinClassMetadata.Class -> kotlinCandidates(
                owner = binaryNameToQualified(node.name),
                ownerIsClass = true,
                functions = functionsOf(metadata.kmClass).filterNot { it.isExtension },
                ownerNode = node,
                classpath = classpath,
            )
            is KotlinClassMetadata.FileFacade -> kotlinCandidates(
                owner = kotlinPackageNameOverrideOf(node) ?: packageNameOf(node.name),
                ownerIsClass = false,
                functions = functionsOf(metadata.kmPackage),
                ownerNode = node,
                classpath = classpath,
            )
            is KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.flatMap { partBinaryName ->
                val partNode = classpath.classNode(partBinaryName) ?: return@flatMap emptyList()
                val partMetadata = kotlinClassMetadataOf(partNode) as? KotlinClassMetadata.MultiFileClassPart
                    ?: return@flatMap emptyList()
                kotlinCandidates(
                    owner = kotlinPackageNameOverrideOf(partNode) ?: packageNameOf(partBinaryName),
                    ownerIsClass = false,
                    functions = functionsOf(partMetadata.kmPackage),
                    ownerNode = partNode,
                    classpath = classpath,
                )
            }
            // A lone part is package-private and never reaches here on its own (the `ACC_PUBLIC`
            // guard above already excluded it) -- only through the facade case, which fetches it by
            // name. `SyntheticClass` and `Unknown` carry no Kotlin declarations this walker binds.
            else -> emptyList()
        }

        return candidates
    }

    /**
     * Gives every member of an overload set a name of its own, instead of dropping the set.
     *
     * ### What changed, and what did not
     *
     * The old rule dropped a Kotlin name outright as soon as more than one binding would carry it.
     * Its reasoning was about **arbitration** and is still right: `org.junit.Assert.assertEquals` has
     * eight bindable overloads, and letting a sort order pick one means `assertEquals(3, 3)` from
     * Python silently calls the deprecated `(double, double)` that always fails. Nothing here picks.
     *
     * What changed is that there is now a third option between "arbitrate" and "drop". The old rule
     * predates `@Metadata`: with only JVM descriptors there was no *Kotlin* parameter type to name an
     * overload by, and 108 mangled JVM names are ambiguous within their own class
     * (`docs/kotlin-extensions-in-python.md` §2.3), so the JVM name could not do it either. Metadata
     * supplies the declared Kotlin types, so the overloads can be **told apart** rather than
     * arbitrated between.
     *
     * The price of not doing so was measured: **33 of `Modifier`'s 130 names**
     * (`docs/kotlin-extensions-in-python.md` §3.1), and the casualty list is the API's centre of
     * gravity -- `padding`, `size`, `background`, `border`, `clickable`, `width`, `height`. For
     * `padding` specifically, the *only* unmangled overload is the `PaddingValues` one nobody wants,
     * so "keep whichever the descriptor-era filter happened to leave" was also the worst answer.
     *
     * ### The rule
     *
     * The bare name is bound only for a group of one. A group of more than one gets one name per
     * member, `name__<types>`, under the first of three schemes that separates the group:
     *
     * 1. the simple names of the declared **value parameters** -- `padding__Dp`, `padding__Dp_Dp`,
     *    `padding__PaddingValues`;
     * 2. the **receiver** joined to them, for overloads that differ only in what they extend
     *    (`Int.times` and `Double.times`, both in `androidx.compose.ui.unit`);
     * 3. **fully qualified** names, for the case where two parameter types share a simple name.
     *
     * A group no scheme separates is dropped, which is the old rule surviving as the floor: two
     * declarations this walker genuinely cannot tell apart must not both claim a table key, and
     * neither may be picked.
     *
     * ### Why a name and not a Python-side dispatcher
     *
     * A dispatcher is the better surface and it cannot be built here. `UpcallTable` is keyed by name
     * and `ExposedCallable` carries one fixed `arity` that `UpcallTrampoline.unmarshalArguments`
     * enforces exactly, so one name reaches one signature by construction; and
     * `PythonProxySource.renderOne` publishes an entry by `setattr`ing its *leaf* name onto a module,
     * so two entries sharing a leaf would silently overwrite each other. A dispatcher therefore lives
     * in `pythonx` (`docs/pythonx-adapter-design.md` §4.1) and selects among these names -- which is
     * why they have to exist and be distinguishable, and why `ExposedCallable` now carries
     * `paramNames` and `paramTypeNames` for it to select on. This layer's job is to make the choice
     * *possible*, not to make it.
     */
    internal fun disambiguateOverloads(candidates: List<Candidate>): List<Candidate> {
        val schemes: List<(ArtifactCallable) -> String> = listOf(
            { it.overloadSuffix(includeReceiver = false, qualified = false) },
            { it.overloadSuffix(includeReceiver = true, qualified = false) },
            { it.overloadSuffix(includeReceiver = true, qualified = true) },
        )
        // A candidate the binder already declined has no name to be ambiguous about; it passes
        // through so that `docs/pyi-generation-design.md` §2.2's "what is declined stays visible"
        // survives this stage too.
        val (bound, declined) = candidates.partition { it.callable != null }
        val disambiguated = bound.groupBy { it.callable!!.name }.values.flatMap { group ->
            if (group.size == 1) return@flatMap group
            val scheme = schemes.firstOrNull { scheme -> group.mapTo(HashSet()) { scheme(it.callable!!) }.size == group.size }
                ?: return@flatMap group.map { candidate ->
                    Candidate(
                        callable = null,
                        declaration = candidate.declaration.copy(
                            bindingName = null,
                            declineReason = "one of ${group.size} overloads no naming scheme separates",
                        ),
                    )
                }
            group.map { candidate ->
                val name = "${candidate.callable!!.name}__${scheme(candidate.callable)}"
                Candidate(
                    callable = candidate.callable.copy(name = name),
                    declaration = candidate.declaration.copy(bindingName = name),
                )
            }
        }
        return disambiguated + declined
    }

    /**
     * The `__`-suffix for one member of an overload set.
     *
     * The receiver is *not* included by default even though it is `paramTypeNames` slot 0: including
     * it always would spell every `Modifier` extension `padding__Modifier_Dp`, and the receiver is
     * the one parameter a reader already knows from where the name is attached
     * (`docs/kotlin-extensions-in-python.md` §4.1). It joins only when it is what separates the
     * group.
     */
    private fun ArtifactCallable.overloadSuffix(includeReceiver: Boolean, qualified: Boolean): String {
        val skipReceiver = if (receiverTypeName != null && !includeReceiver) 1 else 0
        // A composable's synthetic slots are the same for every member of its overload set --
        // `Composer_Int_Int_Int` on all of them -- so they separate nothing and would only make the
        // Python name unreadable. Dropped, which leaves exactly the declared parameters the two
        // overloads actually differ in.
        val declaredEnd = paramNames.indexOf(COMPOSER_PARAMETER_NAME).takeIf { it >= 0 } ?: paramTypeNames.size
        val parts = paramTypeNames.take(declaredEnd).drop(skipReceiver)
        // A zero-parameter member of a group -- there can be at most one, so this only ever has to
        // be distinct from the others, not descriptive.
        if (parts.isEmpty()) return "0"
        return parts.joinToString("_") { name ->
            val chosen = if (qualified) name else name.substringAfterLast('.')
            chosen.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        }
    }

    private fun javaStaticCandidates(node: ClassNode): List<Candidate> {
        val owner = binaryNameToQualified(node.name)
        return node.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) && it.access.hasFlag(Opcodes.ACC_STATIC) }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") }
            // `$` is a Kotlin/Java synthetic (`fn$default`, `access$000`). `-` would be value-class
            // mangling, but nothing reaches this branch *with* Kotlin metadata to interpret it by --
            // a name like that here is unreadable, not merely unsupported, so it stays declined.
            .filter { '$' !in it.name && '-' !in it.name }
            .mapNotNull { candidateFromDescriptor(owner, it.name, it.desc) }
    }

    private fun kotlinCandidates(
        owner: String,
        ownerIsClass: Boolean,
        functions: List<ResolvedFunction>,
        ownerNode: ClassNode,
        classpath: ArtifactClasspath,
    ): List<Candidate> {
        val bySignature = functions.associateBy { it.jvmSignature.name to it.jvmSignature.descriptor }
        return ownerNode.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) && it.access.hasFlag(Opcodes.ACC_STATIC) }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") && '$' !in it.name }
            .mapNotNull { method ->
                val function = bySignature[method.name to method.desc]
                    // A `suspend` declaration's JVM shape carries a trailing `Continuation`, so its
                    // descriptor never matches the one metadata records and it would fall out here
                    // anyway. Looked up by Kotlin name instead so that it is declined *explicitly*
                    // and reaches the model flagged -- CLAUDE.md's "제외한 것을 조용히 빠뜨리지 마라".
                    ?: return@mapNotNull functions
                        .firstOrNull { it.isSuspend && it.jvmSignature.name == method.name }
                        ?.let { suspending ->
                            declinedCandidate(owner, ownerIsClass, suspending, classpath, "suspend", isComposable(method))
                        }
                if (function.isSuspend) return@mapNotNull declinedCandidate(owner, ownerIsClass, function, classpath, "suspend", isComposable(method))
                // A member whose Kotlin-declared parameter count does not match its JVM parameter
                // count has an implicit JVM parameter metadata does not account for -- a value
                // class's own instance turned into an unboxed receiver, or a composable's synthetic
                // `$composer`/`$changed`. There is no declared parameter to bind it to, so it is
                // declined here rather than misread as one fewer parameter than the method actually
                // takes. See this object's KDoc.
                val (paramDescriptors, returnDescriptor) = splitMethodDescriptor(method.desc)
                if (function.allParameterTypes.size != paramDescriptors.size) {
                    // A composable's extra JVM parameters are not *unaccounted for* -- they are
                    // `$composer`, the `$changed` masks and the `$default` mask, in that order, and
                    // `ComposableShape` reads them off the descriptor. Bound as ordinary slots
                    // Python fills; everything else with an arity mismatch is still an implicit
                    // receiver nothing can supply, and still declined.
                    if (isComposable(method)) {
                        composableCandidate(
                            owner, ownerIsClass, function, classpath, paramDescriptors, returnDescriptor, method.name,
                            ownerNode.name,
                        )?.let { return@mapNotNull it }
                    }
                    return@mapNotNull declinedCandidate(
                        owner,
                        ownerIsClass,
                        function,
                        classpath,
                        "the JVM signature carries ${paramDescriptors.size - function.allParameterTypes.size} " +
                            "parameter(s) metadata does not declare",
                        isComposable(method),
                    )
                }
                candidateFromFunction(owner, ownerIsClass, function, classpath, isComposable(method))
            }
    }

    /**
     * Whether this JVM method is a `@Composable`.
     *
     * ### It is an *invisible* annotation, and reading the visible list found none, ever
     *
     * This used to read `MethodNode.visibleAnnotations` on the stated grounds that
     * "`@Composable` is `RUNTIME`-retained, so ASM reads it as an ordinary visible annotation". That
     * is wrong, and `javap -v androidx/compose/material3/TextKt.class` says so directly: every
     * composable in the jar carries
     *
     *     RuntimeInvisibleAnnotations:
     *       androidx.compose.runtime.Composable
     *
     * because `androidx.compose.runtime.Composable` is declared
     * `@Retention(AnnotationRetention.BINARY)`. `RuntimeInvisible*` is exactly what ASM puts in
     * `invisibleAnnotations`, so the old predicate answered `false` for **every real composable**,
     * and `DeclarationModel.isComposable` -- which drives `docs/pyi-generation-design.md` §3.6's
     * PascalCase rule -- was dead. Nothing caught it because the only composables reaching it were
     * being declined anyway, and a declined entry's flag is not asserted anywhere.
     *
     * Both lists are read rather than just the invisible one: retention is the annotation author's
     * choice and a future Compose could change it, whereas a method carrying the descriptor in
     * either list means the same thing.
     *
     * ### Why not `@Metadata`
     *
     * `KmFunction` has an annotations list, but `kotlin-metadata-jvm` only populates it when the
     * compiler was asked to keep annotations in metadata, which Compose's release build was not.
     * The class file is the fact here; metadata is how the *parameters* are then interpreted.
     *
     * Two consumers: the name rule above, and [composableCandidate], which is what stops the arity
     * check from declining every composable.
     */
    private fun isComposable(method: org.objectweb.asm.tree.MethodNode): Boolean =
        sequenceOf(method.visibleAnnotations, method.invisibleAnnotations)
            .filterNotNull()
            .any { list -> list.any { it.desc == ComposableShape.COMPOSABLE_ANNOTATION_DESCRIPTOR } }

    /**
     * A `@Composable`, bound as an ordinary function whose `$composer`, `$changed` and `$default`
     * slots are Python's to fill.
     *
     * ### Why the synthetic parameters are exposed rather than hidden
     *
     * `docs/pythonx-adapter-design.md` §4.5 rejected every way of *hiding* them, and each rejection
     * still stands: an arity-prefix entry cannot express "pass `text`, skip `modifier`", presence
     * branching costs 2^15 call expressions for `Text`, `Text$default` does not exist, and a
     * generated wrapper cannot restate defaults metadata never carries. What none of those noticed is
     * that the mask is a **declared trailing parameter** (§5.2) -- so it does not have to be a
     * compile-time constant at all. Compute it at run time and every one of those problems is
     * somebody else's: `$default` bit *i* set means "parameter *i* was not passed, substitute its
     * default", which is a fact about one integer rather than about 2^15 branches.
     *
     * Where that integer is computed matters and is not here. `pythonx` calls `androidx`, never the
     * other way round, so the mask is Python's arithmetic over which arguments the caller wrote;
     * this layer's job is to leave a slot for it. `$composer` is the one value Python cannot invent
     * -- it exists only inside a composition -- and comes from the single hand-written entry point.
     *
     * ### What is declined here, and why
     *
     * - **An extension composable.** Compose's `$changed` slots count receivers and its `$default`
     *   bits are assigned over value parameters, so a receiver shifts one numbering and not the
     *   other. Nothing here has measured which, and `docs/kotlin-extensions-in-python.md` §2.6
     *   counts exactly one `Modifier`-extension composable in 500 -- so this waits for a measurement
     *   rather than guessing at a bit index that would silently substitute the wrong default.
     * - **A shape [ComposableShape.of] does not recognise**, which then falls back to the decline
     *   this method was reached from. A future lowering that adds a fourth kind of synthetic
     *   parameter degrades to "not bound" rather than to a call with the wrong arguments.
     * - **A `char` parameter**, for `boundaryTypeOf`'s reason: Python has no character type.
     */
    private fun composableCandidate(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        paramDescriptors: List<String>,
        returnDescriptor: String,
        jvmMethodName: String,
        ownerInternalName: String,
    ): Candidate? {
        if (function.isExtension) return null
        val declaredCount = function.allParameterTypes.size
        val shape = ComposableShape.of(declaredCount, paramDescriptors) ?: return null
        if (shape.totalCount != paramDescriptors.size) return null

        val model = declarationModelOf(owner, ownerIsClass, function, classpath, isComposable = true) ?: return null
        val paramTags = paramDescriptors.map { composableSlotTagOf(it) ?: return null }
        val returnTag = if (returnDescriptor == "V") "UNIT" else composableSlotTagOf(returnDescriptor) ?: return null
        val declaredTypeNames = function.allParameterTypes.map { kotlinClassifierNameOf(it) ?: return null }

        val syntheticNames = shape.syntheticParameterNames()
        val paramNames = (0 until declaredCount).map { function.allParameterNames.getOrNull(it) ?: "p$it" } + syntheticNames
        val paramTypeNames = declaredTypeNames + syntheticNames.map {
            if (it == COMPOSER_PARAMETER_NAME) COMPOSER_TYPE_NAME else "kotlin.Int"
        }
        val qualifiedName = "$owner.${function.kotlinName}"

        return Candidate(
            callable = ArtifactCallable(
                name = qualifiedName,
                arity = paramDescriptors.size,
                paramTags = paramTags,
                returnTag = returnTag,
                // Filled by [assignThunkIndices], which is the only place that knows which `t<i>`
                // this is -- and therefore the only place the `.kt` and the `.class` can agree.
                lambdaBody = "",
                receiverTypeName = null,
                paramNames = paramNames,
                paramTypeNames = paramTypeNames,
                returnTypeName = kotlinClassifierNameOf(function.returnType),
                // The declaration's own answer, unlike every other producer here -- and it is the
                // honest one, because the mask reaches *every* default with no cap and no branch to
                // decline. A synthetic slot is never omittable: `pythonx` always computes all three.
                paramHasDefault = (0 until declaredCount).map { function.allParameterDefaults.getOrElse(it) { false } } +
                    List(syntheticNames.size) { false },
                thunk = ThunkSpec(ownerInternalName, jvmMethodName, "(${paramDescriptors.joinToString("")})$returnDescriptor"),
            ),
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnTag,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = paramTags.getOrNull(index))
                },
            ),
        )
    }

    /** The name of the slot the composer arrives in. Not a Kotlin identifier on purpose -- it is the
     * compiler's own spelling, it is what `pythonx` matches on to recognise a composable, and no
     * keyword argument can collide with it. */
    internal const val COMPOSER_PARAMETER_NAME = "\$composer"
    private const val COMPOSER_TYPE_NAME = "androidx.compose.runtime.Composer"

    /** A boundary tag for one JVM slot of a composable, which is the **compiled** shape rather than
     * the declared one: the thunk calls the erased signature, so a `Color` parameter is the `long`
     * it erases to. `null` declines the whole declaration. */
    private fun composableSlotTagOf(descriptor: String): String? = when {
        descriptor == "C" || descriptor == "V" -> null
        else -> boundaryTypeOf(descriptor)?.tag
            ?: if (descriptor.startsWith("L") || descriptor.startsWith("[")) "OBJECT" else null
    }

    /**
     * Numbers the thunks of one walk and writes each composable's body around its number.
     *
     * Deliberately the last pass and deliberately over the whole walk: [disambiguateOverloads] can
     * turn a bound candidate into a declined one, and a thunk emitted for a declaration nothing calls
     * would be a method in the generated class with no caller -- harmless, but it would also make
     * [thunkSpecsOf]'s density check a lie.
     */
    private fun assignThunkIndices(candidates: List<Candidate>): List<Candidate> {
        var next = 0
        return candidates.map { candidate ->
            val callable = candidate.callable ?: return@map candidate
            if (callable.thunk == null) return@map candidate
            val index = next++
            candidate.copy(
                callable = callable.copy(
                    thunkIndex = index,
                    lambdaBody = "{ args -> $THUNK_CLASS_TOKEN.${thunkMethodName(index)}(args) }",
                ),
            )
        }
    }

    /** The model for a declaration that will not be bound, with the reason. The Kotlin types are
     * still read: a stub generator has to be able to say *what* was declined. */
    private fun declinedCandidate(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        reason: String,
        isComposable: Boolean,
    ): Candidate? {
        val declaration = declarationModelOf(owner, ownerIsClass, function, classpath, isComposable) ?: return null
        return Candidate(callable = null, declaration = declaration.copy(bindingName = null, declineReason = reason))
    }

    /**
     * The declared shape of one Kotlin function, independent of whether the boundary can carry it.
     *
     * `null` when some part of the signature has no name a stub could write -- a type *parameter*, a
     * flexible type. `docs/pyi-generation-design.md` §3.1's last row and §7: `BindingPolicy` rejects
     * generic declarations, and stubbing what cannot be called would be a lie.
     */
    private fun declarationModelOf(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        isComposable: Boolean,
    ): DeclarationModel? {
        val receiverIndex = if (function.isExtension) 1 else 0
        val receiverModel = function.receiverType?.let { kotlinTypeModelOf(it, classpath) ?: return null }
        val parameters = function.allParameterTypes.drop(receiverIndex).mapIndexed { index, type ->
            DeclaredParameter(
                name = function.allParameterNames.getOrNull(index + receiverIndex),
                type = kotlinTypeModelOf(type, classpath) ?: return null,
                declaresDefault = function.allParameterDefaults.getOrNull(index + receiverIndex) ?: false,
            )
        }
        return DeclarationModel(
            simpleName = function.kotlinName,
            owner = owner,
            ownerIsClass = ownerIsClass,
            receiver = receiverModel,
            parameters = parameters,
            returnType = kotlinTypeModelOf(function.returnType, classpath) ?: return null,
            isComposable = isComposable,
            isSuspend = function.isSuspend,
        )
    }

    private fun candidateFromFunction(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        isComposable: Boolean,
    ): Candidate? {
        val model = declarationModelOf(owner, ownerIsClass, function, classpath, isComposable) ?: return null
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }
        val receiverIndex = if (function.isExtension) 1 else 0

        val resolvedParams = function.allParameterTypes.map { type ->
            resolveKotlinType(type, classpath, BoundaryDirection.PARAMETER)
                ?: return declined("no boundary type for parameter ${kotlinClassifierNameOf(type) ?: type.classifier}")
        }
        val returnType = resolveKotlinType(function.returnType, classpath, BoundaryDirection.RETURN)
            ?: return declined("no boundary type for return ${kotlinClassifierNameOf(function.returnType) ?: function.returnType.classifier}")
        // Declared, not marshalled: a `Dp` parameter's tag is FLOAT and its declared name is
        // `androidx.compose.ui.unit.Dp`. `docs/pythonx-adapter-design.md` §2.4 row 4.
        val paramTypeNames = function.allParameterTypes.map {
            kotlinClassifierNameOf(it) ?: return declined("unnameable parameter type")
        }
        val returnTypeName = kotlinClassifierNameOf(function.returnType)

        val qualifiedName = "$owner.${function.kotlinName}"
        val argumentExpressions = resolvedParams.mapIndexed { index, type -> type.read("args[$index]") }

        // Kotlin has no syntax to call an extension function by fully qualifying it the way an
        // ordinary top-level function or a Java static can be (`pkg.fn(args)`) -- the receiver can
        // never be a positional argument, only `receiver.fn(args)`. An import is the only way to
        // name it without writing the receiver's own type out, which this walker does not otherwise
        // need to know.
        val alias = "artifact_ext_" + qualifiedName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        val imports =
            if (function.isExtension) listOf("import $owner.${function.kotlinName} as $alias") else emptyList()

        /** The call that passes everything, positionally -- byte for byte what this generator
         * emitted before defaults existed, and still the `else`-less first branch below. */
        val call = if (function.isExtension) {
            "${argumentExpressions.first()}.$alias(${argumentExpressions.drop(1).joinToString(", ")})"
        } else {
            "$owner.${function.kotlinName}(${argumentExpressions.joinToString(", ")})"
        }

        val arity = resolvedParams.size
        // The all-present body, and **only** that one, is decided here. Which arguments may be left
        // out cannot be: it depends on what else carries this Kotlin name, and an overload set is a
        // property of a package that one file does not see (this object's KDoc, and
        // `docs/kotlin-extensions-in-python.md` §2.5's 11 split pairs). [applyDefaultOmission] runs
        // over the finished walk and fills this in.
        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = resolvedParams.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ ${returnType.wrapReturn(call)} }"
            else "{ args -> ${returnType.wrapReturn(call)} }",
            imports = imports,
            receiverTypeName = function.receiverType?.let { kotlinClassifierNameOf(it) },
            paramNames = function.allParameterNames,
            paramTypeNames = paramTypeNames,
            returnTypeName = returnTypeName,
            // Deliberately all `false` until [applyDefaultOmission] says otherwise: this column is a
            // statement about **this body**, and this body passes everything.
            paramHasDefault = List(resolvedParams.size) { false },
        )
        val omittable = omittableParameterIndices(function, receiverIndex)
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                receiverBoundaryTag = if (function.isExtension) resolvedParams.first().tag else null,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = resolvedParams[index + receiverIndex].tag)
                },
            ),
            plan = if (omittable.isEmpty()) null else DefaultOmissionPlan(
                omittable = omittable.sorted(),
                valueIndices = (receiverIndex until resolvedParams.size).toList(),
                declaredDefaults = function.allParameterDefaults,
                buildBody = { allowed ->
                    val branched = presenceBranchedCall(
                        function, owner, alias, argumentExpressions, receiverIndex,
                        qualifiedName, omittable.sorted(), call, allowed,
                    )
                    "{ args -> ${returnType.wrapReturn(branched)} }"
                },
            ),
        )
    }

    /**
     * What one bound declaration would need in order to offer default omission, held until the whole
     * walk is visible.
     *
     * The decision cannot be local. Kotlin resolves `receiver.padding()` against **every** overload
     * of `padding` that is applicable, so whether a branch that writes no argument compiles at all is
     * a fact about the other members of the overload set -- and those may be in another file, another
     * facade or another multi-file part. Building the body eagerly and pruning it afterwards was
     * tried and is worse: the pruning would be textual.
     *
     * Measured, not anticipated. Emitting every subset unconditionally failed to compile
     * `foundation-layout-desktop-1.6.11` in six places -- `WindowInsets(Dp×4)` against
     * `WindowInsets(Int×4)`, `paddingFromBaseline(Dp,Dp)` against `(TextUnit,TextUnit)`, and
     * `paddingFrom(AlignmentLine,Dp,Dp)` against its `TextUnit` twin -- each one the branch that
     * writes nothing, each one *"Overload resolution ambiguity between candidates"*.
     */
    internal class DefaultOmissionPlan(
        /** Indices into `allParameterTypes` that declare a default and that this generator is
         * willing to write a call without. */
        val omittable: List<Int>,
        /** Every value-parameter index, the extension receiver excluded. */
        val valueIndices: List<Int>,
        /** What the *declaration* says, as opposed to what the binding will offer -- the sibling test
         * needs Kotlin's own view, because Kotlin is what resolves the generated call. */
        val declaredDefaults: List<Boolean>,
        /** @param allowed the omission sets to generate a call for. Everything else becomes a branch
         *   that refuses at run time; see [presenceBranchedCall]. */
        val buildBody: (allowed: Set<Set<Int>>) -> String,
    )

    /**
     * How many defaulted parameters one declaration may have before the generator stops offering
     * omission at all.
     *
     * Presence branching costs **one generated call expression per subset** of the defaulted
     * parameters, so the cost is 2^n and it is paid at build time, on every build, by whoever asked
     * for the package. Measured over everything the walker binds today -- all of
     * `foundation-layout-desktop-1.6.11`, `kotlin-stdlib`'s `kotlin.text` and the whole JUnit 4 jar
     * -- the widest bound declaration declares **four** defaults (`padding(start, top, end, bottom)`
     * and six others), so 64 is well clear of the corpus while bounding an artefact nobody has
     * measured.
     *
     * `docs/pythonx-adapter-design.md` §4.5 rejects presence branching outright on "2^15 branches for
     * `Text`". That objection is to an unbounded version of it and, separately, to a case that is not
     * in the table: `Text` is a `@Composable`, and the arity check in [kotlinCandidates] declines
     * every composable for the synthetic `$composer`/`$changed` parameters its JVM signature carries.
     * If composables ever become bindable they will arrive with 15+ defaults and land past this cap
     * -- which is the honest outcome, because the mechanism that suits them is the `$default` mask
     * §5.2 found is a *declared* trailing parameter there, not this one.
     */
    internal const val MAX_OMITTABLE_PARAMETERS = 6

    /**
     * The slots the generated body will let Python leave out, as indices into `allParameterTypes`.
     *
     * Empty means "this declaration is bound exactly as it was before defaults existed". Four
     * separate reasons produce that, and all four are deliberate:
     *
     * 1. nothing declares a default -- the common case, 43 of `foundation-layout`'s 70 entries;
     * 2. more than [MAX_OMITTABLE_PARAMETERS] do (see there);
     * 3. a parameter name is missing or is not one a named argument can be written from. Every
     *    partial branch names what it passes, because an omission that is not a trailing one has no
     *    positional spelling, so a nameless parameter makes the whole set unusable;
     * 4. the receiver, always. It is positional in Kotlin and [RECEIVER_PARAMETER_NAME] is
     *    deliberately not an identifier.
     */
    private fun omittableParameterIndices(function: ResolvedFunction, receiverIndex: Int): Set<Int> {
        val names = function.allParameterNames
        if (names.size != function.allParameterTypes.size) return emptySet()
        val indices = function.allParameterDefaults.indices.filter { index ->
            index >= receiverIndex &&
                function.allParameterDefaults.getOrElse(index) { false } &&
                names[index] != RECEIVER_PARAMETER_NAME
        }
        if (indices.isEmpty() || indices.size > MAX_OMITTABLE_PARAMETERS) return emptySet()
        // Every *passed* argument in a partial branch is named, not only the defaulted ones, so a
        // name this cannot write anywhere in the signature sinks the whole set rather than just its
        // own slot.
        if (names.drop(receiverIndex).any { !isWritableParameterName(it) }) return emptySet()
        return indices.toSet()
    }

    /**
     * Decides, over the finished walk, which omission sets each planned declaration may offer, and
     * rewrites its body and its `paramHasDefault` accordingly.
     *
     * ### The rule
     *
     * A branch that writes the arguments *W* is refused when **any sibling overload of the same
     * Kotlin name would also accept exactly *W***: same receiver, a parameter of the same name and
     * declared type for every member of *W*, and a default on everything else it declares. That is
     * the shape Kotlin reports as `Overload resolution ambiguity`, and the generated call has no way
     * to break the tie -- there are no arguments left to type-annotate, which is exactly why the tie
     * exists.
     *
     * Deliberately **not** a model of Kotlin's specificity rules. Kotlin does resolve some of these
     * (it prefers the two-`Dp` `padding` over the four-`Dp` one when nothing is written, on parameter
     * count), and reproducing that here would mean a generated call silently binding to a *different
     * declaration* than the table entry names. Refusing the branch instead costs one spelling --
     * `padding__Dp_Dp()` with no arguments -- and buys that every branch calls the declaration its
     * entry is named after.
     *
     * ### What a refused branch becomes
     *
     * Not a missing branch: `pythonx` reads `paramHasDefault` per slot and cannot express "these two
     * but not both at once", so the call is reachable and has to answer for itself. It throws, naming
     * the declaration and saying what to write, which is the same answer
     * [disambiguateOverloads] gives one level up for the same reason.
     */
    private fun applyDefaultOmission(candidates: List<Candidate>): List<Candidate> {
        if (candidates.none { it.plan != null }) return candidates
        val byName = candidates.filter { it.callable != null }.groupBy { it.callable!!.name }
        return candidates.map { candidate ->
            val plan = candidate.plan ?: return@map candidate
            val callable = candidate.callable ?: return@map candidate
            val siblings = byName[callable.name].orEmpty().filter { it !== candidate }
            val allowed = plan.omittable.powerSet()
                .filter { it.isNotEmpty() && !anySiblingAlsoAccepts(candidate, plan, siblings, it) }
                .toSet()
            if (allowed.isEmpty()) return@map candidate.copy(plan = null)
            val omittableNow = plan.omittable.filter { index -> allowed.any { index in it } }.toSet()
            Candidate(
                callable = callable.copy(
                    lambdaBody = plan.buildBody(allowed),
                    // The binding's contract, not the declaration's: exactly the slots the rewritten
                    // body has a call for that does not mention them. `pythonx._bind` fills the
                    // sentinel from this, so it has to describe the body and not the Kotlin source.
                    paramHasDefault = callable.paramHasDefault.indices.map { it in omittableNow },
                ),
                declaration = candidate.declaration.copy(
                    // Kept in step one consumer further out: `PyiRendering` writes `= ...` from this
                    // field, and a stub promising an omission the binding refuses would type-check at
                    // the call site and fail at run time -- `docs/pyi-generation-design.md` §3.2's
                    // rule about parameter names, applied to their defaults.
                    parameters = candidate.declaration.parameters.mapIndexed { index, parameter ->
                        parameter.copy(declaresDefault = plan.valueIndices[index] in omittableNow)
                    },
                ),
            )
        }
    }

    private fun anySiblingAlsoAccepts(
        candidate: Candidate,
        plan: DefaultOmissionPlan,
        siblings: List<Candidate>,
        omitted: Set<Int>,
    ): Boolean {
        val callable = candidate.callable ?: return false
        val written = plan.valueIndices.filter { it !in omitted }
            .map { callable.paramNames[it] to callable.paramTypeNames[it] }
        return siblings.any { sibling ->
            val other = sibling.callable ?: return@any false
            if (other.receiverTypeName != callable.receiverTypeName) return@any false
            val otherValues = other.paramNames.indices
                .filter { other.paramNames[it] != RECEIVER_PARAMETER_NAME }
                .map { Triple(other.paramNames[it], other.paramTypeNames.getOrNull(it), plan.siblingDeclares(sibling, it)) }
            written.all { (name, type) -> otherValues.any { it.first == name && it.second == type } } &&
                otherValues.filterNot { written.any { w -> w.first == it.first && w.second == it.second } }
                    .all { it.third }
        }
    }

    /** The sibling's *declared* defaults, which is Kotlin's view and therefore the one that decides
     * applicability. `ArtifactCallable.paramHasDefault` is the binding's view and is not it -- at
     * this point in the walk it is still all `false` for everyone. */
    private fun DefaultOmissionPlan.siblingDeclares(sibling: Candidate, index: Int): Boolean {
        val receiverIndex = if (sibling.callable?.receiverTypeName != null) 1 else 0
        return sibling.declaration.parameters.getOrNull(index - receiverIndex)?.declaresDefault ?: false
    }

    private fun List<Int>.powerSet(): List<Set<Int>> =
        (0 until (1 shl size)).map { mask -> filterIndexed { bit, _ -> (mask shr bit) and 1 == 1 }.toSet() }

    /**
     * One `when` over which defaulted slots arrived as `null`, with one call expression per subset.
     *
     * The subject is a bitmask built from the sentinel tests themselves rather than a chain of
     * `if`s, so the generated text is 2^n *branches* but only n null tests, and reads as a table of
     * the omission sets rather than as nested conditionals. Mask bit `j` is set when the `j`-th
     * omittable slot was **left out**, so mask `0` is [allPresentCall] -- unchanged from what this
     * generator emitted before -- and the all-omitted mask becomes the `else` Kotlin requires.
     *
     * A mask outside [allowed] is a call [applyDefaultOmission] refused to generate. It becomes a
     * `throw` rather than a silent fallback to the all-present call, which would pass a `null` into
     * a cast and fail somewhere else entirely.
     */
    private fun presenceBranchedCall(
        function: ResolvedFunction,
        owner: String,
        alias: String,
        argumentExpressions: List<String>,
        receiverIndex: Int,
        qualifiedName: String,
        ordered: List<Int>,
        allPresentCall: String,
        allowed: Set<Set<Int>>,
    ): String {
        val names = function.allParameterNames
        val subject = ordered.mapIndexed { bit, index ->
            "(if (args[$index] == null) ${1 shl bit} else 0)"
        }.joinToString(" + ")

        fun callOmitting(omitted: Set<Int>): String {
            val arguments = argumentExpressions.indices
                .filter { it >= receiverIndex && it !in omitted }
                .joinToString(", ") { "${writeParameterName(names[it])} = ${argumentExpressions[it]}" }
            return if (function.isExtension) "${argumentExpressions.first()}.$alias($arguments)"
            else "$owner.${function.kotlinName}($arguments)"
        }

        val last = (1 shl ordered.size) - 1
        return buildString {
            appendLine("when ($subject) {")
            for (mask in 0..last) {
                val omitted = ordered.filterIndexed { bit, _ -> (mask shr bit) and 1 == 1 }.toSet()
                val label = if (mask == last) "else" else "$mask"
                val branch = when {
                    mask == 0 -> allPresentCall
                    omitted in allowed -> callOmitting(omitted)
                    else -> "throw IllegalArgumentException(" +
                        "\"$qualifiedName: leaving out " +
                        omitted.joinToString(" and ") { names[it] } +
                        " is ambiguous with another overload of the same name; write ${
                            if (omitted.size == 1) "it" else "at least one of them"
                        }\")"
                }
                appendLine("    $label -> $branch")
            }
            append("}")
        }
    }

    /** Kotlin's hard keywords: a parameter declared with one carries backticks in source and none in
     * `@Metadata`, so a named argument written from the metadata name alone would not parse. */
    private val KOTLIN_HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    private fun isWritableParameterName(name: String): Boolean =
        name.isNotEmpty() && name.first().let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }

    private fun writeParameterName(name: String): String =
        if (name in KOTLIN_HARD_KEYWORDS) "`$name`" else name

    private fun candidateFromDescriptor(owner: String, methodName: String, descriptor: String): Candidate? {
        val (paramDescriptors, returnDescriptor) = splitMethodDescriptor(descriptor)
        val returnType = boundaryTypeOf(returnDescriptor) ?: return null
        val paramTypes = paramDescriptors.map { boundaryTypeOf(it) ?: return null }
        if (paramTypes.any { it.isReturnOnly }) return null

        val argumentExpressions = paramTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val call = "$owner.$methodName(${argumentExpressions.joinToString(", ")})"
        val body = returnType.wrapReturn(call)
        val paramTypeNames = paramDescriptors.map { kotlinNameOfAdmittedDescriptor(it) }
        val returnTypeName = kotlinNameOfAdmittedDescriptor(returnDescriptor)
        val callable = ArtifactCallable(
            name = "$owner.$methodName",
            arity = paramTypes.size,
            paramTags = paramTypes.map { it.tag },
            returnTag = returnType.tag,
            // Arity 0 has no `args` to name, exactly as `FragmentScanner`'s static-getter bodies do.
            lambdaBody = if (paramTypes.isEmpty()) "{ $body }" else "{ args -> $body }",
            // `paramNames` stays empty: a Java class file carries parameter names only when it was
            // compiled with `-parameters`, and JUnit 4 was not. Empty means "not supplied" (see
            // `ExposedCallable.paramNames`), which is the truth here rather than an invented `arg0`.
            paramTypeNames = paramTypeNames,
            returnTypeName = returnTypeName,
        )
        return Candidate(
            callable = callable,
            declaration = DeclarationModel(
                simpleName = methodName,
                owner = owner,
                ownerIsClass = true,
                receiver = null,
                // `docs/pyi-generation-design.md` §3.2: the names are genuinely absent, and a wrong
                // keyword name is worse than no keyword name because it type-checks at the call site
                // and fails at run time.
                parameters = paramTypeNames.mapIndexed { index, name ->
                    DeclaredParameter(null, KotlinTypeModel(name), declaresDefault = false, boundaryTag = paramTypes[index].tag)
                },
                returnType = KotlinTypeModel(returnTypeName),
                returnBoundaryTag = returnType.tag,
                bindingName = "$owner.$methodName",
                parameterNamesKnown = false,
            ),
        )
    }

    /**
     * The Kotlin spelling of a descriptor [boundaryTypeOf] has **already admitted**, which is the
     * only reason this can be total: the admitted set is the primitives, `String`, `byte[]` and
     * `void`, all of which have a Kotlin name. It is not a general descriptor-to-Kotlin mapping --
     * see [boundaryTypeOf]'s KDoc for why no such mapping exists.
     */
    private fun kotlinNameOfAdmittedDescriptor(descriptor: String): String = when (descriptor) {
        "Z" -> "kotlin.Boolean"
        "B" -> "kotlin.Byte"
        "S" -> "kotlin.Short"
        "I" -> "kotlin.Int"
        "J" -> "kotlin.Long"
        "F" -> "kotlin.Float"
        "D" -> "kotlin.Double"
        "Ljava/lang/String;" -> "kotlin.String"
        "[B" -> "kotlin.ByteArray"
        "V" -> "kotlin.Unit"
        else -> error("descriptor $descriptor is not one boundaryTypeOf admits")
    }

    private fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0
}
