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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    // Content-based media type detection. Only Tika's core is wired here: the format *parsers* arrive
    // with the tasks that own those formats, which use PDFBox, POI, jsoup, and Commons CSV directly.
    implementation(libs.tika.core)
    // The text, Markdown, HTML, and CSV extractors: jsoup parses and sanitises markup, Commons CSV parses
    // records whose fields may contain the delimiter or a newline.
    implementation(libs.jsoup)
    implementation(libs.commons.csv)
    // The Word, spreadsheet, and presentation extractors. POI reads the OOXML family (XWPF/XSSF/XSLF)
    // from poi-ooxml, and the legacy binary formats (HWPF/HSSF/HSLF) from poi-scratchpad.
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)
    // The local API: the embedded server, its Netty engine, and the loopback client the CLI and the
    // tests use to talk to a running server.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.client.cio)
    // Logging: the console encoder, the rolling JSON file sink, and the redaction filter the
    // configuration in logback.xml names.
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
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

// The committed Office fixtures are evidence a reader can inspect, not build output: this task exists so
// a maintainer can regenerate them and see exactly which bytes changed. It needs no converter for five of
// the six files; the legacy .doc needs LibreOffice or macOS textutil, and is left untouched without one.
val officeFixtures = tasks.register<JavaExec>("officeFixtures") {
    group = "build"
    description = "Regenerate the committed Office fixtures under src/test/resources/fixtures"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("infoscry.fixtures.OfficeFixtureGenerator")
}
