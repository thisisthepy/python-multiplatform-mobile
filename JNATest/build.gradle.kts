import org.gradle.internal.classpath.Instrumented.systemProperty

plugins {
    kotlin("jvm") version "2.0.20"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.java.dev.jna:jna:5.12.1")
    implementation("net.java.dev.jna:jna-platform:5.12.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(19)
}

run {
    systemProperty("jna.library.path", "C:\\Users\\BREW\\AppData\\Local\\Programs\\Python\\Python311")
}
