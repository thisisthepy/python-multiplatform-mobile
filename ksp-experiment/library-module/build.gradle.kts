plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    ksp(project(":ksp-processor"))
}

ksp {
    arg("experiment.role", "library")
    arg("experiment.moduleName", "library_module")
}
