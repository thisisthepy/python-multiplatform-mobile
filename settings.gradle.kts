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
include(":python-multiplatform")
include(":python-multiplatform-ksp")
include(":ksp-fixtures:library")
include(":ksp-fixtures:app")
