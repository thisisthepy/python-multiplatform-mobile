enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // The convenience plugin of ROADMAP §7 lives in its own build so that an outside consumer
    // resolves it by id like any other plugin. Including it here is what makes
    // `id("io.github.thisisthepy.python.multiplatform.bindings")` resolve inside this repo.
    includeBuild("python-multiplatform-gradle-plugin")

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()

        maven {
            setUrl("https://jitpack.io")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "PythonMultiplatformMobile"

include(":sample")
// The half of the sample that can apply the bindings plugin. KSP 2.3.11 requires AGP >= 8.10 and
// this build is on 8.5.2, so no module carrying an Android plugin may apply it -- see the comment
// in `sample/build.gradle.kts`.
include(":sample-bindings")
include(":python-multiplatform")
include(":python-multiplatform-ksp")
include(":ksp-fixtures:library")
include(":ksp-fixtures:app")
