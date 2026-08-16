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
        // A **container**: the shape `androidx.compose.foundation.layout.Column` really has, reduced
        // the same way `Text` above is. Slot 1 is the one this fixture exists for -- a parameter the
        // walker reports as `kotlin.Function3@Composable`, which is a `@Composable ColumnScope.() ->
        // Unit` after the Compose plugin lowered it (measured: `Column`'s JVM descriptor takes
        // `Lkotlin/jvm/functions/Function3;` where metadata declares `kotlin.Function1`).
        ExposedCallable(
            name = "androidx.compose.foundation.layout.Column",
            arity = 5,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("modifier", "content", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf(
                "androidx.compose.ui.Modifier", COMPOSABLE_FUNCTION3, COMPOSER, "kotlin.Int", "kotlin.Int",
            ),
            returnTypeName = null,
            paramHasDefault = listOf(true, false, false, false, false),
            callable = { args ->
                calls += args.toList()
                // Exactly what Compose does with a lowered `content`: an ordinary interface call
                // passing the scope, the composer and a `$changed` of its own. If the object in
                // slot 1 is not a `Function3` this is where it fails, which is the point.
                @Suppress("UNCHECKED_CAST")
                val content = args[1] as Function3<Any?, Any?, Any?, Unit>
                contentInvocations++
                // Deliberately **not** `args[2]`. Compose hands a content lambda the composer that
                // is current where the content runs, and a wrapper that ignored its own argument and
                // reused whatever `pythonx.push_composer` last saw would pass every test that used
                // one composer. A distinct object makes that shortcut fail.
                content(StubScope, InnerComposer, 0L)
                Unit
            },
        ),
        // A **plain** function-typed parameter on a composable: `Button`'s `onClick`, which is
        // `kotlin.Function0` in metadata *and* `Lkotlin/jvm/functions/Function0;` in the descriptor.
        // Nothing was lowered, so nothing pushes a composer and the callable takes no arguments --
        // which is the distinction `_function_slot` has to make and this entry is here to check.
        ExposedCallable(
            name = "androidx.compose.material3.Button",
            arity = 4,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.OBJECT, TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.FUNCTION,
            paramNames = listOf("onClick", "\$composer", "\$changed", "\$default"),
            paramTypeNames = listOf("kotlin.Function0", COMPOSER, "kotlin.Int", "kotlin.Int"),
            returnTypeName = null,
            paramHasDefault = listOf(false, false, false, false),
            callable = { args ->
                calls += args.toList()
                @Suppress("UNCHECKED_CAST")
                clicks += args[0] as Function0<Unit>
                Unit
            },
        ),
    )

    /** What the walker emits for a slot whose declared `kotlin.Function1` was lowered to a JVM
     * `Function3` -- see `ArtifactScanner.functionSlotTypeName`. */
    const val COMPOSABLE_FUNCTION3: String = "kotlin.Function3@Composable"

    /** Stands in for `ColumnScope`: the receiver Compose threads into a lowered content lambda, and
     * the argument `PythonCallables` must **drop** rather than forward, because Python has no way to
     * be handed one. */
    object StubScope

    /** The composer a container hands its content, distinct from [StubComposer] on purpose. */
    object InnerComposer

    /** How many times [entries]'s `Column` has invoked its `content`. Distinct from [calls]: a
     * container that received a content it never called would otherwise look identical. */
    var contentInvocations: Int = 0

    /** Every `onClick` Kotlin was handed, **not** invoked -- so that a test can call one after the
     * composition that produced it has gone, which is the lifetime question. */
    val clicks: MutableList<Function0<Unit>> = mutableListOf()
}
