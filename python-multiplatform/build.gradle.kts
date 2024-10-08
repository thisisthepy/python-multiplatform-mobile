import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget.*
import org.jetbrains.kotlin.konan.target.linker


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    id("com.codingfeline.buildkonfig").version("0.15.2")
    //id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}


val pythonVersion = project.rootProject.version.toString()
val libraryVersion = "$pythonVersion-alpha01"
version = libraryVersion

buildkonfig {
    packageName = project.name.lowercase().replace("-", ".")
    objectName = "BuildConfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "pythonVersion", pythonVersion)
        buildConfigField(FieldSpec.Type.STRING, "libraryVersion", libraryVersion)
    }
}

val libVersion = version.toString().split('.').subList(0, 2).joinToString(".")
println("----------------------------------------------------------------------------------------")
println("                   Build Configuration for Python version $libVersion                   ")
println("----------------------------------------------------------------------------------------")
println()

val includePath = "src/nativeInterop/cinterop/include"
val licensePath = "src/nativeInterop/cinterop/license"
val libPath = "src/nativeInterop/cinterop/lib"
val libPathForDesktop = "$libPath/desktop"
val libPathForAndroid = "$libPath/android"
val libPathForIOS = "$libPath/ios/Python.xcframework"

val androidBuildDir = "$projectDir/build/android"

kotlin {
    /** Uncomment this block to enable WebAssembly support (currently not supported by Python Multiplatform)
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "sample"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "demo.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }
     */

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        afterEvaluate {
            val abiList = listOf("arm64-v8a", "x86_64")
            val copyAndroidPythonBinaries by tasks.creating(Copy::class) {
                dependsOn(
                    tasks.named("linkAndroidNativeArm64"),
                    tasks.named("linkAndroidNativeX64")
                )
                into("$androidBuildDir/jniLibs/")
                abiList.forEach {
                    from("$libPathForAndroid/$it") {
                        include("libpython*.*.so")
                        include("lib*_python.so")
                        into(it)
                    }
                }
            }
            val copyAndroidPythonAssets by tasks.creating(Copy::class) {
                into("$androidBuildDir/assets/")
                abiList.forEach {
                    from(includePath) {
                        into("$it/include/python$libVersion")  // include
                    }
                }
                abiList.forEach {
                    from("$libPathForAndroid/$it") {
                        into("$it/lib/python$libVersion")  // python stdlib
                    }
                }
            }
            tasks.whenTaskAdded {
                if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
                    dependsOn(copyAndroidPythonBinaries)
                }
                if (name.startsWith("package") && name.endsWith("Assets")) {
                    dependsOn(copyAndroidPythonAssets)
                }
            }

        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        tasks.withType<AbstractCopyTask> {
            duplicatesStrategy = DuplicatesStrategy.WARN
        }
        tasks.withType<ProcessResources> {
            duplicatesStrategy = DuplicatesStrategy.WARN
        }
        tasks.withType<Jar> {
            duplicatesStrategy = DuplicatesStrategy.WARN
            from(licensePath) {
                into("META-INF/LICENSE")
            }
        }
    }

    /* Supported platforms
     * https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/native/src/org/jetbrains/kotlin/ide/konan/NativeDefinitions.flex
     */
    listOf(
        iosArm64(), iosSimulatorArm64(), iosX64(),
        androidNativeArm64(), androidNativeX64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            val targetABI = when(konanTarget) {
                ANDROID_ARM64 -> "arm64-v8a"
                ANDROID_X64 -> "x86_64"
                IOS_ARM64 -> "ios-arm64"
                IOS_X64 -> "ios-arm64_x86_64-simulator"
                IOS_SIMULATOR_ARM64 -> "ios-arm64_x86_64-simulator"
                else -> throw RuntimeException("Unsupported ABI: $konanTarget")
            }
            val targetLibPath = when(konanTarget.family) {
                Family.ANDROID -> libPathForAndroid
                Family.IOS -> libPathForIOS
                else -> throw RuntimeException("Unsupported target family: ${konanTarget.family}")
            }

            compilations.getByName("main").cinterops.create("python") {
                headers("$includePath/Python.h")
                if (konanTarget.family == Family.IOS) {
                    compilerOpts.addAll(listOf(
                        "-F$projectDir/$targetLibPath", "-I$projectDir/$includePath"
                    ))
                } else {
                    includeDirs(includePath)
                }
            }

            binaries {
                if (konanTarget.family == Family.ANDROID) {
                    sharedLib("multiplatform_python$libVersion") {
                        linkerOpts.addAll(listOf("-L$targetLibPath/$targetABI/", "-lpython$libVersion"))

                        linkTaskProvider.configure {
                            val type = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
                            copy {
                                from(outputFile)
                                into(file("$androidBuildDir/$type/jniLibs/$targetABI/"))
                            }
                        }
                        afterEvaluate {
                            val preBuild by tasks.getting
                            preBuild.dependsOn(linkTaskProvider)
                        }
                    }
                } else if (konanTarget.family == Family.IOS) {
                    all {
                        linkerOpts.addAll(listOf("-F$projectDir/$targetLibPath"))
                    }
                    framework {
                        baseName = "PythonMultiplatform"
                    }
                }
            }
        }
    }
    
    sourceSets {
        val jvmMain by creating
        val commonMain by getting
        val desktopMain by getting {
            resources.srcDirs("src/desktopMain/resources", libPathForDesktop)
        }
        val androidMain by getting
        jvmMain.dependsOn(commonMain)
        desktopMain.dependsOn(jvmMain)
        androidMain.dependsOn(jvmMain)

        val nativeMain by creating
        nativeMain.dependsOn(commonMain)
        try {  // when not on macOS
            val iosMain by creating  // This will fail on macOS
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            iosX64Main.dependsOn(iosMain)
            iosArm64Main.dependsOn(iosMain)
            iosSimulatorArm64Main.dependsOn(iosMain)
        } finally {
            val iosMain by getting
            iosMain.dependsOn(nativeMain)
        }

        val artMain by creating {
            kotlin.srcDir("src/artMain/kotlin")
        }
        val androidNativeX64Main by getting
        val androidNativeArm64Main by getting
        artMain.dependsOn(nativeMain)
        androidNativeX64Main.dependsOn(artMain)
        androidNativeArm64Main.dependsOn(artMain)
    }
}

android {
    namespace = "python.multiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].assets.srcDirs("src/androidMain/assets", "$androidBuildDir/assets")
    sourceSets["debug"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/debug/jniLibs")
    sourceSets["release"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/release/jniLibs")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        //artifact(javadocJar)
        groupId = groupId
        artifactId = artifactId
        version = version

        pom {
            name.set("python-multiplatform")
            description.set("A multiplatform solution to use Python with Kotlin interoperably.")
            url.set("https://github.com/thisisthepy/python-multiplatform-mobile")
            licenses {
                license {
                    //name.set("The Apache License, Version 2.0")
                    //url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("thisisthepy")
                    name.set("thisisthepy")
                    email.set("thisisthepy@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:github.com/thisisthepy/python-multiplatform-mobile.git")
                developerConnection.set("scm:git:ssh://github.com/thisisthepy/python-multiplatform-mobile.git")
                url.set("https://github.com/thisisthepy/python-multiplatform-mobile")
            }
        }
    }
}

//signing {
//    useInMemoryPgpKeys(
//        rootProject.extra["signing_key_id"] as String,
//        rootProject.extra["signing_secret_key"] as String,
//        rootProject.extra["signing_password"] as String
//    )
//    sign(publishing.publications)
//}


fun downloadPythonBuilds() {
    // TODO: Automatically download stand-alone Python builds
    //val downloadDir = "$projectDir/build/python/standalone/$pythonVersion"
}
