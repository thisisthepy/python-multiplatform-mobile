package python.multiplatform.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility

/** What one module's worth of scanning produced, plus the files it came from -- the latter is
 * what [com.google.devtools.ksp.processing.Dependencies] needs for incremental processing. */
class ScanResult(val fragment: FragmentModel, val originatingFiles: List<KSFile>)

/**
 * Walks every file KSP knows about in this compilation and turns the exposed surface
 * (`docs/binding-policy.md`) into a [FragmentModel] -- the pure, KSP-independent shape
 * [renderFragmentSource] turns into text.
 */
class FragmentScanner(private val excludePackages: List<String>) {

    fun scan(resolver: Resolver, moduleName: String): ScanResult {
        val files = resolver.getAllFiles().toList()
        val topLevel = files.flatMap { it.declarations }

        val entries = mutableListOf<CallableEntryModel>()
        val classes = mutableListOf<ClassModel>()

        topLevel.filterIsInstance<KSFunctionDeclaration>()
            .filter { BindingPolicy.isExposedTopLevelFunction(it, excludePackages) }
            .forEach { entries += topLevelFunctionEntry(it) }

        topLevel.filterIsInstance<KSClassDeclaration>()
            .filter { BindingPolicy.isExposedClass(it, excludePackages) }
            .forEach { classDeclaration ->
                val (classEntries, classModel) = classEntriesAndModel(classDeclaration)
                entries += classEntries
                classes += classModel
            }

        return ScanResult(FragmentModel(moduleName, entries, classes), files)
    }

    private fun topLevelFunctionEntry(function: KSFunctionDeclaration): CallableEntryModel {
        val qualifiedName = function.qualifiedName!!.asString()
        val paramShapes = function.parameters.map { it.type.toShape() }
        val returnShape = function.returnType?.toShape() ?: TypeShape("kotlin.Unit", false)
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[$i]") }
        val callExpr = "$qualifiedName(${argExprs.joinToString(", ")})"
        return CallableEntryModel(
            name = qualifiedName,
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = tagFor(returnShape),
            kind = "FUNCTION",
            lambdaBody = "{ args -> ${wrapReturnExpression(returnShape, callExpr)} }",
        )
    }

    private fun classEntriesAndModel(classDeclaration: KSClassDeclaration): Pair<List<CallableEntryModel>, ClassModel> {
        val qualifiedName = classDeclaration.qualifiedName!!.asString()
        val entries = mutableListOf<CallableEntryModel>()
        val memberNames = mutableListOf<String>()

        classDeclaration.primaryConstructor
            ?.takeIf { it.getVisibility() == Visibility.PUBLIC && !BindingPolicy.hasPythonInternal(it) }
            ?.let { ctor ->
                val entry = constructorEntry(qualifiedName, ctor)
                entries += entry
                memberNames += entry.name
            }

        classDeclaration.declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter { BindingPolicy.isExposedMemberFunction(it) }
            .forEach { function ->
                val entry = memberFunctionEntry(qualifiedName, function)
                entries += entry
                memberNames += entry.name
            }

        val properties = classDeclaration.declarations.filterIsInstance<KSPropertyDeclaration>().toList()
        properties.filter { BindingPolicy.isExposedProperty(it) }
            .forEach { property ->
                val (getterEntry, setterEntry) = propertyEntries(qualifiedName, property)
                entries += getterEntry
                memberNames += getterEntry.name
                if (setterEntry != null) {
                    entries += setterEntry
                    memberNames += setterEntry.name
                }
            }

        val pyObjectFields = properties.filter { isPyObjectType(it.type.resolve()) }
        val traverseBody = if (pyObjectFields.isEmpty()) null else traverseBody(qualifiedName, pyObjectFields)

        return entries to ClassModel(qualifiedName, memberNames, traverseBody)
    }

    private fun constructorEntry(classQualifiedName: String, ctor: KSFunctionDeclaration): CallableEntryModel {
        val paramShapes = ctor.parameters.map { it.type.toShape() }
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[$i]") }
        return CallableEntryModel(
            name = "$classQualifiedName.<init>",
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = Tag.OBJECT,
            kind = "CONSTRUCTOR",
            lambdaBody = "{ args -> $classQualifiedName(${argExprs.joinToString(", ")}) }",
        )
    }

    private fun memberFunctionEntry(classQualifiedName: String, function: KSFunctionDeclaration): CallableEntryModel {
        val paramShapes = function.parameters.map { it.type.toShape() }
        val returnShape = function.returnType?.toShape() ?: TypeShape("kotlin.Unit", false)
        // args[0] is the receiver; declared parameters start at args[1].
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[${i + 1}]") }
        val receiver = "(args[0] as $classQualifiedName)"
        val callExpr = "$receiver.${function.simpleName.asString()}(${argExprs.joinToString(", ")})"
        return CallableEntryModel(
            name = "$classQualifiedName.${function.simpleName.asString()}",
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = tagFor(returnShape),
            kind = "METHOD",
            lambdaBody = "{ args -> ${wrapReturnExpression(returnShape, callExpr)} }",
        )
    }

    private fun propertyEntries(
        classQualifiedName: String,
        property: KSPropertyDeclaration,
    ): Pair<CallableEntryModel, CallableEntryModel?> {
        val propShape = property.type.toShape()
        val propTag = tagFor(propShape)
        val propName = property.simpleName.asString()
        val receiver = "(args[0] as $classQualifiedName)"
        val getter = CallableEntryModel(
            name = "$classQualifiedName.$propName",
            arity = 0,
            paramTags = emptyList(),
            returnTag = propTag,
            kind = "GETTER",
            lambdaBody = "{ args -> ${wrapReturnExpression(propShape, "$receiver.$propName")} }",
        )
        val setter = if (property.isMutable) {
            CallableEntryModel(
                name = "$classQualifiedName.$propName=",
                arity = 1,
                paramTags = listOf(propTag),
                returnTag = Tag.UNIT,
                kind = "SETTER",
                lambdaBody = "{ args -> $receiver.$propName = ${castExpression(propShape, "args[1]")} }",
            )
        } else null
        return getter to setter
    }

    private fun traverseBody(classQualifiedName: String, pyObjectFields: List<KSPropertyDeclaration>): String {
        val visits = pyObjectFields.joinToString("\n") { field ->
            val name = field.simpleName.asString()
            val receiver = "(obj as $classQualifiedName).$name"
            if (field.type.resolve().isMarkedNullable) {
                "        $receiver?.let { visit(it.pointer.toRawValue()) }"
            } else {
                "        visit($receiver.pointer.toRawValue())"
            }
        }
        return "{ obj, visit ->\n$visits\n    }"
    }
}
