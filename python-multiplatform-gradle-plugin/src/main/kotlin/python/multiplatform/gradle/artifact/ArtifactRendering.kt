package python.multiplatform.gradle.artifact

import java.io.Serializable

/** The package walked artefacts' fragments land in. Deliberately **not**
 * `python.multiplatform.generated.fragments`, which `PackageScanFragmentDiscovery` sweeps into
 * KSP's `FunctionTable` -- see [renderArtifactTableSource]. It is still under
 * `python.multiplatform.generated`, which `python-multiplatform-ksp`'s
 * `ALWAYS_EXCLUDED_PACKAGE_PREFIXES` excludes by prefix, so the processor never rescans this
 * generated source as if a consumer had written it. */
internal const val ARTIFACTS_PACKAGE = "python.multiplatform.generated.artifacts"

private const val FRAGMENT_INTERFACE = "python.multiplatform.reflection.FunctionTableFragment"
private const val EXPOSED_CALLABLE = "python.multiplatform.reflection.ExposedCallable"
private const val TYPE_TAG = "python.multiplatform.reflection.TypeTag"
private const val CALLABLE_KIND = "python.multiplatform.reflection.CallableKind"
private const val UPCALL_TABLE = "python.multiplatform.reflection.UpcallTable"

/**
 * One binding taken out of a compiled artefact.
 *
 * Mirrors `python.multiplatform.ksp.CallableEntryModel` field for field, minus what a jar cannot
 * answer: there is no `isSuspend` (a `suspend` function's JVM shape takes a `Continuation`, which
 * [boundaryTypeOf] declines, so no suspending declaration ever reaches here) and no `kind` (every
 * entry is a `FUNCTION`, because only statics are bound -- an instance method would need a
 * `ReflectedClass` and a receiver handle, which is the next step and not this one).
 *
 * `Serializable`: [KlibScanWorkAction] hands one of these back across the `WorkerExecutor` isolation
 * boundary it runs [KlibScanner] behind.
 */
internal data class ArtifactCallable(
    val name: String,
    val arity: Int,
    val paramTags: List<String>,
    val returnTag: String,
    val lambdaBody: String,
    /** `import ... as ...` lines [lambdaBody] depends on -- non-empty only for a Kotlin extension
     * function, which [lambdaBody] calls as `receiver.alias(...)`. Kotlin has no fully-qualified
     * call syntax for an extension (the receiver can never be a positional argument), so the alias
     * has to come from an import instead; see `ArtifactScanner`'s KDoc. */
    val imports: List<String> = emptyList(),
    /**
     * The Kotlin type this declaration extends, or `null` when it is not an extension function.
     *
     * Carried rather than recomputed because it is not recoverable from anything else here: the
     * receiver is [ArtifactCallable.paramTags]'s slot 0, and a tag says how a value is marshalled,
     * not what it is. `docs/pythonx-adapter-design.md` §2.4 row 3 records this exact loss --
     * "`ArtifactScanner` has both at the moment it constructs the call expression and discards them
     * one line later" -- and §4.2 records that `docs/kotlin-extensions-in-python.md` §4.1
     * (an extension becomes a method on its receiver's proxy) has no input without it.
     */
    val receiverTypeName: String? = null,
    /** The declaration's parameter names, receiver slot included as `<receiver>`. */
    val paramNames: List<String> = emptyList(),
    /** The Kotlin type each parameter was declared as, receiver slot included -- **not** the type it
     * marshals as. A `Dp` parameter is `paramTags` `FLOAT` and `paramTypeNames`
     * `androidx.compose.ui.unit.Dp`. */
    val paramTypeNames: List<String> = emptyList(),
    /** The declared Kotlin return type. */
    val returnTypeName: String? = null,
    /**
     * Whether each parameter may be **left out of a call**, which is a statement about [lambdaBody]
     * and not quite about the declaration.
     *
     * `ArtifactScanner.applyDefaultOmission` sets it from the omission sets it actually generated a
     * call for; the two answers differ where it declined to (a declaration past
     * `ArtifactScanner.MAX_OMITTABLE_PARAMETERS`, or one whose parameters have no writable names).
     * `pythonx._bind` fills a slot this marks with the `null` sentinel [lambdaBody] branches on, so
     * the body's answer is the one that has to be told. [omittableSlotsOf] is where that invariant is
     * enforced for both producers.
     */
    val paramHasDefault: List<Boolean> = emptyList(),
) : Serializable

/** One artefact's worth of bindings: what becomes a single `FunctionTableFragment` object. */
internal data class ArtifactFragment(
    val objectName: String,
    val moduleName: String,
    val entries: List<ArtifactCallable>,
)

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * A Kotlin identifier for a resolved artefact's coordinates.
 *
 * `python.multiplatform.ksp.sanitiseModuleName`'s rule, widened: a `ComponentIdentifier`'s display
 * name is `group:name:version` for a module and `project :path` for a project, so spaces and colons
 * have to go the way periods and hyphens do.
 */
internal fun artifactFragmentObjectName(coordinates: String): String =
    "ArtifactFragment_" + coordinates.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/**
 * `paramHasDefault` is a statement about [ArtifactCallable.lambdaBody], and this is where that is
 * enforced rather than assumed.
 *
 * The column used to be inert -- carried into the table and read by nothing -- so a producer could
 * fill it from the *declaration* and be right enough. It is not inert any more: `pythonx._bind`
 * fills a slot it marks with `null`, and a body that has no branch for that `null` casts it and
 * throws. `ArtifactScanner` sets the column from what it generated; [KlibScanner] still sets it from
 * `AbiValueParameter.hasDefaultArg` while emitting a body that passes every argument, which is the
 * old, now-wrong pairing.
 *
 * Rather than trust two producers to agree, the flag is derived here from the one thing that cannot
 * lie about the body: whether the body tests for the sentinel at all. A klib entry therefore reaches
 * Python as "every argument required", which is what it is.
 */
private fun omittableSlotsOf(entry: ArtifactCallable): List<Boolean> =
    if ("== null" in entry.lambdaBody) entry.paramHasDefault else entry.paramHasDefault.map { false }

private fun renderEntry(entry: ArtifactCallable): String {
    val paramTags = entry.paramTags.joinToString(", ") { "$TYPE_TAG.$it" }
    val paramNames = entry.paramNames.joinToString(", ") { it.quoted() }
    val paramTypeNames = entry.paramTypeNames.joinToString(", ") { it.quoted() }
    val paramHasDefault = omittableSlotsOf(entry).joinToString(", ")
    return """
        |$EXPOSED_CALLABLE(
        |    name = ${entry.name.quoted()},
        |    arity = ${entry.arity},
        |    paramTypes = listOf($paramTags),
        |    returnType = $TYPE_TAG.${entry.returnTag},
        |    kind = $CALLABLE_KIND.FUNCTION,
        |    paramNames = listOf($paramNames),
        |    paramTypeNames = listOf($paramTypeNames),
        |    returnTypeName = ${entry.returnTypeName?.quoted() ?: "null"},
        |    isExtension = ${entry.receiverTypeName != null},
        |    receiverTypeName = ${entry.receiverTypeName?.quoted() ?: "null"},
        |    paramHasDefault = listOf($paramHasDefault),
        |    callable = ${entry.lambdaBody},
        |)
    """.trimMargin()
}

/**
 * Renders one artefact's fragment.
 *
 * The same file shape `python-multiplatform-ksp`'s `renderFragmentSource` emits, because the
 * runtime must not be able to tell the two producers apart: `UpcallTable`, `CallableHandle` and
 * `PythonProxySource` all see a `FunctionTableFragment` and nothing more.
 *
 * `DEPRECATION` joins `UNCHECKED_CAST` in the file-level suppression for a reason KSP's version does
 * not have: the declarations here are somebody else's. JUnit 4's own `junit.framework.Assert` is
 * deprecated, and a consumer who asked for a binding should not be handed a warning about source
 * they did not write and cannot change.
 *
 * `OPT_IN_USAGE_ERROR` is there for the same reason one size up. Compose marks a large part of its
 * public API with `@ExperimentalFoundationApi` and friends, which are `RequiresOptIn` at `ERROR`
 * level, so a fragment binding `Modifier.combinedClickable` would fail to compile in a consumer's
 * build unless that consumer happened to have opted in. The *marker* is not knowable here -- it is
 * one annotation among any number a library may invent -- so the diagnostic is suppressed rather
 * than an `@OptIn` guessed at. What that costs is real and worth stating: a consumer gets no warning
 * that a bound declaration is experimental. What it buys is that asking for a package does not fail
 * the build over which of its declarations someone else marked.
 */
internal fun renderArtifactFragmentSource(fragment: ArtifactFragment): String = buildString {
    appendLine("// GENERATED by python-multiplatform-gradle-plugin. Do not edit.")
    appendLine(
        "@file:Suppress(\"UNCHECKED_CAST\", \"DEPRECATION\", \"DEPRECATION_ERROR\", " +
            "\"OPT_IN_USAGE\", \"OPT_IN_USAGE_ERROR\")",
    )
    appendLine()
    appendLine("package $ARTIFACTS_PACKAGE")
    appendLine()
    // Deduplicated and sorted: two entries binding the same extension function (possible once an
    // ambiguous-overload group is what prevents it, not the walker's enumeration order) must not
    // produce two conflicting `as` aliases for the same import.
    fragment.entries.flatMap { it.imports }.toSortedSet().forEach { appendLine(it) }
    if (fragment.entries.any { it.imports.isNotEmpty() }) appendLine()
    appendLine("object ${fragment.objectName} : $FRAGMENT_INTERFACE {")
    appendLine("    override val moduleName: String = ${fragment.moduleName.quoted()}")
    appendLine()
    appendLine("    override fun entries(): List<$EXPOSED_CALLABLE> = listOf(")
    fragment.entries.forEach { appendLine(renderEntry(it).prependIndent("        ") + ",") }
    appendLine("    )")
    appendLine("}")
}

/**
 * Renders the aggregator over every walked artefact.
 *
 * A second list beside KSP's `FunctionTable` rather than an extension of it. The reasoning is in
 * `ArtifactTableRenderingTest`'s class doc, and the short form is in the generated doc comment
 * below so that a reader who only ever sees the output gets it too.
 */
internal fun renderArtifactTableSource(fragmentObjectNames: List<String>): String = buildString {
    appendLine("// GENERATED by python-multiplatform-gradle-plugin. Do not edit.")
    appendLine("package $ARTIFACTS_PACKAGE")
    appendLine()
    appendLine("/**")
    appendLine(" * Every resolved artefact this module asked to have bound.")
    appendLine(" *")
    appendLine(" * Not merged into `python.multiplatform.generated.FunctionTable`: that one means \"every")
    appendLine(" * module in this build graph that was compiled with the processor\", and a walked")
    appendLine(" * third-party jar is not one. Install both:")
    appendLine(" *")
    appendLine(" *     UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)")
    appendLine(" */")
    appendLine("object ArtifactTable {")
    appendLine("    val fragments: List<$FRAGMENT_INTERFACE> = listOf(")
    fragmentObjectNames.forEach { appendLine("        $it,") }
    appendLine("    )")
    appendLine()
    appendLine("    /** Adds these to whatever is already installed, leaving it in place. */")
    appendLine("    fun registerInto() {")
    appendLine("        fragments.forEach { $UPCALL_TABLE.register(it) }")
    appendLine("    }")
    appendLine("}")
}
