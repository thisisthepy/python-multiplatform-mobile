import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget.*


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
    packageName = "${project.name.lowercase().replace("-", ".")}"
    objectName = "BuildConfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "pythonVersion", pythonVersion)
        buildConfigField(FieldSpec.Type.STRING, "libraryVersion", libraryVersion)
    }
}


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

    val versions = version.toString().split('.')
    val libVersion = "${versions[0]}.${versions[1]}"

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        afterEvaluate {
            val copyAndroidPythonBinaries by tasks.creating(Copy::class) {
                dependsOn(
                    tasks.named("linkAndroidNativeArm64"),
                    tasks.named("linkAndroidNativeX64")
                )
                into("$projectDir/build/android/jniLibs/")
                from("src/artMain/prebuilt/arm64-v8a/lib") {
                    include("libpython*.*.so")
                    include("lib*_python.so")
                    into("arm64-v8a")
                }
                from("src/artMain/prebuilt/x86_64/lib") {
                    include("libpython*.*.so")
                    include("lib*_python.so")
                    into("x86_64")
                }
            }
            val copyAndroidPythonAssets by tasks.creating(Copy::class) {
                into("$projectDir/build/android/assets/")
                from("src/artMain/prebuilt/arm64-v8a/include") {
                    into("arm64-v8a/include")
                }
                from("src/artMain/prebuilt/x86_64/include") {
                    into("x86_64/include")
                }
                from("src/artMain/prebuilt/arm64-v8a/lib/python$libVersion") {
                    exclude("config-$libVersion-aarch64-linux-android/")
                    into("arm64-v8a/lib/python$libVersion")
                }
                from("src/artMain/prebuilt/x86_64/lib/python$libVersion") {
                    exclude("config-$libVersion-x86_64-linux-android/")
                    into("x86_64/lib/python$libVersion")
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
            from("$projectDir/src/desktopMain/LICENSE") {
                into("META-INF/LICENSE")
            }
        }
    }
    
    listOf(
        // Supported platforms
        // https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/native/src/org/jetbrains/kotlin/ide/konan/NativeDefinitions.flex
        iosArm64(), iosSimulatorArm64(), iosX64(),
        androidNativeArm64(), androidNativeX64(),
        //macosArm64(), macosX64(),
        //linuxX64(), linuxArm64()
        //mingwX64(),
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            val main by compilations.getting {
                val python by cinterops.creating {
                    var baseDir = when(target.konanTarget) {
                        ANDROID_ARM64 -> "src/artMain/prebuilt/arm64-v8a"
                        ANDROID_X64 -> "src/artMain/prebuilt/x86_64"
                        IOS_ARM64 -> "src/iosMain/framework/Python.xcframework/ios-arm64"
                        IOS_X64 -> "src/iosMain/framework/Python.xcframework/ios-arm64_x86_64-simulator"
                        IOS_SIMULATOR_ARM64 -> "src/iosMain/framework/Python.xcframework/ios-arm64_x86_64-simulator"
                        else -> throw RuntimeException("Unsupported ABI: ${target.konanTarget}")
                    }
                    if (target.konanTarget.family == Family.IOS) {
                        compilerOpts("-F$projectDir/$baseDir")
                        baseDir += "/Python.framework/Headers"
                    } else {
                        baseDir += "/include/python$libVersion"
                    }
                    headers(
                        "$baseDir/Python.h", "$baseDir/object.h",
                        "$baseDir/pythonrun.h", "$baseDir/cpython/initconfig.h"
                    )
                    includeDirs("$baseDir/", "$baseDir/cpython/")
                    packageName("python.native.ffi")
                }
            }
            binaries {
                if (konanTarget.family == Family.IOS) {
                    val frameworkPath = "$projectDir/src/iosMain/framework/Python.xcframework" +
                        when(target.konanTarget) {
                            IOS_ARM64 -> "ios-arm64"
                            else -> "ios-arm64_x86_64-simulator"
                        }
                    framework {
                        baseName = "PythonMultiplatform"
                        //isStatic = true
                        linkerOpts.addAll(listOf("-F$frameworkPath", "-framework", "Python"))
                        export("$projectDir/src/iosMain/framework/Python.xcframework")

                        isStatic = false
                        embedBitcode(org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode.DISABLE)

                        tasks.register<Copy>("copyPythonXCFramework") {
                            from("$projectDir/src/iosMain/framework/Python.xcframework")
                            into("$projectDir/build/xcode-frameworks/Python.xcframework")
                        }

                        tasks.named("linkReleaseFrameworkIosArm64").configure {
                            dependsOn("copyPythonXCFramework")
                        }
                        tasks.named("linkReleaseFrameworkIosX64").configure {
                            dependsOn("copyPythonXCFramework")
                        }
                        tasks.named("linkReleaseFrameworkIosSimulatorArm64").configure {
                            dependsOn("copyPythonXCFramework")
                        }
                    }
                } else if (konanTarget.family == Family.ANDROID) {
                    sharedLib("multiplatform_python$libVersion") {
                        val abi = when(target.konanTarget) {
                            ANDROID_ARM64 -> "arm64-v8a"
                            ANDROID_X64 -> "x86_64"
                            else -> throw RuntimeException("Unsupported ABI: ${target.konanTarget}")
                        }
                        val type = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
                        val libPath = "$projectDir/src/artMain/prebuilt/$abi/lib/"
                        linkerOpts.addAll(listOf("-L$libPath", "-lpython$libVersion"))

                        linkTaskProvider.configure {
                            copy {
                                from(outputFile)
                                into(file("$projectDir/build/android/$type/jniLibs/$abi/"))
                            }
                        }

                        afterEvaluate {
                            val preBuild by tasks.getting
                            preBuild.dependsOn(linkTaskProvider)
                        }
                    }
                }
            }
        }
    }
    
    sourceSets {
        val jvmMain by creating
        val commonMain by getting
        val desktopMain by getting {
            resources.srcDir("src/desktopMain/resources")
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
            val iosMain by getting {
//                dependencies {
//                    implementation {
//                        artifacts {
//                            add("default", fileTree(mapOf(
//                                "dir" to "src/iosMain/framework",
//                                "include" to listOf("**/*.xcframework")
//                            )))
//                        }
//                    }
//                }

            }
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

    sourceSets["main"].assets.srcDirs("src/androidMain/assets", "$projectDir/build/android/assets")
    sourceSets["debug"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$projectDir/build/android/jniLibs", "$projectDir/build/android/debug/jniLibs")
    sourceSets["release"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$projectDir/build/android/jniLibs", "$projectDir/build/android/release/jniLibs")

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
