import org.gradle.internal.classpath.Instrumented
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
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
    
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "demo"
            isStatic = true
        }
    }
    sourceSets {
        val desktopMain by getting
        
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            api(projects.pythonMultiplatform)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "org.thisisthepy.python.multiplatform.demo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.thisisthepy.python.multiplatform.demo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"/*, "x86_64", "armeabi-v7a", "x86"*/))
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

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.thisisthepy.python.multiplatform.demo.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.thisisthepy.python.multiplatform.demo"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {  // Compiler Settings
    options.compilerArgs.addAll(listOf("--enable-preview", "--add-modules=jdk.incubator.foreign"))
}

tasks.withType<JavaExec>().configureEach {  // JVM Execution Settings
    jvmArgs(
        "--enable-preview",
        "--add-modules=jdk.incubator.foreign",
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.library.path=C:\\Users\\BREW\\Desktop\\Thisisthepy\\PythonMultiplatformMobile\\python-multiplatform\\build\\python\\standalone\\3.13.0\\windows\\amd64_msvc\\install"
    )
}

tasks.withType<Test>().configureEach {  // Test Settings
    jvmArgs(
        "--enable-preview",
        "--add-modules=jdk.incubator.foreign",
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.library.path=C:\\Users\\BREW\\Desktop\\Thisisthepy\\PythonMultiplatformMobile\\python-multiplatform\\build\\python\\standalone\\3.13.0\\windows\\amd64_msvc\\install"
    )
}
