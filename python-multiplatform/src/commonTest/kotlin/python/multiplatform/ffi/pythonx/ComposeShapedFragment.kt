package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table shaped exactly the way the artefact walker shapes Compose, backed by Kotlin this
 * repository owns.
 *
 * ### Why a stand-in rather than the real jars
 *
 * `WalkedArtifactComposeModifierTest` (in `ksp-fixtures/artifact`) already proves that the walker
 * produces these entries out of `foundation-layout-desktop-1.6.11.jar` and that Compose's own
 * `padding` runs when Python calls them. What it cannot do is run anywhere but desktop, because it
 * needs a JVM classpath. The adapter under test here is `commonMain` Python, so its tests belong in
 * `commonTest` where all five targets run them -- which rules out a jar.
 *
 * So this fragment restates the *shape* and nothing else: every field
 * `WalkedArtifactComposeModifierTest.aWalkedEntryCarriesItsDeclarationAndNotOnlyItsTags` asserts
 * about the real `padding__Dp` is asserted about this one too ([shapeMatchesTheWalkedEntries]), and
 * the Kotlin behind it is [StubModifier] rather than `androidx.compose.ui.Modifier`. The names are
 * the real ones on purpose: `pythonx`'s seeded package map says `pythonx.compose` means
 * `androidx.compose`, and a fixture under a made-up package would not exercise it.
 *
 * ### What each entry is here to say
 *
 * | entry | what it exists for |
 * |---|---|
 * | `padding__Dp` / `__Dp_Dp` / `__Dp_Dp_Dp_Dp` / `__PaddingValues` | overload dispatch on count, on keyword names, and on argument type |
 * | `size__Dp` | the second link of a chain, so the return really is a receiver again |
 * | `fillMaxWidth` | a receiver-only extension, and a `fill_max_width` reverse-name case |
 * | `zIndex` | `z_index`, the case where a one-letter first segment must not be swallowed |
 * | `toURLString` | a name the snake -> camel rule **cannot** invert; the index has to carry it |
 * | `paddingFromBaseline__TextUnit` | the value-class reject list: a packed wrapper must refuse a raw number |
 * | `emptyModifier` | where a chain starts. Compose has no bound declaration for this; see [EMPTY_MODIFIER] |
 */
object ComposeShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_pythonx_compose"

    /**
     * Which overload actually ran, in call order.
     *
     * The dispatcher's decision is invisible from Python -- `padding(16)` and `padding(1, 2, 3, 4)`
     * are the same call site -- so this is how a test says *which* declaration was reached rather
     * than merely that something was.
     */
    val calls: MutableList<String> = mutableListOf()

    /**
     * The one declaration here that the real walker does not produce.
     *
     * `Modifier` as an *expression* is `Modifier.Companion`, an object instance, and the walker
     * binds functions. So the empty modifier a chain starts from has no bound name in the real
     * table, and `pythonx` cannot conjure one -- which is why the class-object spelling
     * (`Modifier.padding(...)`) needs an empty-instance factory registered by name and refuses
     * with a message rather than guessing when none is. This is that name for the fixture.
     */
    const val EMPTY_MODIFIER: String = "androidx.compose.ui.emptyModifier"

    private const val MODIFIER = "androidx.compose.ui.Modifier"

    private const val DP = "androidx.compose.ui.unit.Dp"

    private const val PADDING_VALUES = "androidx.compose.foundation.layout.PaddingValues"

    private const val TEXT_UNIT = "androidx.compose.ui.unit.TextUnit"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = EMPTY_MODIFIER,
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            paramNames = emptyList(),
            paramTypeNames = emptyList(),
            returnTypeName = MODIFIER,
        ) { StubModifier.EMPTY },
        ExposedCallable(
            name = "androidx.compose.ui.describeModifier",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.STRING,
            paramNames = listOf("modifier"),
            paramTypeNames = listOf(MODIFIER),
            returnTypeName = "kotlin.String",
        ) { args -> (args[0] as StubModifier).describe() },
        // padding, all four overloads, with the receiver in slot 0 exactly as the walker emits it.
        extension(
            name = "androidx.compose.foundation.layout.padding__Dp",
            paramNames = listOf("all"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf(DP),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "padding__Dp"
            receiver.plus("padding(${dp(args[0])})")
        },
        extension(
            name = "androidx.compose.foundation.layout.padding__Dp_Dp",
            paramNames = listOf("horizontal", "vertical"),
            paramTypes = listOf(TypeTag.FLOAT, TypeTag.FLOAT),
            paramTypeNames = listOf(DP, DP),
            paramHasDefault = listOf(true, true),
        ) { receiver, args ->
            calls += "padding__Dp_Dp"
            receiver.plus("padding(h=${dp(args[0])}, v=${dp(args[1])})")
        },
        extension(
            name = "androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp",
            paramNames = listOf("start", "top", "end", "bottom"),
            paramTypes = listOf(TypeTag.FLOAT, TypeTag.FLOAT, TypeTag.FLOAT, TypeTag.FLOAT),
            paramTypeNames = listOf(DP, DP, DP, DP),
            paramHasDefault = listOf(true, true, true, true),
        ) { receiver, args ->
            calls += "padding__Dp_Dp_Dp_Dp"
            receiver.plus(
                "padding(s=${dp(args[0])}, t=${dp(args[1])}, e=${dp(args[2])}, b=${dp(args[3])})",
            )
        },
        extension(
            name = "androidx.compose.foundation.layout.padding__PaddingValues",
            paramNames = listOf("paddingValues"),
            paramTypes = listOf(TypeTag.OBJECT),
            paramTypeNames = listOf(PADDING_VALUES),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "padding__PaddingValues"
            receiver.plus("padding(${(args[0] as StubPaddingValues).label})")
        },
        ExposedCallable(
            name = "androidx.compose.foundation.layout.paddingValuesOf",
            arity = 1,
            paramTypes = listOf(TypeTag.FLOAT),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("all"),
            paramTypeNames = listOf(DP),
            returnTypeName = PADDING_VALUES,
        ) { args -> StubPaddingValues("pv(${dp(args[0])})") },
        extension(
            name = "androidx.compose.foundation.layout.size__Dp",
            paramNames = listOf("size"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf(DP),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "size__Dp"
            receiver.plus("size(${dp(args[0])})")
        },
        extension(
            name = "androidx.compose.foundation.layout.fillMaxWidth",
            paramNames = emptyList(),
            paramTypes = emptyList(),
            paramTypeNames = emptyList(),
            paramHasDefault = emptyList(),
        ) { receiver, _ ->
            calls += "fillMaxWidth"
            receiver.plus("fillMaxWidth")
        },
        extension(
            name = "androidx.compose.ui.draw.zIndex",
            paramNames = listOf("zIndex"),
            paramTypes = listOf(TypeTag.FLOAT),
            // A genuine `kotlin.Float`, not a value class: the same TypeTag as `Dp` and a
            // different declared type, which is the whole reason `paramTypeNames` exists.
            paramTypeNames = listOf("kotlin.Float"),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "zIndex"
            receiver.plus("zIndex(${dp(args[0])})")
        },
        // A packed value class. `docs/kotlin-extensions-in-python.md` §2.4: raw 16 decodes as
        // `TextUnit.Unspecified`, silently. Nothing binds such a parameter today (TextUnit's
        // constructor is INTERNAL, so `resolveKotlinType` declines it), so this entry is
        // hypothetical -- it exists so the Python side's refusal has something to refuse.
        extension(
            name = "androidx.compose.foundation.layout.paddingFromBaseline__TextUnit",
            paramNames = listOf("top"),
            paramTypes = listOf(TypeTag.FLOAT),
            paramTypeNames = listOf(TEXT_UNIT),
            paramHasDefault = listOf(false),
        ) { receiver, args ->
            calls += "paddingFromBaseline__TextUnit"
            receiver.plus("paddingFromBaseline(${dp(args[0])})")
        },
        // The name the snake -> camel rule cannot invert: `toURLString` forward-converts to
        // `to_url_string`, and the reverse rule turns that back into `toUrlString`.
        ExposedCallable(
            name = "androidx.compose.ui.util.toURLString",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
            paramNames = listOf("raw"),
            paramTypeNames = listOf("kotlin.String"),
            returnTypeName = "kotlin.String",
        ) { args -> "url:" + (args[0] as String) },
    )

    private fun dp(value: Any?): String = (value as Double).toString()

    /**
     * One extension entry, with slot 0 spelled the way the walker spells it: counted in `arity`,
     * named `<receiver>`, typed with the receiver's Kotlin type name, and never defaulted.
     */
    private fun extension(
        name: String,
        paramNames: List<String>,
        paramTypes: List<TypeTag>,
        paramTypeNames: List<String>,
        paramHasDefault: List<Boolean>,
        body: (StubModifier, List<Any?>) -> StubModifier,
    ): ExposedCallable = ExposedCallable(
        name = name,
        arity = paramTypes.size + 1,
        paramTypes = listOf(TypeTag.OBJECT) + paramTypes,
        returnType = TypeTag.OBJECT,
        paramNames = listOf("<receiver>") + paramNames,
        paramTypeNames = listOf(MODIFIER) + paramTypeNames,
        returnTypeName = MODIFIER,
        isExtension = true,
        receiverTypeName = MODIFIER,
        paramHasDefault = listOf(false) + paramHasDefault,
    ) { args -> body(args[0] as StubModifier, args.drop(1)) }
}

/** What a `Modifier` is for this fixture: an ordered list of what was applied to it. */
class StubModifier private constructor(private val elements: List<String>) {

    fun plus(element: String): StubModifier = StubModifier(elements + element)

    fun describe(): String = if (elements.isEmpty()) "<empty>" else elements.joinToString(" -> ")

    companion object {
        val EMPTY: StubModifier = StubModifier(emptyList())
    }
}

/** An ordinary object parameter, so that two arity-2 overloads can be told apart by type alone. */
class StubPaddingValues(val label: String)
