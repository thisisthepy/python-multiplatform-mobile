package python.multiplatform.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Variance

private const val PY_OBJECT_QUALIFIED_NAME = "python.multiplatform.ffi.PyObject"

/** Reduces a resolved KSP type to the plain data [castExpression]/[wrapReturnExpression] work on. */
fun KSType.toShape(): TypeShape {
    val qualifiedName = declaration.qualifiedName?.asString() ?: "kotlin.Any"
    return TypeShape(qualifiedName, isMarkedNullable, rendered = renderWithArguments(qualifiedName))
}

/**
 * `pkg.Type` for a plain type, `pkg.Type<pkg.Arg, *>` for a generic one -- what a cast in
 * generated source needs, since `x as kotlin.collections.List` does not compile.
 *
 * Use-site variance (`Array<out String>`) is deliberately dropped: the cast is unchecked either
 * way, and an invariant cast target still satisfies a projected parameter. A star projection
 * stays a star, because there is nothing else to write for it. A type argument that is itself a
 * type *parameter* (`T`) has no qualified name and would render as an unresolved reference;
 * [BindingPolicy] rejects every declaration that can produce one before this is reached, and the
 * fallbacks to `*` here are the belt to that pair of braces.
 */
private fun KSType.renderWithArguments(qualifiedName: String): String {
    if (arguments.isEmpty()) return qualifiedName
    val renderedArguments = arguments.joinToString(", ") { argument ->
        if (argument.variance == Variance.STAR) return@joinToString "*"
        val argumentType = argument.type?.resolve() ?: return@joinToString "*"
        if (argumentType.declaration !is KSClassDeclaration) return@joinToString "*"
        val inner = argumentType.declaration.qualifiedName?.asString() ?: return@joinToString "*"
        argumentType.renderWithArguments(inner) + if (argumentType.isMarkedNullable) "?" else ""
    }
    return "$qualifiedName<$renderedArguments>"
}

fun KSTypeReference.toShape(): TypeShape = resolve().toShape()

/**
 * Whether [type] is [python.multiplatform.ffi.PyObject] or one of its subclasses (`PyInt`,
 * `PyList`, ... every wrapper the object model exposes). A field of this type is a Python
 * reference the Kotlin object holds, and `tp_traverse` must see it -- see
 * `docs/object-lifetime.md`'s "Cycle collection is part of the table's job".
 */
fun isPyObjectType(type: KSType): Boolean {
    val declaration = type.declaration
    if (declaration.qualifiedName?.asString() == PY_OBJECT_QUALIFIED_NAME) return true
    val classDeclaration = declaration as? KSClassDeclaration ?: return false
    return classDeclaration.getAllSuperTypes().any {
        it.declaration.qualifiedName?.asString() == PY_OBJECT_QUALIFIED_NAME
    }
}
