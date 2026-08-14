package python.multiplatform.gradle.artifact

import org.objectweb.asm.ClassReader
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
 * PyREPL drops private and protected members, `<init>`, `Companion`, and **any name containing
 * `-`**. Judged against what a *binding* needs rather than what a stub needs:
 *
 * | PyREPL's filter | here | why |
 * |---|---|---|
 * | not `private`/`protected` | **not enough** — must be `public` | PyREPL keeps package-private members, which is harmless in a stub. Generated Kotlin lives in another package and cannot call one; `kotlin.text.StringsKt__IndentKt` is exactly that shape |
 * | drop `<init>` | kept | a constructor needs a `ReflectedClass` and a receiver handle, which is the next step |
 * | drop `Companion` | subsumed | only statics are bound, and `Companion` is an instance field |
 * | drop names containing `-` | **kept, and load-bearing** | see below |
 * | (none) | drop non-`static` | an instance method needs a receiver; see [ArtifactCallable] |
 * | (none) | drop unbindable types | see [boundaryTypeOf] |
 * | (none) | drop ambiguous overloads | see [scanClassNode] |
 *
 * **The `-` filter is not a stub-only convenience.** A hyphen in a JVM method name is Kotlin's
 * value-class mangling suffix, and the reason it cannot simply be stripped is that a value class
 * *erases to the type it wraps*: `Duration` is a `long`, so `getInWholeSeconds-impl(J)J` passes
 * [boundaryTypeOf]'s type filter while meaning something entirely different from `long -> long`.
 * The name is the only place the bytecode still admits it. PyREPL's instinct was right, and it holds
 * more strongly here than it did there.
 *
 * ### What this cannot do, and what it would take
 *
 * Only declarations callable from Kotlin *by their JVM shape* are bound: a Java static, or a Kotlin
 * `@JvmStatic`. A Kotlin top-level function is not one -- see
 * `ArtifactScannerTest.kotlinFileFacadesAreSkippedBecauseKotlinCannotNameThem`, which pins the
 * `@Metadata` kinds involved against the real `kotlin-stdlib`. Recovering Kotlin's own names,
 * extension receivers, property/function distinction and value-class parameters means decoding
 * `@Metadata`'s `d1`/`d2`, which is `kotlin-metadata-jvm`'s job and not ASM's.
 */
internal object ArtifactScanner {

    /** `kotlin.Metadata` is `RUNTIME`-retained, so ASM reads it as an ordinary visible annotation --
     * no metadata library needed for the *kind*, only for the payload. */
    private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

    /** `k = 1`: an ordinary class, object or interface. Every other kind is a compiler-invented
     * carrier -- 2 file facade, 3 synthetic class, 4 multi-file facade, 5 multi-file part -- whose
     * JVM name has no Kotlin spelling. */
    private const val KOTLIN_KIND_CLASS = 1

    /**
     * Every binding this jar offers, sorted by name.
     *
     * @param includePrefixes package or class names, each matched as a *namespace*: `junit.runner`
     *   covers `junit.runner.Version` but `junit.run` covers nothing. Empty means every class in the
     *   jar, which is what the type filter makes affordable.
     */
    fun scanJar(jar: File, includePrefixes: List<String>): List<ArtifactCallable> {
        val entries = mutableListOf<ArtifactCallable>()
        JarFile(jar).use { file ->
            file.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                // A nested class's binary name uses `$`, which is not how Kotlin spells the
                // qualified name (`Outer.Inner`), and `$1` anonymous classes have no Kotlin name at
                // all. Skipped wholesale, as PyREPL does.
                .filter { '$' !in it.name }
                .filter { matchesInclude(binaryNameToQualified(it.name.removeSuffix(".class")), includePrefixes) }
                .forEach { entry ->
                    val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                    entries += scanClassNode(node)
                }
        }
        return entries.sortedBy { it.name }
    }

    /**
     * The `k` of each named class's `kotlin.Metadata`, or absent from the map when the class carries
     * none. Exposed for `ArtifactScannerTest` to pin against a real Kotlin artefact, since it is the
     * fact that bounds what this walker can reach.
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

    /**
     * A class file the running ASM cannot parse is skipped, not fatal.
     *
     * `ClassReader` throws `IllegalArgumentException` for a class-file version newer than it knows,
     * and a build that resolves one jar compiled with a newer JDK than this plugin's ASM must not
     * fail outright -- the binding for that jar is simply absent, which is the same outcome as not
     * asking for it.
     */
    private fun readClassNodeOrNull(bytes: ByteArray): ClassNode? = try {
        ClassNode().also {
            ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    } catch (_: IllegalArgumentException) {
        null
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

    /** A namespace match, not a text one: `junit.run` is a prefix of the string `junit.runner` and
     * of no package or class. */
    private fun matchesInclude(qualifiedName: String, includePrefixes: List<String>): Boolean =
        includePrefixes.isEmpty() || includePrefixes.any {
            qualifiedName == it || qualifiedName.startsWith("$it.")
        }

    private fun scanClassNode(node: ClassNode): List<ArtifactCallable> {
        if (!node.access.hasFlag(Opcodes.ACC_PUBLIC)) return emptyList()
        if (node.access.hasFlag(Opcodes.ACC_SYNTHETIC) or node.access.hasFlag(Opcodes.ACC_ANNOTATION)) return emptyList()
        // A compiler-invented carrier: its JVM name is not a name in the Kotlin namespace, so
        // generated Kotlin cannot write a call through it. See this object's KDoc.
        kotlinMetadataKind(node)?.let { if (it != KOTLIN_KIND_CLASS) return emptyList() }

        val owner = binaryNameToQualified(node.name)

        // Grouped by name first, and a group with more than one member is dropped entire. Ambiguity
        // is counted over what *would be bound*, so an overload the type filter already declined
        // does not make its sibling ambiguous.
        return node.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) && it.access.hasFlag(Opcodes.ACC_STATIC) }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") }
            // `$` is a Kotlin/Java synthetic (`fn$default`, `access$000`); `-` is value-class
            // mangling, which the type filter cannot catch because a value class erases to the type
            // it wraps. See this object's KDoc.
            .filter { '$' !in it.name && '-' !in it.name }
            .mapNotNull { callableOrNull(owner, it.name, it.desc) }
            .groupBy { it.name }
            .filterValues { it.size == 1 }
            .values
            .map { it.single() }
            .sortedBy { it.name }
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
