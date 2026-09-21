import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The JUnit tag for tests that need a tool installed on this machine rather than a stand-in. */
val EXTERNAL_TAG = "external"

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
    // The PDF extractor: PDFBox reads one page's text layer at a time and renders only the pages that
    // OCR has to read.
    implementation(libs.pdfbox)
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
    // `external` is the tag for the tests that need a tool installed on this machine: the real OCR
    // reading, and later the real Calibre conversion. They are excluded here and run by `externalTest`,
    // because a suite that failed wherever a tool is absent would be a suite people learn to ignore.
    useJUnitPlatform { excludeTags(EXTERNAL_TAG) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val externalTest = tasks.register<Test>("externalTest") {
    group = "verification"
    description = "Run the tests that need the tools this machine has installed ($EXTERNAL_TAG)"
    useJUnitPlatform { includeTags(EXTERNAL_TAG) }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

/*
 * The tag above and the one the tests annotate themselves with have to be the same string, or the default
 * suite silently gains a test that fails wherever a tool is absent — the exact outcome the tag prevents.
 * Kotlin needs the annotation's value at compile time, so it cannot be read from this script; instead this
 * checks that the one place the tests declare it declares the same value.
 */
val externalTagSource = layout.projectDirectory.file("src/test/kotlin/infoscry/ExternalTag.kt")

val verifyExternalTag = tasks.register("verifyExternalTag") {
    group = "verification"
    description = "Fail if the tests and this build declare different external JUnit tags"
    val source = externalTagSource.asFile
    val tag = EXTERNAL_TAG
    inputs.file(source)
    doLast {
        val declaration = "const val EXTERNAL_TAG: String = \"$tag\""
        check(source.readText().contains(declaration)) {
            "${source.path} must declare `$declaration` so the default suite keeps excluding the " +
                "tests that need installed tools"
        }
    }
}

tasks.named("check") { dependsOn(verifyExternalTag) }

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

// The PDF fixtures are written by PDFBox itself and are byte-stable, so a maintainer can regenerate them
// and see exactly which bytes changed.
val pdfFixtures = tasks.register<JavaExec>("pdfFixtures") {
    group = "build"
    description = "Regenerate the committed PDF fixtures under src/test/resources/fixtures"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("infoscry.fixtures.PdfFixtureGenerator")
}

// The OCR fixture is a rendered page, so its bytes follow this machine's glyph rasteriser rather than
// this repository; the task exists so the image can be rebuilt and re-read by a person, not so its bytes
// can be compared.
val ocrFixtures = tasks.register<JavaExec>("ocrFixtures") {
    group = "build"
    description = "Regenerate the committed OCR image fixture under src/test/resources/fixtures"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("infoscry.fixtures.OcrFixtureGenerator")
}

// The e-book fixtures are zips, so their compressed streams follow the JDK's deflate implementation;
// the task exists so a maintainer can rebuild them and see which content changed. The tests assert what
// the extraction reads rather than comparing the containers byte for byte.
val ebookFixtures = tasks.register<JavaExec>("ebookFixtures") {
    group = "build"
    description = "Regenerate the committed e-book fixtures under src/test/resources/fixtures"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("infoscry.fixtures.EbookFixtureGenerator")
}
