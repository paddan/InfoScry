import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "infoscry"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

application {
    mainClass.set("infoscry.MainKt")
    // The SQLite JDBC driver and, later, ONNX Runtime load their native libraries through
    // System.load. JDK 25 warns about that unless native access is granted, and a future JDK
    // blocks it, so the permission is part of how the application and its tests are started.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val webDir = layout.projectDirectory.dir("web")

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    group = "verification"
    description = "Install frontend dependencies with npm ci"
    workingDir = file("web")
    commandLine("npm", "ci")
    outputs.dir(webDir.dir("node_modules"))
}

val frontendTest = tasks.register<Exec>("frontendTest") {
    group = "verification"
    description = "Run the Vitest frontend tests"
    dependsOn(frontendInstall)
    workingDir = file("web")
    commandLine("npm", "test", "--", "--run")
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    group = "build"
    description = "Build the static SvelteKit frontend"
    dependsOn(frontendInstall)
    workingDir = file("web")
    commandLine("npm", "run", "build")
    inputs.files(webDir.dir("src").asFileTree)
    outputs.dir(webDir.dir("build"))
}

tasks.named("check") {
    dependsOn(frontendTest)
}

tasks.processResources {
    dependsOn(frontendBuild)
    from(webDir.dir("build")) {
        into("static")
    }
}
