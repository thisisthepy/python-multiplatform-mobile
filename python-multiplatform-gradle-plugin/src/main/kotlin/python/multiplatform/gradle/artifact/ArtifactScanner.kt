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
        // Over the whole walk rather than per class: an overload set is a property of a *package*,
        // and Kotlin lets one live in two files. `docs/kotlin-extensions-in-python.md` §2.5 counts 11
        // such pairs in Compose alone -- two file facades, two `ClassNode`s, one Kotlin name -- which
        // a per-class grouping cannot see and would have emitted twice under one table key.
        return disambiguateOverloads(entries)
    }

    /**
     * One declaration, seen by both consumers of this walk at the moment it is read.
     *
     * [callable] is `null` exactly when the binder declined it, and [declaration] says why. Keeping
     * them together up to the end of the walk is what makes `docs/pyi-generation-design.md` §2.2's
     * "the two renderers cannot drift" a property of the code rather than a convention.
     */
    private data class Candidate(val callable: ArtifactCallable?, val declaration: DeclarationModel)

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
    private fun disambiguateOverloads(candidates: List<Candidate>): List<Candidate> {
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
        val parts = paramTypeNames.drop(skipReceiver)
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
                val (paramDescriptors, _) = splitMethodDescriptor(method.desc)
                if (function.allParameterTypes.size != paramDescriptors.size) {
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

    /** `@Composable` is `RUNTIME`-retained, so ASM sees it without any metadata decoding. Read here
     * because the *name* rule depends on it -- `docs/pyi-generation-design.md` §3.6: composables stay
     * PascalCase where every other function becomes snake_case. No composable is bindable today (the
     * arity check above declines every one of them for its synthetic parameters), so this is carried
     * for the model's sake and for the day that changes. */
    private fun isComposable(method: org.objectweb.asm.tree.MethodNode): Boolean =
        method.visibleAnnotations?.any { it.desc == "Landroidx/compose/runtime/Composable;" } == true

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

        val (call, imports) = if (function.isExtension) {
            // Kotlin has no syntax to call an extension function by fully qualifying it the way an
            // ordinary top-level function or a Java static can be (`pkg.fn(args)`) -- the receiver
            // can never be a positional argument, only `receiver.fn(args)`. An import is the only
            // way to name it without writing the receiver's own type out, which this walker does not
            // otherwise need to know.
            val alias = "artifact_ext_" + qualifiedName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            val receiverExpression = argumentExpressions.first()
            val remainingArguments = argumentExpressions.drop(1)
            ("$receiverExpression.$alias(${remainingArguments.joinToString(", ")})") to
                listOf("import $owner.${function.kotlinName} as $alias")
        } else {
            ("$owner.${function.kotlinName}(${argumentExpressions.joinToString(", ")})") to emptyList()
        }

        val body = returnType.wrapReturn(call)
        val arity = resolvedParams.size
        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = resolvedParams.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            imports = imports,
            receiverTypeName = function.receiverType?.let { kotlinClassifierNameOf(it) },
            paramNames = function.allParameterNames,
            paramTypeNames = paramTypeNames,
            returnTypeName = returnTypeName,
            paramHasDefault = function.allParameterDefaults,
        )
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
        )
    }

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
