package fixture.artifact

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The declarations `WalkedArtifactComposeModifierTest` needs that the *walker* cannot supply,
 * written as this module's own Kotlin so KSP binds them.
 *
 * ### Why a seed is needed at all
 *
 * A `Modifier` chain has to start somewhere, and the thing it starts from is `Modifier` the
 * *expression* -- `androidx.compose.ui.Modifier.Companion`, an object instance
 * (`docs/kotlin-extensions-in-python.md` §4.2). The artefact walker binds **functions**, so a
 * companion's instance is not something it can hand out; `docs/ecosystem.md` §5b's next step (a
 * `ReflectedClass` and a receiver handle) is where that will come from. Until it does, the empty
 * modifier is one function of our own -- and everything after it is Compose's own code, reached
 * through the walker under Compose's own name.
 *
 * ### Why the checks are functions here rather than assertions in Python
 *
 * The test must prove that a `Modifier` **built in Python** is the real thing, and "the real thing"
 * is a claim only Compose's own types can settle. `toString` on a modifier chain is not it:
 * `PaddingElement` and `SizeElement` are `internal` classes with no `toString` of their own, so the
 * text is `PaddingElement@1a2b3c` and says nothing. What *is* settled is structural equality --
 * `ModifierNodeElement` subclasses implement `equals` by value, and `CombinedModifier` compares
 * both halves -- so comparing the Python-built chain against one this file builds independently
 * proves that Compose's own `padding` ran, with the argument Python supplied, in the order Python
 * chained it.
 *
 * Nothing here builds anything on Python's behalf: [equalsPaddingThenSize] takes the finished chain
 * as its *parameter* and only says whether it matches.
 */
fun emptyModifier(): Modifier = Modifier

/** For a failure message. See this file's KDoc for why nothing asserts on it. */
fun describeModifier(modifier: Modifier): String = modifier.toString()

/** Proves the handle round trip is *identity*: `HandleTable.resolve` is a slot read, and this says
 * it resolved to the object that was registered rather than to an equal one. */
fun isTheEmptyModifier(modifier: Modifier): Boolean = modifier === Modifier

/** How many elements the chain has. `Modifier.foldIn` is Compose's own traversal, so a chain that
 * did not actually compose cannot report 2. */
fun modifierElementCount(modifier: Modifier): Int = modifier.foldIn(0) { count, _ -> count + 1 }

/** Whether [modifier] is structurally `Modifier.padding(pad.dp).size(size.dp)`. */
fun equalsPaddingThenSize(modifier: Modifier, pad: Double, size: Double): Boolean =
    modifier == Modifier.padding(pad.dp).size(size.dp)
