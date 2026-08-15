package python.multiplatform.gradle.artifact

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import python.multiplatform.gradle.model.DeclarationModel
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.URLClassLoader

/**
 * Runs [KlibScanner] behind a `WorkerExecutor` classloader boundary, because running it in this
 * plugin's own classloader does not work in a real consumer build.
 *
 * ### Why a worker at all
 *
 * `KlibScanner`'s KDoc and `python-multiplatform-gradle-plugin/build.gradle.kts`'s
 * `kotlin-compiler-embeddable` dependency comment record the measurement: `LibraryAbiReader` (this
 * plugin's own `2.0.20` classes) and `KotlinLibraryImplKt` (the Kotlin Gradle Plugin's
 * `kotlin-util-klib`, a *different* version, on the root buildscript scope -- a *parent* of this
 * plugin's classloader in every consumer build) both answer to `org.jetbrains.kotlin.library.*`, and
 * parent-first delegation serves each class from whichever scope resolves it first. The two do not
 * agree on `KotlinLibraryImpl`'s constructor, hence `NoSuchMethodError`.
 *
 * A `WorkerExecutor` submitted with `classLoaderIsolation` builds its action's classloader from an
 * explicit classpath ([pluginClasspath]) with Gradle's own minimal worker infrastructure as its
 * parent -- **not** this plugin's classloader and **not** the root buildscript scope that sits above
 * it. `org.jetbrains.kotlin.library.*` then has exactly one source inside the worker: whatever jar
 * [pluginClasspath] put there, which is this plugin's own `kotlin-compiler-embeddable:2.0.20`.
 *
 * ### What crosses the boundary, and how
 *
 * Gradle's `WorkParameters` marshal managed property types (`RegularFileProperty`,
 * `ListProperty<String>`) across the isolation, but a `WorkAction` has no return value -- there is no
 * property Gradle marshals back out. [KlibScanResult] is therefore written to [Params.resultFile] with
 * plain `ObjectOutputStream`/`ObjectInputStream` rather than handed back through the worker API: both
 * ends load `DeclarationModel`/`ArtifactCallable` from the *same* class file bytes (this plugin's own
 * jar, present on both the coordinator's classloader and the worker's explicit [pluginClasspath]), so
 * Java serialization's structural check (`serialVersionUID`, computed from the members when none is
 * declared) matches even though the two ends loaded the class through different `ClassLoader`
 * instances -- serialization was never identity-based to begin with.
 */
internal abstract class KlibScanWorkAction : WorkAction<KlibScanWorkAction.Params> {

    internal interface Params : WorkParameters {
        val klibFile: RegularFileProperty
        val includePrefixes: ListProperty<String>
        val resultFile: RegularFileProperty
    }

    override fun execute() {
        val klib = parameters.klibFile.get().asFile
        val includes = parameters.includePrefixes.get()
        val result = KlibScanResult(
            callables = KlibScanner.scanKlib(klib, includes),
            declarations = KlibScanner.scanKlibDeclarations(klib, includes),
        )
        ObjectOutputStream(parameters.resultFile.get().asFile.outputStream()).use { it.writeObject(result) }
    }
}

/** Both of [KlibScanner]'s results for one klib, carried across [KlibScanWorkAction]'s isolation
 * boundary in one round trip rather than two -- the same "one walk" the JVM producer's own
 * [Candidate] already keeps to inside a single process. */
internal data class KlibScanResult(
    val callables: List<ArtifactCallable>,
    val declarations: List<DeclarationModel>,
) : Serializable

/**
 * Every URL this plugin's own classloader resolves -- its compiled classes plus every `implementation`
 * dependency `python-multiplatform-gradle-plugin/build.gradle.kts` declares, `kotlin-compiler-embeddable`
 * and `kotlin-stdlib` included.
 *
 * Deliberately *not* the root buildscript scope that [KlibScanWorkAction]'s KDoc explains this whole
 * file exists to get away from: that scope is a *parent* of this classloader, and a `URLClassLoader`'s
 * own `.urLs` are only the entries it would search *after* its parent already failed -- exactly the
 * child-scope jars, none of the parent's.
 *
 * Cast rather than reflected into: Gradle's plugin classloader (`InstrumentingVisitableURLClassLoader`)
 * is a `URLClassLoader` subclass, so `getURLs()` is public API, not a private field this has to reach
 * around. If a future Gradle version changes that, this cast fails loudly at the call site rather than
 * silently handing the worker an empty classpath.
 */
private fun pluginClasspath(): List<File> {
    val loader = KlibScanner::class.java.classLoader
    val urls = (loader as? URLClassLoader)?.urLs
        ?: error(
            "python-multiplatform-gradle-plugin: expected the plugin classloader to be a " +
                "URLClassLoader so KlibScanWorkAction could read its own classpath explicitly; " +
                "got ${loader.javaClass.name} instead. See KlibScanWorkAction.kt's KDoc.",
        )
    return urls.map { File(it.toURI()) }.filter { it.exists() }
}

/**
 * Runs [KlibScanner] over [klib] inside an isolated worker classloader and returns both of its
 * results -- see [KlibScanWorkAction]'s KDoc for why this indirection exists at all.
 *
 * [scratchDirectory] holds the round-trip file only; the caller owns cleanup the same way it owns
 * `Task.getTemporaryDir()`; this function does not delete anything, so two calls in the same build
 * (jars and stubs) don't race each other's cleanup.
 */
internal fun WorkerExecutor.scanKlibIsolated(klib: File, includes: List<String>, scratchDirectory: File): KlibScanResult {
    scratchDirectory.mkdirs()
    val resultFile = File.createTempFile("klib-scan-", ".ser", scratchDirectory)
    val queue = classLoaderIsolation { classpath.from(pluginClasspath()) }
    queue.submit(KlibScanWorkAction::class.java) {
        klibFile.set(klib)
        includePrefixes.set(includes)
        this.resultFile.set(resultFile)
    }
    queue.await()
    return ObjectInputStream(resultFile.inputStream()).use { it.readObject() as KlibScanResult }
}
