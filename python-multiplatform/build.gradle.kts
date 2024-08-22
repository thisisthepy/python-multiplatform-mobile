import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.Family


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    //id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}

version = "$version-ALPHA01"


fun generateCinteropDefinition(defPath: String, defTemplate: String, includePath: String): File {
    val defFile = project.file(defPath)
    if (!defFile.exists()) {
        defFile.createNewFile()
    }
    defFile.writeText(defTemplate.replace("<_INCL_>", includePath))
    return defFile
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
    val pythonVersion = "${versions[0]}.${versions[1]}"

    val distDir = "$projectDir/dist/toolchain"
    val darwinDir = "$distDir/darwin"
    val mingwDir = "$distDir/mingw"
    val linuxDir = "$distDir/linux"

    val defTemplate = "src/nativeInterop/cinterop/python.def.template"
    val template = String(project.file(defTemplate).readBytes())
        .replace("<_PY_VER_>", pythonVersion)
        .replace("<_DARWIN_>", darwinDir)
        .replace("<_MINGW_>", mingwDir)
        .replace("<_LINUX_>", linuxDir)

    val pythonDarwinDef = "src/nativeInterop/cinterop/python$pythonVersion-darwin.def"
    val darwinIncludes = "$darwinDir/root/python3/include/python$pythonVersion"
    val pythonMingwDef = "src/nativeInterop/cinterop/python$pythonVersion-mingw.def"
    val mingwIncludes = "$mingwDir/python3/include/python$pythonVersion"

    val pythonDarwinDefFile = generateCinteropDefinition(pythonDarwinDef, template, darwinIncludes)
    val pythonMingwDefFile = generateCinteropDefinition(pythonMingwDef, template, mingwIncludes)
    
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    
    jvm("desktop")
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        //macosX64(),
        //macosArm64(),
        //mingwX64(),
        //linuxX64(),
        //linuxArm64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            val main by compilations.getting {
                val python by cinterops.creating {
                    // Supported platforms
                    // https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/native/src/org/jetbrains/kotlin/ide/konan/NativeDefinitions.flex
                    defFile(if (konanTarget.family == Family.MINGW) pythonMingwDefFile else pythonDarwinDefFile)
                    packageName("python.native.ffi")
                    compilerOpts(when(konanTarget.family) {
                        Family.MINGW -> listOf(
                            "-include-binary", "$mingwDir/python3/lib/libpython3.11.dll.a"
                        )
                        Family.IOS -> listOf(
                            "-include-binary", "$darwinDir/lib/iphoneos/libpython3.11.a",
                            "-include-binary", "$darwinDir/lib/iphoneos/libcrypto.a",
                            "-include-binary", "$darwinDir/lib/iphoneos/libffi.a",
                            "-include-binary", "$darwinDir/lib/iphoneos/libpyobjus.a",
                            "-include-binary", "$darwinDir/lib/iphoneos/libssl.a"
                        )
                        Family.OSX -> listOf(
                            "-include-binary", "$darwinDir/hostpython/lib/libpython3.11.a",
                            "-include-binary", "$darwinDir/hostopenssl/lib/libcrypto.a",
                            "-include-binary", "$darwinDir/hostopenssl/lib/libssl.a",
                        )
                        else -> listOf()
                    })
                }
            }
            binaries.framework {
                if (konanTarget.family == Family.IOS) {
                    baseName = "python"
                    isStatic = true
                }
            }
        }
    }
    
    sourceSets {
        val desktopMain by getting
    }
}

android {
    namespace = "org.thisisthepy.python.multiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
