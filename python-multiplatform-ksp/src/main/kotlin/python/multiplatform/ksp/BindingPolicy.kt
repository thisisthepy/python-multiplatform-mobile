package python.multiplatform.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility

private const val PYTHON_INTERNAL_ANNOTATION = "python.multiplatform.reflection.PythonInternal"

/** `docs/binding-policy.md`: what the generator is allowed to expose, decided from the
 * declaration alone -- no classpath resolution needed beyond what KSP already gives a symbol. */
object BindingPolicy {

    fun isExcludedByPackage(qualifiedName: String, excludePackages: List<String>): Boolean =
        (ALWAYS_EXCLUDED_PACKAGE_PREFIXES + excludePackages).any {
            qualifiedName == it || qualifiedName.startsWith("$it.")
        }

    fun hasPythonInternal(annotated: KSAnnotated): Boolean =
        annotated.annotations.any { annotation ->
            val shortName = annotation.shortName.asString()
            // Cheap check first (`shortName`, no resolution); only resolve the annotation type
            // when the name plausibly matches, since resolving every annotation on every
            // declaration would be the expensive path `getSymbolsWithAnnotation` was rejected
            // for avoiding (docs/upcall-table-design.md §2, "Discovery key").
            shortName == "PythonInternal" &&
                annotation.annotationType.resolve().declaration.qualifiedName?.asString() == PYTHON_INTERNAL_ANNOTATION
        }

    private fun isPublic(declaration: KSDeclaration): Boolean = declaration.getVisibility() == Visibility.PUBLIC

    /** Top-level functions only; member functions go through [isExposedMember]. */
    fun isExposedTopLevelFunction(function: KSFunctionDeclaration, excludePackages: List<String>): Boolean {
        val qualifiedName = function.qualifiedName?.asString() ?: return false
        if (isExcludedByPackage(qualifiedName, excludePackages)) return false
        if (!isPublic(function)) return false
        if (hasPythonInternal(function)) return false
        if (Modifier.SUSPEND in function.modifiers) return false
        if (Modifier.INLINE in function.modifiers && function.typeParameters.isNotEmpty()) return false
        if (function.extensionReceiver != null) return false
        val params = function.parameters
        val isEntryPoint = function.simpleName.asString() == "main" &&
            (params.isEmpty() || (params.size == 1 && params[0].type.toShape().qualifiedName == "kotlin.Array"))
        if (isEntryPoint) return false
        return true
    }

    fun isExposedClass(classDeclaration: KSClassDeclaration, excludePackages: List<String>): Boolean {
        val qualifiedName = classDeclaration.qualifiedName?.asString() ?: return false
        if (isExcludedByPackage(qualifiedName, excludePackages)) return false
        if (!isPublic(classDeclaration)) return false
        if (hasPythonInternal(classDeclaration)) return false
        return classDeclaration.classKind == com.google.devtools.ksp.symbol.ClassKind.CLASS
    }

    /** Member function of an already-[isExposedClass] class. Constructors are handled
     * separately via [KSClassDeclaration.primaryConstructor]. */
    fun isExposedMemberFunction(function: KSFunctionDeclaration): Boolean {
        if (!isPublic(function)) return false
        if (hasPythonInternal(function)) return false
        if (Modifier.SUSPEND in function.modifiers) return false
        if (Modifier.INLINE in function.modifiers && function.typeParameters.isNotEmpty()) return false
        if (function.extensionReceiver != null) return false
        if (function.isConstructor()) return false
        return true
    }

    fun isExposedProperty(property: KSPropertyDeclaration): Boolean {
        if (!isPublic(property)) return false
        if (hasPythonInternal(property)) return false
        if (property.extensionReceiver != null) return false
        return true
    }
}
