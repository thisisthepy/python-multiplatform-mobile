package python.multiplatform.gradle.artifact

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Walks the jars a configuration resolves and writes one `FunctionTableFragment` per artefact that
 * contributed something, plus the `ArtifactTable` that lists them.
 *
 * `docs/ecosystem.md` §5b: KSP reads the consumer's own source, this reads everything the build
 * resolves. The two emit the same shape into the same `UpcallTable`; see [renderArtifactTableSource]
 * for why they keep separate aggregators.
 *
 * ### Only jars
 *
 * A resolved artefact that is not a `.jar` -- a klib, an `aar`, a project's classes directory -- is
 * skipped silently. Whether the same walk is possible over a klib, and what a Kotlin declaration
 * from one could be bound to at runtime with no JVM underneath, is `docs/ecosystem.md` §5b's stated
 * open question and is not answered here.
 */
abstract class PythonArtifactBindingsTask : DefaultTask() {

    /** Every file the walked configuration resolved. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection

    /**
     * File name to the artefact's coordinates (`junit:junit:4.13.2`, or `project :lib`).
     *
     * Keyed by file name rather than by absolute path so that the task input does not carry a
     * Gradle cache location, which differs per machine and would defeat any build cache.
     */
    @get:Input
    abstract val coordinatesByFileName: MapProperty<String, String>

    /** Package or class names to bind; empty binds every class in every resolved jar. */
    @get:Input
    abstract val includePrefixes: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().asFile.resolve(ARTIFACTS_PACKAGE.replace('.', '/'))
        // Rebuilt from scratch: a dependency that was removed, or an include prefix that stopped
        // matching, must take its fragment file with it or the next compilation still sees it.
        outputDirectory.get().asFile.deleteRecursively()
        destination.mkdirs()

        val includes = includePrefixes.get()
        val coordinates = coordinatesByFileName.get()
        val objectNames = mutableListOf<String>()

        // Every resolved jar, not just the one being enumerated: a value-class parameter or an
        // extension receiver declared in one artefact routinely lives in another --
        // `androidx.compose.foundation.layout`'s `Modifier.padding(Dp)` needs `Dp`, which is
        // `androidx.compose.ui.unit`'s. See `ArtifactScanner.scanJar`'s `classpath` parameter.
        val resolvedJars = artifacts.files.filter { it.isFile && it.name.endsWith(".jar") }

        resolvedJars
            .sortedBy { it.name }
            .forEach { jar ->
                val entries = ArtifactScanner.scanJar(jar, includes, classpath = resolvedJars)
                // An artefact that contributed nothing gets no fragment: a fragment with no entries
                // would still take a `moduleName` in `UpcallTable`'s installed set, which is the one
                // thing that set is read for.
                if (entries.isEmpty()) return@forEach
                val coordinate = coordinates[jar.name] ?: jar.name.removeSuffix(".jar")
                val objectName = artifactFragmentObjectName(coordinate)
                destination.resolve("$objectName.kt").writeText(
                    renderArtifactFragmentSource(ArtifactFragment(objectName, "artifact:$coordinate", entries)),
                )
                objectNames += objectName
                logger.info("python-multiplatform: bound ${entries.size} declaration(s) from $coordinate")
            }

        destination.resolve("ArtifactTable.kt").writeText(renderArtifactTableSource(objectNames))
    }
}
