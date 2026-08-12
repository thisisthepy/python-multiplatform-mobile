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
    /**
     * Mirrors `python.multiplatform.reflection.ExposedCallable.isSuspend`: the declaration was a
     * `suspend fun`, so [lambdaBody] starts a coroutine and evaluates to a `PendingCall` rather
     * than to the value. [returnTag] still describes the *declared* return type, which is what the
     * boundary marshals once the coroutine finishes.
     */
    val isSuspend: Boolean = false,
)

data class ClassModel(
    val name: String,
    val memberNames: List<String>,
    /** The full lambda expression text for `traverse`, or `null` if the class has no
     * `PyObject`-typed fields and therefore needs no `tp_traverse` slot. */
    val traverseBody: String?,
    /** Mirrors `python.multiplatform.reflection.ReflectedClassKind` by name, for the same reason
     * [CallableEntryModel.kind] mirrors `CallableKind`. */
    val kind: String = "CLASS",
    /** Entry names in declaration order, for [kind] `"ENUM"`; empty otherwise. */
    val enumEntryNames: List<String> = emptyList(),
)

data class FragmentModel(
    val moduleName: String,
    val entries: List<CallableEntryModel>,
    val classes: List<ClassModel>,
)

/**
 * Drops entries whose name was already taken, keeping the first.
 *
 * Two declarations can legally share one exposed name -- an instance property and a
 * companion-object property of the same name, or an `expect`/`actual` pair both visible to a
 * platform compilation. `UpcallTable.register` only rejects collisions *between* fragments, so a
 * duplicate inside one fragment would silently make `resolve` return whichever entry landed last.
 * Scanning order puts instance members first, so the receiver-taking entry is the survivor.
 */
fun List<CallableEntryModel>.distinctByName(): List<CallableEntryModel> = distinctBy { it.name }
