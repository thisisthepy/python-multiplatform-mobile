package python.multiplatform.gradle.artifact

import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `docs/pythonx-adapter-design.md` §5, measured against real Compose Multiplatform jars.
 *
 * ### What this test exists to pin
 *
 * A `@Composable` used to be declined by [ArtifactScanner] for the one reason its KDoc gave: "the
 * JVM signature carries N parameter(s) metadata does not declare". §5.2 measured what those
 * parameters *are* -- `$composer`, one or more `$changed` bitmasks and (when anything is defaulted)
 * one or more `$default` bitmasks -- and this walks every public `@Composable` in the jars to check
 * that the shape is **derivable**, which is what makes binding one possible at all.
 *
 * Nothing here guesses. The trailing shape is read off the descriptor, and the arithmetic that
 * predicts it (`SLOTS_PER_INT = 10`, `BITS_PER_INT = 31`) is asserted against every composable the
 * jars declare rather than against the nine `javap` rows §5.2 recorded by hand.
 */
class ComposableBindingTest {

    private val caches: File? = listOf(
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
        File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
    ).firstOrNull { it.isDirectory }

    private fun jarUnder(group: String, artifact: String): File? = caches?.resolve(group)?.resolve(artifact)
        ?.walkTopDown()?.firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

    private val kotlinStdlibJar: File
        get() = File(Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location.toURI())

    /** Everything a Compose type might have to be resolved against. */
    private fun composeClasspath(): List<File> = listOfNotNull(
        jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        jarUnder("org.jetbrains.compose.material", "material-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-text-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-saveable-desktop"),
        jarUnder("org.jetbrains.compose.animation", "animation-core-desktop"),
        jarUnder("org.jetbrains.compose.collection-internal", "collection-desktop"),
        jarUnder("org.jetbrains.compose.annotation-internal", "annotation-desktop"),
        kotlinStdlibJar,
    )

    /**
     * The measurement §5.2 did by hand for nine functions, run over every public top-level
     * `@Composable` three Compose jars declare.
     *
     * For each one: the JVM parameter list is the Kotlin parameter list, then **one** `Composer`,
     * then nothing but `int`s. The number of those ints is `changedParamCount(n) +
     * defaultParamCount(n)` when the declaration defaults anything and `changedParamCount(n)` when
     * it does not -- which is what makes it possible to say *which* trailing slot is the mask.
     *
     * Deliberately not an assertion about a fixed count: it prints how many it checked, and fails on
     * the first composable whose shape the arithmetic does not predict.
     */
    @Test
    fun everyComposableJvmSignatureIsKotlinParamsThenComposerThenInts() {
        val jars = listOfNotNull(
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
        )
        if (jars.isEmpty()) return // no local Gradle cache with Compose in it
        val classpath = composeClasspath()

        var checked = 0
        var withDefaults = 0
        val wrong = mutableListOf<String>()
        forEachPublicComposable(jars, classpath) { owner, function, method ->
            val (descriptors, _) = splitMethodDescriptor(method.desc)
            val n = function.allParameterTypes.size
            val shape = ComposableShape.of(n, descriptors)
            checked++
            if (shape == null) {
                wrong += "$owner.${function.kotlinName} ${method.desc}"
                return@forEachPublicComposable
            }
            if (shape.defaultCount > 0) withDefaults++
            // The `$default` masks exist exactly when the declaration defaults something.
            val declares = function.allParameterDefaults.any { it }
            if (declares != (shape.defaultCount > 0)) {
                wrong += "$owner.${function.kotlinName}: declares=$declares defaultCount=${shape.defaultCount}"
            }
        }

        println("composable shape: $checked public top-level composables checked, $withDefaults carry a \$default mask")
        assertTrue(checked > 100, "expected the jars to declare composables; checked only $checked")
        assertEquals(emptyList(), wrong, "composables whose JVM shape the arithmetic did not predict")
    }

    /**
     * The binding itself: `androidx.compose.material3.Text` reaches the table, with the three
     * synthetic parameter groups as **ordinary slots** Python fills.
     *
     * Before this, the same walk produced a declined [python.multiplatform.gradle.model.DeclarationModel]
     * whose reason was "the JVM signature carries 4 parameter(s) metadata does not declare".
     */
    @Test
    fun composablesAreBoundWithTheirSyntheticParametersExposed() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            material3,
            includePrefixes = listOf("androidx.compose.material3"),
            classpath = composeClasspath(),
        )
        val texts = entries.filter { it.name.substringAfterLast('.').substringBefore("__") == "Text" }
        assertTrue(texts.isNotEmpty(), "no Text binding among ${entries.size} entries")

        val stringText = texts.firstOrNull { it.paramTypeNames.firstOrNull() == "kotlin.String" }
        assertNotNull(stringText, "no String-taking Text among ${texts.map { it.name to it.arity }}")

        // The declared parameters, then Composer, two $changed masks and one $default -- the shape
        // `javap -p` shows and `everyComposableJvmSignatureIsKotlinParamsThenComposerThenInts`
        // proves is derivable. **How many** declared parameters is Compose's business and changes
        // between versions (1.6.11's `Text` takes 16, 1.7.0's takes 17 -- `minLines`), so the
        // assertion is the relation and not the number.
        val declared = stringText.paramNames.indexOf("\$composer")
        assertTrue(declared >= 16, "expected Text to declare at least 16 parameters, got $declared")
        assertEquals(declared + 4, stringText.arity, stringText.paramNames.toString())
        assertEquals(stringText.arity, stringText.paramNames.size)
        assertEquals(stringText.arity, stringText.paramTags.size)
        // The synthetic slots separate nothing, so they must not reach the Python-visible name.
        assertTrue("Composer" !in stringText.name, stringText.name)
        assertEquals(
            listOf("\$composer", "\$changed", "\$changed1", "\$default"),
            stringText.paramNames.takeLast(4),
        )
        assertEquals(
            listOf("OBJECT", "INT", "INT", "INT"),
            stringText.paramTags.takeLast(4),
        )
        assertEquals("STRING", stringText.paramTags.first())
        // The declaration's own defaults survive onto the binding: the mask is what makes them
        // reachable, so unlike the sentinel mechanism there is no cap and no branch to decline.
        assertTrue(stringText.paramHasDefault.count { it } >= 14, stringText.paramHasDefault.toString())
        assertEquals(false, stringText.paramHasDefault.first(), "text has no default")
        assertEquals(
            listOf(false, false, false, false),
            stringText.paramHasDefault.takeLast(4),
            "a synthetic slot is never omittable: pythonx always computes it",
        )
    }

    /**
     * The generated Kotlin never names the composable, and it cannot: a Kotlin file facade
     * (`androidx.compose.material3.TextKt`) has **no Kotlin name at all**, so
     * `TextKt.\`Text-fLXpl1I\`(...)` does not compile -- "unresolved reference 'TextKt'", measured
     * with `kotlinc` 2.4.20-Beta2 against the real jar. The call site is therefore emitted as
     * bytecode; see [ComposableThunks].
     */
    @Test
    fun aComposableBindingCallsAGeneratedThunkRatherThanNamingTheDeclaration() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            material3,
            includePrefixes = listOf("androidx.compose.material3"),
            classpath = composeClasspath(),
        )
        val text = entries.first { it.name.substringAfterLast('.').substringBefore("__") == "Text" }
        val thunk = assertNotNull(text.thunk, "a composable binding must carry a thunk spec")
        assertEquals("androidx/compose/material3/TextKt", thunk.ownerInternalName)
        assertTrue(thunk.methodName.startsWith("Text"), thunk.methodName)
        assertTrue(text.lambdaBody.contains("$THUNK_CLASS_TOKEN.t"), text.lambdaBody)
    }

    /**
     * The emitted thunk really links: generated with ASM against a class this test compiled itself,
     * loaded, and called with a boxed `Object[]` exactly as the boundary hands one over.
     *
     * The target deliberately has a **mangled** JVM name, because that is the case that closes every
     * other route: a hyphen is not a Java identifier and a Kotlin file facade is not a Kotlin name.
     */
    @Test
    fun aGeneratedThunkCallsAMangledJvmNameAndUnboxesTheBoundaryTypes() {
        val targetInternal = "fixture/thunk/MangledProbe"
        val target = mangledProbeClass(targetInternal)
        val bytes = generateThunkClass(
            "Probe",
            listOf(
                ThunkSpec(targetInternal, "sum-abc123", "(IJFZLjava/lang/String;)J"),
                ThunkSpec(targetInternal, "nothing", "(I)V"),
            ),
        )
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(name: String, data: ByteArray): Class<*> = defineClass(name, data, 0, data.size)
        }
        loader.define(targetInternal.replace('/', '.'), target)
        val cls = loader.define("$THUNK_PACKAGE.ProbeThunks", bytes)

        // INT carries a Long and FLOAT carries a Double, per `boundaryTypeOf`; the thunk narrows
        // each one on the way in and widens the `long` return back to what INT promises.
        val t0 = cls.getMethod(thunkMethodName(0), Array<Any?>::class.java)
        assertEquals(7L + 8L + 2L + 1L, t0.invoke(null, arrayOf<Any?>(7L, 8L, 2.5, true, "x")))
        val t1 = cls.getMethod(thunkMethodName(1), Array<Any?>::class.java)
        assertEquals(Unit, t1.invoke(null, arrayOf<Any?>(1L)))
    }

    /**
     * A class whose static method is called `sum-abc123`. Emitted rather than compiled on purpose:
     * a hyphen is Kotlin's value-class mangling suffix, is not a Java identifier, and the exact
     * suffix `kotlinc` would pick is not something a test may assume -- so the name is *chosen* here
     * and the point (that the thunk can call a name no source file can spell) is made directly.
     *
     * `sum-abc123(i, j, f, z, s)` returns `i + j + f.toLong() + (if (z) 1 else 0)`; `nothing(i)`
     * returns `void`.
     */
    private fun mangledProbeClass(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sum-abc123", "(IJFZLjava/lang/String;)J", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.I2L)
            visitVarInsn(Opcodes.LLOAD, 1); visitInsn(Opcodes.LADD)
            visitVarInsn(Opcodes.FLOAD, 3); visitInsn(Opcodes.F2L); visitInsn(Opcodes.LADD)
            visitVarInsn(Opcodes.ILOAD, 4); visitInsn(Opcodes.I2L); visitInsn(Opcodes.LADD)
            visitInsn(Opcodes.LRETURN)
            visitMaxs(0, 0); visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "nothing", "(I)V", null, null).apply {
            visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun forEachPublicComposable(
        jars: List<File>,
        classpath: List<File>,
        action: (owner: String, function: ResolvedFunction, method: org.objectweb.asm.tree.MethodNode) -> Unit,
    ) {
        val artifactClasspath = ArtifactClasspath(classpath)
        jars.forEach { jar ->
            java.util.jar.JarFile(jar).use { file ->
                file.entries().asSequence().filter { it.name.endsWith(".class") && '$' !in it.name }.forEach { entry ->
                    val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                    if ((node.access and Opcodes.ACC_PUBLIC) == 0) return@forEach
                    val containers = when (val metadata = kotlinClassMetadataOf(node)) {
                        is kotlin.metadata.jvm.KotlinClassMetadata.FileFacade -> listOf(metadata.kmPackage to node)
                        is kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.mapNotNull { part ->
                            val partNode = artifactClasspath.classNode(part) ?: return@mapNotNull null
                            val partMetadata = kotlinClassMetadataOf(partNode) as? kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassPart
                                ?: return@mapNotNull null
                            partMetadata.kmPackage to partNode
                        }
                        else -> return@forEach
                    }
                    containers.forEach { (kmPackage, ownerNode) ->
                        val bySignature = functionsOf(kmPackage).associateBy { it.jvmSignature.name to it.jvmSignature.descriptor }
                        ownerNode.methods.forEach { method ->
                            if ((method.access and Opcodes.ACC_PUBLIC) == 0) return@forEach
                            if ((method.access and Opcodes.ACC_STATIC) == 0) return@forEach
                            // `invisibleAnnotations`, deliberately: `@Composable` is
                            // `AnnotationRetention.BINARY`, so it is a `RuntimeInvisibleAnnotation`
                            // and the visible list is empty for every composable in the jar. Read
                            // out of `javap -v`, and the reason `ArtifactScanner.isComposable` used
                            // to answer `false` for all 500 of them.
                            val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
                            if (annotations.none { it.desc == "Landroidx/compose/runtime/Composable;" }) return@forEach
                            // By name **and descriptor**: `Text--4IGK_g` names two different
                            // overloads in one class, so a name-only lookup would pair a composable
                            // with the wrong declaration and make the arithmetic check vacuous.
                            // `KmFunction.jvmSignature` records the *lowered* descriptor, synthetic
                            // parameters included, which is why this matches at all.
                            val function = bySignature[method.name to method.desc] ?: return@forEach
                            action(ownerNode.name.replace('/', '.'), function, method)
                        }
                    }
                }
            }
        }
    }
}
