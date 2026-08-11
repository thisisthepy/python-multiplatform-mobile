package python.multiplatform.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference

private const val PY_OBJECT_QUALIFIED_NAME = "python.multiplatform.ffi.PyObject"

/** Reduces a resolved KSP type to the plain data [castExpression]/[wrapReturnExpression] work on. */
fun KSType.toShape(): TypeShape =
    TypeShape(qualifiedName = declaration.qualifiedName?.asString() ?: "kotlin.Any", nullable = isMarkedNullable)

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
