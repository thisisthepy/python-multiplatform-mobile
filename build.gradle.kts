plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.jetpack.compose).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.ksp).apply(false)
}

allprojects {
    group = "io.github.thisisthepy"
    version = "3.13.0"  // Official Python release version
}

// ROADMAP §15a/§15e item 3: `python-multiplatform-gradle-plugin` is a separate included build
// (see `pluginManagement { includeBuild(...) }` in settings.gradle.kts), so its tasks never join
// the root task graph -- `./gradlew tasks --all` from here lists none of them, and a coordinator
// publishing only what the root build shows would silently skip it. This task does not change how
// any of the three components publish (each already has its own `maven-publish` config); it only
// gives the root build one entry point that reaches all three.
tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "Publishes python-multiplatform, python-multiplatform-ksp, and " +
        "python-multiplatform-gradle-plugin (including its plugin-marker publication) to mavenLocal()."
    dependsOn(":python-multiplatform:publishToMavenLocal")
    dependsOn(":python-multiplatform-ksp:publishToMavenLocal")
    dependsOn(gradle.includedBuild("python-multiplatform-gradle-plugin").task(":publishToMavenLocal"))
}
