package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiQualifiedName
import org.jetbrains.kotlin.library.abi.AbiType
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import java.io.File

/**
 * `docs/ecosystem.md` §5b's second producer, adapted for Kotlin/Native Klibs.
 *
 * JVM uses ASM to parse .class files. Klib uses `LibraryAbiReader`.
 *
 * Experimental API usage:
 * `LibraryAbiReader` is experimental and may break across Kotlin compiler versions.
 * It is wrapped entirely in this file. The API surface used is:
 * - `LibraryAbiReader.readAbiInfo`
 * - `LibraryAbi.topLevelDeclarations.declarations`
 * - `AbiFunction` (isSuspend, valueParameters, returnType)
 * - `AbiType.Simple` (getClassifierReference)
 * - `AbiClassifierReference.ClassReference` (getClassName)
 * - `AbiQualifiedName` (toString)
 * By keeping it here, any breaks are contained to `KlibScanner`.
 */
@OptIn(ExperimentalLibraryAbiReader::class)
internal object KlibScanner {

    fun scanKlib(klib: File, includePrefixes: List<String>): List<ArtifactCallable> {
        val entries = mutableListOf<ArtifactCallable>()
        val abi = try {
            LibraryAbiReader.readAbiInfo(klib)
        } catch (e: Exception) {
            return emptyList()
        }

        val declarations = abi.topLevelDeclarations.declarations
        for (decl in declarations) {
            if (decl !is AbiFunction) continue
            // Klib metadata correctly reports suspend functions, unlike JVM bytecode
            // where they are transformed to take a Continuation. We explicitly filter them out.
            if (decl.isSuspend) continue
            if (decl.hasExtensionReceiverParameter) continue
            // Constructors are not bound here (yet), similar to JVM scanner.
            if (decl.isConstructor) continue

            val qualifiedName = decl.qualifiedName.toString()
            if (!matchesInclude(qualifiedName, includePrefixes)) continue
            
            // JVM scanner drops names with '-' as they are mangled value classes.
            // Klib does not mangle value class names in the ABI, but boundaryTypeOf
            // drops anything it doesn't recognize. Thus value class parameters will simply
            // map to null and be naturally discarded.

            val callable = callableOrNull(qualifiedName, decl)
            if (callable != null) {
                entries.add(callable)
            }
        }

        // Overload Handling:
        // JVM side drops all overloads because Python has no overload resolution.
        // Even though Klib provides exact types (so we know exactly which overload is which),
        // we still drop them because the Python runtime still cannot dispatch them
        // dynamically by argument type, and the UpcallTable expects one function per name.
        return entries
            .groupBy { it.name }
            .filterValues { it.size == 1 }
            .values
            .map { it.single() }
            .sortedBy { it.name }
    }

    private fun matchesInclude(qualifiedName: String, includePrefixes: List<String>): Boolean =
        includePrefixes.isEmpty() || includePrefixes.any {
            qualifiedName == it || qualifiedName.startsWith("$it.")
        }

    private fun callableOrNull(qualifiedName: String, function: AbiFunction): ArtifactCallable? {
        val returnTypeStr = typeToString(function.returnType) ?: return null
        val returnType = klibBoundaryTypeOf(returnTypeStr) ?: return null

        val paramTypes = function.valueParameters.map { param ->
            val paramStr = typeToString(param.type) ?: return null
            klibBoundaryTypeOf(paramStr) ?: return null
        }
        
        if (paramTypes.any { it.isReturnOnly }) return null

        val argumentExpressions = paramTypes.mapIndexed { index, type -> type.read("args[$index]") }
        
        // For Klibs, the owner isn't a class if it's a top-level function.
        // The qualified name is something like "package.name/functionName" or "package.name.ClassName.functionName".
        // Let's replace '/' with '.' to make it valid Kotlin code.
        val kotlinQualifiedName = qualifiedName.replace('/', '.')
        
        val call = "$kotlinQualifiedName(${argumentExpressions.joinToString(", ")})"
        val body = returnType.wrapReturn(call)
        return ArtifactCallable(
            name = kotlinQualifiedName,
            arity = paramTypes.size,
            paramTags = paramTypes.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (paramTypes.isEmpty()) "{ $body }" else "{ args -> $body }",
        )
    }

    private fun typeToString(type: AbiType?): String? {
        if (type == null) return "kotlin/Unit"
        if (type !is AbiType.Simple) return null
        val ref = type.classifierReference
        if (ref !is AbiClassifierReference.ClassReference) return null
        return ref.className.toString()
    }

    /**
     * Maps precise Klib types to BoundaryType.
     * 
     * Value classes (like kotlin.time.Duration) are naturally dropped here because
     * they don't match any of these explicitly allowed boundary types, achieving the
     * same result as the JVM scanner's '-' name filter but relying on actual types.
     */
    private fun klibBoundaryTypeOf(typeStr: String): BoundaryType? = when (typeStr) {
        "kotlin/Boolean" -> BoundaryType("BOOLEAN", "(%s as Boolean)", "(%s)")
        "kotlin/Byte" -> BoundaryType("INT", "(%s as Long).toByte()", "(%s).toLong()")
        "kotlin/Short" -> BoundaryType("INT", "(%s as Long).toShort()", "(%s).toLong()")
        "kotlin/Int" -> BoundaryType("INT", "(%s as Long).toInt()", "(%s).toLong()")
        "kotlin/Long" -> BoundaryType("INT", "(%s as Long)", "(%s)")
        "kotlin/Float" -> BoundaryType("FLOAT", "(%s as Double).toFloat()", "(%s).toDouble()")
        "kotlin/Double" -> BoundaryType("FLOAT", "(%s as Double)", "(%s)")
        "kotlin/String" -> BoundaryType("STRING", "(%s as String)", "(%s)")
        "kotlin/ByteArray" -> BoundaryType("BYTES", "(%s as ByteArray)", "(%s)")
        "kotlin/Unit" -> BoundaryType("UNIT", "(%s as Unit)", "(%s)", isReturnOnly = true)
        else -> null
    }
}
