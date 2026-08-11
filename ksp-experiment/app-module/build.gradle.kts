plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    application
}

application {
    mainClass.set("experiment.app.MainKt")
}

dependencies {
    implementation(project(":library-module"))
    ksp(project(":ksp-processor"))
}

ksp {
    arg("experiment.role", "app")
    arg("experiment.moduleName", "app_module")
}
