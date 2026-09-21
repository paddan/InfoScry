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
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
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
