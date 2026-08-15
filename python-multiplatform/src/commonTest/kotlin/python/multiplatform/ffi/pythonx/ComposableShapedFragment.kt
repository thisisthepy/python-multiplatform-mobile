package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table shaped the way `ArtifactScanner` shapes a `@Composable`, backed by Kotlin this repository
 * owns.
 *
 * ### Why a stand-in
 *
 * Same judgement as [ComposeShapedFragment]'s, one step further along. The adapter under test is
 * `commonMain` Python, so its tests belong in `commonTest` where all five targets run them -- and a
 * real composable needs a JVM classpath, a Compose runtime, a live `Composer` and a generated thunk
 * class, none of which exist on Kotlin/Native. What is under test here is the *arithmetic*: which
 * `$default` bits a Python call sets, what goes in the slots it left out, and what `$changed` is.
 *
 * The real end of it -- `androidx.compose.material3.Text` drawing "hi" -- is
 * `:ksp-fixtures:compose`'s `ComposableRenderTest`, which is desktop-only for exactly those reasons.
 *
 * ### The shape, and why every field of it matters
 *
 * `Text(text, modifier = ..., color = ..., fontSize = ...)` reduced to four declared parameters and
 * the three synthetic groups a composable's JVM signature really carries
 * (`docs/pythonx-adapter-design.md` §5.2):
 *
 * | slot | name | tag | why |
 * |---|---|---|---|
 * | 0 | `text` | `STRING` | required: the one argument `Text('hi')` writes |
 * | 1 | `modifier` | `OBJECT` | defaulted, reference: its absent value must be `null` |
 * | 2 | `color` | `INT` | defaulted, **value class erased to a primitive**: its absent value must be `0`, not `None`, or the thunk's `Number.intValue()` would raise |
 * | 3 | `fontSize` | `INT` | a second defaulted slot, so a mask with two bits set is distinguishable from one with either |
 * | 4 | `$composer` | `OBJECT` | the value Python cannot invent |
 * | 5 | `$changed` | `INT` | must arrive as `0` -- the conservative claim |
 * | 6 | `$default` | `INT` | the mask, which is the whole point |
 */
object ComposableShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_pythonx_composable"

    /** Every call, as `(text, modifier, color, fontSize, composer, changed, default)`. Recorded
     * rather than returned because "the default was used" and "0 was passed" are the same number at
     * the boundary -- the same trap [ComposeShapedFragment.calls] exists for. */
    val calls: MutableList<List<Any?>> = mutableListOf()

    /** Stands in for a live `androidx.compose.runtime.Composer`: an instance Python can only have
     * got from Kotlin, so a slot holding it proves the handle round-tripped. */
    object StubComposer

    private const val COMPOSER = "androidx.compose.runtime.Composer"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "androidx.compose.material3.Text",
            arity = 7,
            paramTypes = listOf(
                TypeTag.STRING, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT,
                TypeTag.OBJECT, TypeTag.INT, TypeTag.INT,
            ),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("text", "modifier", "color", "fontSize", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(
                "kotlin.String", "androidx.compose.ui.Modifier", "kotlin.Int", "kotlin.Int",
                COMPOSER, "kotlin.Int", "kotlin.Int",
            ),
            returnTypeName = null,
            paramHasDefault = listOf(false, true, true, true, false, false, false),
            callable = { args ->
                calls += args.toList()
                Unit
            },
        ),
        // Where the composer comes from in a test: a plain declaration returning an object handle,
        // so the Python side gets one the only way it ever legitimately can -- out of Kotlin.
        ExposedCallable(
            name = "androidx.compose.runtime.stubComposer",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.FUNCTION,
            returnTypeName = COMPOSER,
            callable = { StubComposer },
        ),
    )
}
