package python.multiplatform.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRenderingTest {

    @Test
    fun fragmentSourceImplementsTheRuntimeInterfaceAndCarriesTheModuleName() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = listOf(
                CallableEntryModel(
                    name = "my.lib.greet",
                    arity = 1,
                    paramTags = listOf(Tag.STRING),
                    returnTag = Tag.STRING,
                    kind = "FUNCTION",
                    lambdaBody = "{ args -> greet(args[0] as String) }",
                ),
            ),
            classes = emptyList(),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("package python.multiplatform.generated.fragments"))
        assertTrue(src.contains("object Fragment_my_lib : python.multiplatform.reflection.FunctionTableFragment"))
        assertTrue(src.contains("override val moduleName: String = \"my_lib\""))
        assertTrue(src.contains("name = \"my.lib.greet\""))
        assertTrue(src.contains("{ args -> greet(args[0] as String) }"))
        // no fragment is public API for a user to call directly; but it must not be `internal`,
        // or the app module's generated aggregator cannot reference it across the module
        // boundary. See renderFragmentSource's doc comment.
        assertTrue(!src.contains("internal object Fragment_my_lib"))
    }

    @Test
    fun fragmentSourceOmitsTraverseArgumentForClassesWithoutPythonFields() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = emptyList(),
            classes = listOf(
                ClassModel(name = "my.lib.Counter", memberNames = listOf("my.lib.Counter.<init>"), traverseBody = null),
            ),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("name = \"my.lib.Counter\""))
        assertTrue(!src.contains("traverse ="))
    }

    @Test
    fun fragmentSourceIncludesTraverseArgumentForClassesWithPythonFields() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = emptyList(),
            classes = listOf(
                ClassModel(
                    name = "my.lib.RefHolder",
                    memberNames = listOf("my.lib.RefHolder.<init>"),
                    traverseBody = "{ obj, visit -> (obj as RefHolder).field?.let { visit(it.pointer.toRawValue()) } }",
                ),
            ),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("traverse = { obj, visit ->"))
    }

    @Test
    fun aggregatorSourceReferencesEveryDiscoveredFragmentExplicitly() {
        val src = renderAggregatorSource(
            listOf(
                "python.multiplatform.generated.fragments.Fragment_library_module",
                "python.multiplatform.generated.fragments.Fragment_app_module",
            ),
        )

        assertTrue(src.contains("package python.multiplatform.generated"))
        assertTrue(src.contains("object FunctionTable"))
        assertTrue(src.contains("python.multiplatform.generated.fragments.Fragment_library_module,"))
        assertTrue(src.contains("python.multiplatform.generated.fragments.Fragment_app_module,"))
        assertTrue(src.contains("UpcallTable.install(fragments)"))
    }

    @Test
    fun aggregatorSourceWithNoFragmentsStillProducesValidSyntaxShape() {
        val src = renderAggregatorSource(emptyList())

        assertTrue(src.contains("val fragments: List<python.multiplatform.reflection.FunctionTableFragment> = listOf(\n    )"))
    }

    @Test
    fun fragmentSourceSuppressesUncheckedCastsBecauseEveryArgumentReadIsOne() {
        // Reading `args[0] as List<String>` out of an `Array<Any?>` is unchecked by
        // construction; without the file-level suppression every generated fragment with a
        // generic parameter buries the build in warnings.
        val src = renderFragmentSource(FragmentModel("my_lib", emptyList(), emptyList()))

        assertTrue(src.startsWith("@file:Suppress(\"UNCHECKED_CAST\")"))
    }

    @Test
    fun classSourceCarriesTheKotlinShapeAndAnEnumsEntryNames() {
        // An interface has no constructor and an enum has a fixed instance set: the Python side
        // picks a different proxy shape for each, and cannot infer it from member names.
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = emptyList(),
            classes = listOf(
                ClassModel(
                    name = "my.lib.Greeter",
                    memberNames = listOf("my.lib.Greeter.greet"),
                    traverseBody = null,
                    kind = "INTERFACE",
                ),
                ClassModel(
                    name = "my.lib.Color",
                    memberNames = listOf("my.lib.Color.RED"),
                    traverseBody = null,
                    kind = "ENUM",
                    enumEntryNames = listOf("RED", "GREEN"),
                ),
            ),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("kind = python.multiplatform.reflection.ReflectedClassKind.INTERFACE"))
        assertTrue(src.contains("kind = python.multiplatform.reflection.ReflectedClassKind.ENUM"))
        assertTrue(src.contains("enumEntryNames = listOf(\"RED\", \"GREEN\")"))
    }

    @Test
    fun anOrdinaryClassRendersTheDefaultKindAndNoEntryNames() {
        val src = renderFragmentSource(
            FragmentModel(
                moduleName = "my_lib",
                entries = emptyList(),
                classes = listOf(ClassModel("my.lib.Counter", listOf("my.lib.Counter.<init>"), null)),
            ),
        )

        assertTrue(src.contains("kind = python.multiplatform.reflection.ReflectedClassKind.CLASS"))
        assertTrue(!src.contains("enumEntryNames"))
    }

    @Test
    fun duplicateEntryNamesAreDroppedBeforeTheyReachTheTable() {
        // Legal Kotlin: `class Foo { val x = 1; companion object { val x = 2 } }` yields two
        // entries called `pkg.Foo.x` once companion members are folded into the owner. Two
        // entries under one name make UpcallTable.resolve return whichever won the race; the
        // instance member is the one that keeps its receiver contract, so it wins deliberately.
        val instance = CallableEntryModel("p.Foo.x", 0, emptyList(), Tag.INT, "GETTER", "{ args -> 1L }")
        val static = CallableEntryModel("p.Foo.x", 0, emptyList(), Tag.INT, "STATIC_GETTER", "{ 2L }")
        val other = CallableEntryModel("p.Foo.y", 0, emptyList(), Tag.INT, "GETTER", "{ args -> 3L }")

        val deduped = listOf(instance, static, other).distinctByName()

        assertEquals(listOf("p.Foo.x", "p.Foo.y"), deduped.map { it.name })
        assertEquals("GETTER", deduped[0].kind)
    }
}
