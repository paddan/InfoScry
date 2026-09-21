import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The JUnit tag for tests that need a tool installed on this machine rather than a stand-in. */
val EXTERNAL_TAG = "external"

/** The JUnit tags for the tests that need the pinned model and the required accelerator. */
val MODEL_TAG = "model"
val GPU_TAG = "gpu"

/**
 * Where the embedding model is installed, and where the GPU run expects to find it.
 *
 * The model is not in the repository, so a run that needs it points at a data directory the same way the
 * application does: `~/.infoscry` unless `-PdataDir=` says otherwise.
 */
val dataDir: String = (findProperty("dataDir") as String?)
    ?: "${System.getProperty("user.home")}/.infoscry"

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
    // The embedding model: ONNX Runtime runs the pinned E5 export through Apple's CoreML execution
    // provider, and DJL's Hugging Face tokenizer reads the model's own tokenizer.json so the chunker and
    // the embedder measure text exactly the way the model does.
    implementation(libs.onnxruntime)
    implementation(libs.djl.tokenizers)

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
    //
    // `model` and `gpu` are excluded for a stronger reason: they need the pinned weights and the
    // accelerator, so the hardware gate is `gpuIntegrationTest`, which fails rather than skips.
    useJUnitPlatform { excludeTags(EXTERNAL_TAG, MODEL_TAG, GPU_TAG) }
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
 * The tags above and the ones the tests annotate themselves with have to be the same strings, or the
 * default suite silently gains a test that fails wherever a tool or a GPU is absent — the exact outcome the
 * tags prevent. Kotlin needs the annotation's value at compile time, so it cannot be read from this script;
 * instead this checks that the places the tests declare them declare the same values.
 */
val externalTagSource = layout.projectDirectory.file("src/test/kotlin/infoscry/ExternalTag.kt")
val gpuTagSource = layout.projectDirectory.file("src/test/kotlin/infoscry/GpuTag.kt")

val verifyTestTags = tasks.register("verifyTestTags") {
    group = "verification"
    description = "Fail if the tests and this build declare different JUnit tags"
    val externalFile = externalTagSource.asFile
    val gpuFile = gpuTagSource.asFile
    val external = EXTERNAL_TAG
    val model = MODEL_TAG
    val gpu = GPU_TAG
    inputs.files(externalFile, gpuFile)
    doLast {
        fun require(file: java.io.File, declaration: String) {
            check(file.readText().contains(declaration)) {
                "${file.path} must declare `$declaration` so the suites keep running the tests they name"
            }
        }
        require(externalFile, "const val EXTERNAL_TAG: String = \"$external\"")
        require(gpuFile, "const val GPU_TAG: String = \"$gpu\"")
        require(gpuFile, "const val MODEL_TAG: String = \"$model\"")
    }
}

tasks.named("check") { dependsOn(verifyTestTags) }

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
    // The pinned model manifest is authored in `models/` so a reader can inspect the pin without reading
    // Kotlin, and copied onto the classpath so the application and its tests read that one file.
    from(project.layout.projectDirectory.dir("models")) {
        include("embedding-model.json")
        into("models")
    }
    from(webDir.dir("build")) {
        into("static")
    }
}

/**
 * The hardware gate: the tests that load the pinned model on the required accelerator.
 *
 * It includes tags rather than naming classes, and it must FAIL when the hardware or the model files are
 * missing — a gate that skipped would report success while proving nothing. Use `-PdataDir=` to point at the
 * data directory that holds the model.
 */
val gpuIntegrationTest = tasks.register<Test>("gpuIntegrationTest") {
    group = "verification"
    description = "Run the pinned model on the required accelerator ($MODEL_TAG, $GPU_TAG)"
    useJUnitPlatform { includeTags(MODEL_TAG, GPU_TAG) }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("infoscry.dataDir", dataDir)
    outputs.upToDateWhen { false }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

/**
 * Installs the pinned embedding model into the data directory, verifying every checksum.
 *
 * This is the task the application's remedy names, and the only supported way to get the weights: about
 * 1.1 GB from the pinned revision, never committed to the repository.
 */
val embeddingModel = tasks.register<JavaExec>("embeddingModel") {
    group = "build"
    description = "Download and verify the pinned embedding model into $dataDir"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("infoscry.embedding.ModelInstaller")
    systemProperty("infoscry.dataDir", dataDir)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
