package python.multiplatform.ksp

/**
 * `CallableKind` mirrors `python.multiplatform.reflection.CallableKind` by name -- kept as a
 * string here (rather than an import of the runtime enum) so this whole model stays independent
 * of the runtime module and is reachable from unit tests that never touch KSP or a real
 * classpath.
 */
data class CallableEntryModel(
    val name: String,
    val arity: Int,
    val paramTags: List<Tag>,
    val returnTag: Tag,
    val kind: String,
    /** The full lambda expression text, e.g. `{ args -> greet(args[0] as String) }`. */
    val lambdaBody: String,
)

data class ClassModel(
    val name: String,
    val memberNames: List<String>,
    /** The full lambda expression text for `traverse`, or `null` if the class has no
     * `PyObject`-typed fields and therefore needs no `tp_traverse` slot. */
    val traverseBody: String?,
)

data class FragmentModel(
    val moduleName: String,
    val entries: List<CallableEntryModel>,
    val classes: List<ClassModel>,
)
