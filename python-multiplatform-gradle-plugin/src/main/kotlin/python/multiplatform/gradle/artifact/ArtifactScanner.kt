package python.multiplatform.gradle.artifact

import kotlin.metadata.jvm.KotlinClassMetadata
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
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
 * | (none) | drop ambiguous overloads | see [scanClassNode] |
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
    fun scanJar(target: File, includePrefixes: List<String> = emptyList(), classpath: List<File> = listOf(target)): List<ArtifactCallable> {
        val artifactClasspath = ArtifactClasspath(classpath)
        val entries = mutableListOf<ArtifactCallable>()
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
        return entries.sortedBy { it.name }
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

    private fun scanClassNode(node: ClassNode, classpath: ArtifactClasspath): List<ArtifactCallable> {
        if (!node.access.hasFlag(Opcodes.ACC_PUBLIC)) return emptyList()
        if (node.access.hasFlag(Opcodes.ACC_SYNTHETIC) or node.access.hasFlag(Opcodes.ACC_ANNOTATION)) return emptyList()

        // Ambiguity is counted over what *would be bound*, over the whole group a facade
        // contributes -- not per class -- so ambiguity introduced by splitting one Kotlin name
        // across two multi-file parts is still caught. See this object's KDoc.
        val candidates: List<ArtifactCallable> = when (val metadata = kotlinClassMetadataOf(node)) {
            // No `kotlin.Metadata` at all: a Java class (or a Kotlin class whose metadata payload
            // this library could not decode -- declined the same way unparsable bytecode is, not
            // fatal). Bound the same way this walker always has, by JVM descriptor alone.
            null -> javaStaticCandidates(node)
            is KotlinClassMetadata.Class -> kotlinCandidates(
                owner = binaryNameToQualified(node.name),
                functions = functionsOf(metadata.kmClass).filterNot { it.isExtension },
                ownerNode = node,
                classpath = classpath,
            )
            is KotlinClassMetadata.FileFacade -> kotlinCandidates(
                owner = packageNameOf(node.name),
                functions = functionsOf(metadata.kmPackage),
                ownerNode = node,
                classpath = classpath,
            )
            is KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.flatMap { partBinaryName ->
                val partNode = classpath.classNode(partBinaryName) ?: return@flatMap emptyList()
                val partMetadata = kotlinClassMetadataOf(partNode) as? KotlinClassMetadata.MultiFileClassPart
                    ?: return@flatMap emptyList()
                kotlinCandidates(
                    owner = packageNameOf(partBinaryName),
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

        return candidates.groupBy { it.name }.filterValues { it.size == 1 }.values.map { it.single() }
    }

    private fun javaStaticCandidates(node: ClassNode): List<ArtifactCallable> {
        val owner = binaryNameToQualified(node.name)
        return node.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) && it.access.hasFlag(Opcodes.ACC_STATIC) }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") }
            // `$` is a Kotlin/Java synthetic (`fn$default`, `access$000`). `-` would be value-class
            // mangling, but nothing reaches this branch *with* Kotlin metadata to interpret it by --
            // a name like that here is unreadable, not merely unsupported, so it stays declined.
            .filter { '$' !in it.name && '-' !in it.name }
            .mapNotNull { callableOrNull(owner, it.name, it.desc) }
    }

    private fun kotlinCandidates(
        owner: String,
        functions: List<ResolvedFunction>,
        ownerNode: ClassNode,
        classpath: ArtifactClasspath,
    ): List<ArtifactCallable> {
        val bySignature = functions.associateBy { it.jvmSignature.name to it.jvmSignature.descriptor }
        return ownerNode.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) && it.access.hasFlag(Opcodes.ACC_STATIC) }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") && '$' !in it.name }
            .mapNotNull { method ->
                val function = bySignature[method.name to method.desc] ?: return@mapNotNull null
                // A member whose Kotlin-declared parameter count does not match its JVM parameter
                // count has an implicit JVM parameter metadata does not account for -- a value
                // class's own instance turned into an unboxed receiver. There is no declared
                // parameter to bind it to, so it is declined here rather than misread as one fewer
                // parameter than the method actually takes. See this object's KDoc.
                val (paramDescriptors, _) = splitMethodDescriptor(method.desc)
                if (function.allParameterTypes.size != paramDescriptors.size) return@mapNotNull null
                buildCallableFromFunction(owner, function, classpath)
            }
    }

    private fun buildCallableFromFunction(owner: String, function: ResolvedFunction, classpath: ArtifactClasspath): ArtifactCallable? {
        val resolvedParams = function.allParameterTypes.map {
            resolveKotlinType(it, classpath, BoundaryDirection.PARAMETER) ?: return null
        }
        val returnType = resolveKotlinType(function.returnType, classpath, BoundaryDirection.RETURN) ?: return null

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
        return ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = resolvedParams.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            imports = imports,
        )
    }

    private fun callableOrNull(owner: String, methodName: String, descriptor: String): ArtifactCallable? {
        val (paramDescriptors, returnDescriptor) = splitMethodDescriptor(descriptor)
        val returnType = boundaryTypeOf(returnDescriptor) ?: return null
        val paramTypes = paramDescriptors.map { boundaryTypeOf(it) ?: return null }
        if (paramTypes.any { it.isReturnOnly }) return null

        val argumentExpressions = paramTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val call = "$owner.$methodName(${argumentExpressions.joinToString(", ")})"
        val body = returnType.wrapReturn(call)
        return ArtifactCallable(
            name = "$owner.$methodName",
            arity = paramTypes.size,
            paramTags = paramTypes.map { it.tag },
            returnTag = returnType.tag,
            // Arity 0 has no `args` to name, exactly as `FragmentScanner`'s static-getter bodies do.
            lambdaBody = if (paramTypes.isEmpty()) "{ $body }" else "{ args -> $body }",
        )
    }

    private fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0
}
