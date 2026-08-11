package experiment.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.KspExperimental

class ExperimentProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val role = environment.options["experiment.role"] ?: "app"
        return if (role == "library") {
            LibraryProcessor(environment)
        } else {
            AppProcessor(environment)
        }
    }
}

class LibraryProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var invoked = false
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val moduleName = environment.options["experiment.moduleName"] ?: "unknown"
        val topLevelFunctions = resolver.getAllFiles().flatMap { it.declarations }
            .filterIsInstance<KSFunctionDeclaration>()
            .mapNotNull { it.qualifiedName?.asString() }
            .toList()

        if (topLevelFunctions.isNotEmpty()) {
            val file = environment.codeGenerator.createNewFile(
                dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
                packageName = "experiment.generated.fragments",
                fileName = "Fragment_$moduleName"
            )
            file.writer().use { writer ->
                writer.write("""
                    package experiment.generated.fragments
                    
                    object Fragment_$moduleName {
                        fun entries(): List<String> = listOf(
                            ${topLevelFunctions.joinToString(", ") { "\"$it\"" }}
                        )
                    }
                """.trimIndent())
            }
        }
        return emptyList()
    }
}

class AppProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var round = 0

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        round++
        val moduleName = environment.options["experiment.moduleName"] ?: "unknown"
        
        if (round == 1) {
            val topLevelFunctions = resolver.getAllFiles().flatMap { it.declarations }
                .filterIsInstance<KSFunctionDeclaration>()
                .mapNotNull { it.qualifiedName?.asString() }
                .toList()

            if (topLevelFunctions.isNotEmpty()) {
                val file = environment.codeGenerator.createNewFile(
                    dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
                    packageName = "experiment.generated.fragments",
                    fileName = "Fragment_$moduleName"
                )
                file.writer().use { writer ->
                    writer.write("""
                        package experiment.generated.fragments
                        object Fragment_$moduleName {
                            fun entries(): List<String> = listOf(
                                ${topLevelFunctions.joinToString(", ") { "\"$it\"" }}
                            )
                        }
                    """.trimIndent())
                }
            }
        }

        if (round == 2) {
            val fragments = resolver.getDeclarationsFromPackage("experiment.generated.fragments")
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.simpleName.asString().startsWith("Fragment_") }
                .mapNotNull { it.qualifiedName?.asString() }
                .toList()
            
            environment.logger.warn("AppProcessor round 2 generating FunctionTable with: ${fragments}")

            val file = environment.codeGenerator.createNewFile(
                dependencies = Dependencies(false),
                packageName = "experiment.generated",
                fileName = "FunctionTable"
            )
            file.writer().use { writer ->
                val lines = fragments.joinToString(",\n                            ") { "$it.entries()" }
                val flatten = if (fragments.isEmpty()) "" else ".flatten()"
                writer.write("""
                    package experiment.generated
                    
                    object FunctionTable {
                        fun allEntries(): List<String> = listOf<List<String>>(
                            $lines
                        )$flatten
                    }
                """.trimIndent())
            }
        }
        
        return emptyList()
    }
}
