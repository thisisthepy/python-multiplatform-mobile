import org.jetbrains.kotlin.konan.target.Family

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    //id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}

fun generateCinteropDefinition(defPath: String, defTemplate: String, includePath: String): File {
    val defFile = project.file(defPath)
    if (!defFile.exists()) {
        defFile.createNewFile()
    }
    defFile.writeText(defTemplate.replace("<_INCL_>", includePath))
    return defFile
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
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

    targetHierarchy.default()

    js(IR) {
        browser()
    }

    ios()

//    androidTarget {
//        compilations.all {
//            kotlinOptions {
//                jvmTarget = JavaVersion.VERSION_1_8.toString()
//            }
//            publishLibraryVariants("release", "debug")
//            //publishLibraryVariantsGroupedByFlavor = true
//        }
//    }
//
//    jvm("desktop") {
//        compilations.all {
//            kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
//        }
//    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        macosX64(),
        macosArm64(),
        mingwX64(),
        //linuxX64(),
        //linuxArm64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            val main by compilations.getting {
                kotlinOptions {
                    freeCompilerArgs = when(konanTarget.family) {
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
                    }
                }
                val python by cinterops.creating {
                    // Supported platforms
                    // https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/native/src/org/jetbrains/kotlin/ide/konan/NativeDefinitions.flex
                    defFile(if (konanTarget.family == Family.MINGW) pythonMingwDefFile else pythonDarwinDefFile)
                    packageName("python.native.ffi")
                }
            }
            binaries {
//                staticLib {
//                    binaryOptions["memoryModel"] = "experimental"
//                    freeCompilerArgs += listOf("-Xgc=cms")
//                }
                if (konanTarget.family != Family.MINGW) {
                    framework {
                        baseName = "python"
                        isStatic = true
                    }
                }
            }
        }
    }

    sourceSets {
        withSourcesJar()

        val commonMain by getting {
            dependencies {
                //implementation(libs.stately.concurrent.collections)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jsMain by getting
        val mingwX64Main by getting
        //val linuxX64Main by getting
        //val linuxArm64Main by getting
        val macosX64Main by getting
        val macosArm64Main by getting
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by getting {
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
        val nativeMain by getting {
            dependsOn(commonMain)
            macosX64Main.dependsOn(this)
            macosArm64Main.dependsOn(this)
            mingwX64Main.dependsOn(this)
            //linuxX64Main.dependsOn(this)
            //linuxArm64Main.dependsOn(this)
            iosMain.dependsOn(this)
        }
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
            description.set("Python Multiplatform Build Tool with Kotlin Multiplatform Mobile")
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
