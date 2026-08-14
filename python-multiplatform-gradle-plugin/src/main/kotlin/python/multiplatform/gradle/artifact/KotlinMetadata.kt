package python.multiplatform.gradle.artifact

import kotlin.metadata.KmClassifier
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.Visibility
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile

/**
 * The part of the walker that reads `@Metadata`'s `d1`/`d2` payload -- the facts
 * [ArtifactScanner]'s KDoc names as ASM's limit: a multi-file facade's part classes, an extension
 * function's receiver, a value class's underlying type and public accessor, `suspend`.
 *
 * Split from `ArtifactScanner` because it has its own dependency (`kotlin-metadata-jvm`, see the
 * plugin's `build.gradle.kts`) and its own unit of correctness: given a class's bytes, what did the
 * compiler actually declare, independent of what the walker chooses to bind.
 */

private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

/**
 * Every jar (or, for this module's own tests, every directory of `.class` files) a build resolved,
 * indexed once so a classifier can be looked up regardless of which root it actually lives in.
 *
 * Why this has to span more than the one jar being walked: `androidx.compose.foundation.layout`'s
 * `Modifier.padding(Dp)` declares `Dp` as a parameter, and `Dp` is compiled into
 * `androidx.compose.ui.unit`, a different artefact. A value class's underlying type and accessor
 * cannot be read without finding the class that declares them, and single-jar resolution would
 * decline every cross-artefact value-class parameter -- which is most of Compose's public surface.
 */
internal class ArtifactClasspath(roots: List<File>) {

    private val locationsByBinaryName: Map<String, File> by lazy {
        val map = LinkedHashMap<String, File>()
        roots.forEach { root ->
            when {
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { file ->
                        map.putIfAbsent(file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class"), root)
                    }
                root.isFile && root.name.endsWith(".jar") -> runCatching {
                    JarFile(root).use { jarFile ->
                        jarFile.entries().asSequence()
                            .filter { it.name.endsWith(".class") }
                            .forEach { entry -> map.putIfAbsent(entry.name.removeSuffix(".class"), root) }
                    }
                }
            }
        }
        map
    }

    private val nodeCache = mutableMapOf<String, ClassNode?>()

    /** @param binaryName e.g. `androidx/compose/ui/unit/Dp` -- no `.class` suffix. */
    fun classNode(binaryName: String): ClassNode? = nodeCache.getOrPut(binaryName) {
        val root = locationsByBinaryName[binaryName] ?: return@getOrPut null
        val bytes = readBytes(root, binaryName) ?: return@getOrPut null
        readClassNodeOrNull(bytes)
    }

    private fun readBytes(root: File, binaryName: String): ByteArray? = if (root.isDirectory) {
        root.resolve("$binaryName.class").takeIf { it.isFile }?.readBytes()
    } else {
        runCatching {
            JarFile(root).use { jarFile ->
                jarFile.getJarEntry("$binaryName.class")?.let { entry -> jarFile.getInputStream(entry).use { it.readBytes() } }
            }
        }.getOrNull()
    }
}

/**
 * A class file the running ASM cannot parse is skipped, not fatal -- see [ArtifactScanner]'s own
 * copy of this reasoning. Shared because [ArtifactClasspath] needs it for classes outside the jar
 * currently being walked.
 */
internal fun readClassNodeOrNull(bytes: ByteArray): ClassNode? = try {
    ClassNode().also {
        org.objectweb.asm.ClassReader(bytes).accept(
            it,
            org.objectweb.asm.ClassReader.SKIP_CODE or org.objectweb.asm.ClassReader.SKIP_DEBUG or org.objectweb.asm.ClassReader.SKIP_FRAMES,
        )
    }
} catch (_: IllegalArgumentException) {
    null
}

/** `null` when the class carries no `kotlin.Metadata` at all (a Java class), or when the payload
 * cannot be decoded (a metadata version this library does not understand -- declined, not fatal,
 * the same policy [readClassNodeOrNull] applies to bytecode ASM cannot parse). */
internal fun kotlinClassMetadataOf(node: ClassNode): KotlinClassMetadata? {
    val annotation = node.visibleAnnotations?.firstOrNull { it.desc == KOTLIN_METADATA_DESCRIPTOR } ?: return null
    val header = buildMetadataHeader(annotation) ?: return null
    return try {
        KotlinClassMetadata.readLenient(header)
    } catch (_: Exception) {
        null
    }
}

/**
 * Reconstructs a real `kotlin.Metadata` annotation instance from ASM's flat name/value view of it,
 * so `KotlinClassMetadata.readLenient` -- which wants the annotation, not its fields -- can decode
 * it. `kotlin.Metadata` is `RUNTIME`-retained (see [ArtifactScanner]'s KDoc), so every field ASM
 * hands back is exactly what the compiler wrote.
 */
private fun buildMetadataHeader(annotation: AnnotationNode): kotlin.Metadata? {
    val values = annotation.values ?: return null
    var kind: Int? = null
    var metadataVersion: IntArray? = null
    var data1: Array<String>? = null
    var data2: Array<String>? = null
    var extraString: String? = null
    var packageName: String? = null
    var extraInt: Int? = null
    var i = 0
    while (i < values.size - 1) {
        when (values[i] as? String) {
            "k" -> kind = values[i + 1] as? Int
            "mv" -> metadataVersion = (values[i + 1] as? List<*>)?.filterIsInstance<Int>()?.toIntArray()
            "d1" -> data1 = (values[i + 1] as? List<*>)?.filterIsInstance<String>()?.toTypedArray()
            "d2" -> data2 = (values[i + 1] as? List<*>)?.filterIsInstance<String>()?.toTypedArray()
            "xs" -> extraString = values[i + 1] as? String
            "pn" -> packageName = values[i + 1] as? String
            "xi" -> extraInt = values[i + 1] as? Int
        }
        i += 2
    }
    return kotlin.metadata.jvm.Metadata(
        kind = kind,
        metadataVersion = metadataVersion,
        data1 = data1,
        data2 = data2,
        extraString = extraString,
        packageName = packageName,
        extraInt = extraInt,
    )
}

/** Which side of the boundary a Kotlin type is being resolved for -- a value class needs a public
 * constructor to be read as a parameter, and a public accessor besides to be wrapped as a return. */
internal enum class BoundaryDirection { PARAMETER, RETURN }

/**
 * What a jar says about one `@JvmInline value class`: its own qualified name, its underlying type
 * (recursion base case: `kotlinPrimitiveBoundaryTypeOf` bottoms out, or another value class does),
 * and whether the two operations this walker needs -- construct one, read its wrapped value back
 * out -- are even public.
 *
 * `kotlin.time.Duration` is the case that motivates [propertyIsPublic] and [constructorIsPublic]
 * both existing rather than one boolean: its constructor is `internal`, so nothing outside the
 * stdlib module can ever construct one, in *either* direction. See
 * `ValueClassFixtures.kt`'s `Meters` for the shape that has both public.
 */
internal data class ValueClassInfo(
    val qualifiedName: String,
    val underlyingType: KmType,
    val constructorIsPublic: Boolean,
    val propertyName: String?,
    val propertyIsPublic: Boolean,
)

internal fun ArtifactClasspath.valueClassInfo(binaryName: String): ValueClassInfo? {
    val node = classNode(binaryName) ?: return null
    val metadata = kotlinClassMetadataOf(node) as? KotlinClassMetadata.Class ?: return null
    val kmClass = metadata.kmClass
    if (!kmClass.isValue) return null
    val underlyingType = kmClass.inlineClassUnderlyingType ?: return null
    val propertyName = kmClass.inlineClassUnderlyingPropertyName
    val constructorIsPublic = kmClass.constructors.any { it.visibility == Visibility.PUBLIC && it.valueParameters.size == 1 }
    val propertyIsPublic = propertyName != null &&
        kmClass.properties.any { it.name == propertyName && it.visibility == Visibility.PUBLIC }
    return ValueClassInfo(
        qualifiedName = binaryName.replace('/', '.'),
        underlyingType = underlyingType,
        constructorIsPublic = constructorIsPublic,
        propertyName = propertyName,
        propertyIsPublic = propertyIsPublic,
    )
}

/**
 * The Kotlin-type half of [boundaryTypeOf]'s policy: a declaration is bound only if every type in
 * its signature resolves here, and this is the whole of what "resolves" means -- a primitive Kotlin
 * type, or a value class whose relevant operation ([direction]) is public, unwrapped one level
 * (recursively, in case that value class wraps another).
 *
 * Deliberately not "wraps [boundaryTypeOf]": that function keys off a JVM descriptor, which is
 * exactly the erased view a value class parameter lies about (see [ArtifactScanner]'s KDoc). This
 * keys off the Kotlin type metadata actually declares.
 */
internal fun resolveKotlinType(type: KmType, classpath: ArtifactClasspath, direction: BoundaryDirection): BoundaryType? {
    if (type.isNullable) return null
    val classifier = type.classifier as? KmClassifier.Class ?: return null
    kotlinPrimitiveBoundaryTypeOf(classifier.name)?.let { return it }

    val info = classpath.valueClassInfo(classifier.name) ?: return null
    val underlying = resolveKotlinType(info.underlyingType, classpath, direction) ?: return null
    return when (direction) {
        BoundaryDirection.PARAMETER ->
            if (info.constructorIsPublic) valueClassBoundaryType(info.qualifiedName, underlying, info.propertyName) else null
        BoundaryDirection.RETURN ->
            if (info.propertyIsPublic && info.propertyName != null) {
                valueClassBoundaryType(info.qualifiedName, underlying, info.propertyName)
            } else {
                null
            }
    }
}

private fun valueClassBoundaryType(qualifiedName: String, underlying: BoundaryType, propertyName: String?): BoundaryType =
    BoundaryType(
        tag = underlying.tag,
        readFn = { slot -> "$qualifiedName(${underlying.read(slot)})" },
        wrapFn = { call ->
            val property = propertyName
                ?: error("$qualifiedName has no public accessor for its wrapped value; must not be used in return position")
            underlying.wrapReturn("($call).$property")
        },
    )

/** Kotlin's own names for the types [boundaryTypeOf] admits, keyed the way `KmClassifier.Class.name`
 * spells a top-level class: `/`-separated, no leading slash. One table, not two: both this and
 * [boundaryTypeOf] resolve to the same [BoundaryType] instances, because a JVM `I` and a Kotlin
 * `kotlin.Int` have to widen and narrow identically. */
internal fun kotlinPrimitiveBoundaryTypeOf(kotlinInternalName: String): BoundaryType? = when (kotlinInternalName) {
    "kotlin/Boolean" -> boundaryTypeOf("Z")
    "kotlin/Byte" -> boundaryTypeOf("B")
    "kotlin/Short" -> boundaryTypeOf("S")
    "kotlin/Int" -> boundaryTypeOf("I")
    "kotlin/Long" -> boundaryTypeOf("J")
    "kotlin/Float" -> boundaryTypeOf("F")
    "kotlin/Double" -> boundaryTypeOf("D")
    "kotlin/String" -> boundaryTypeOf("Ljava/lang/String;")
    "kotlin/ByteArray" -> boundaryTypeOf("[B")
    "kotlin/Unit" -> boundaryTypeOf("V")
    else -> null
}

/** One function's worth of what [ArtifactScanner] needs, already carrying its own JVM signature so
 * it can be matched back to the `MethodNode` that owns the bytecode. */
internal data class ResolvedFunction(
    val kotlinName: String,
    val isExtension: Boolean,
    val allParameterTypes: List<KmType>, // receiver (if [isExtension]) first, then declared value parameters
    val returnType: KmType,
    val jvmSignature: JvmMethodSignature,
)

internal fun functionsOf(container: KmDeclarationContainer): List<ResolvedFunction> =
    container.functions.mapNotNull { function -> resolvedFunctionOrNull(function) }

private fun resolvedFunctionOrNull(function: KmFunction): ResolvedFunction? {
    if (function.visibility != Visibility.PUBLIC) return null
    if (function.isSuspend) return null
    val signature = function.signature ?: return null
    val allParams = listOfNotNull(function.receiverParameterType) + function.valueParameters.map { it.type }
    return ResolvedFunction(
        kotlinName = function.name,
        isExtension = function.receiverParameterType != null,
        allParameterTypes = allParams,
        returnType = function.returnType,
        jvmSignature = signature,
    )
}
