package python.multiplatform.ksp

import kotlin.test.Test
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
}
