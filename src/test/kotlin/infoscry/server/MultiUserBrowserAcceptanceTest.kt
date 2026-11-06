package infoscry.server

import infoscry.EXTERNAL_TAG
import infoscry.domain.CollectionId
import infoscry.jobs.Harness
import infoscry.jobs.ImportRootSource
import infoscry.jobs.SingleLargeUnit
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

/**
 * Real Chromium and HTTP against a disposable archive: an administrator adds a user and makes them an
 * editor, the user signs in and edits the glossary, and the administrator sees who made the edit. A server
 * folder the administrator adds under Admin is what the editor browses when importing.
 */
@Tag(EXTERNAL_TAG)
class MultiUserBrowserAcceptanceTest {

    @Test
    fun `a new editor signs in, edits a collection and is shown as the one who changed it`() {
        val tempDir = Files.createTempDirectory("infoscry-multi-user-browser-")
        try {
            Harness(tempDir).use { importer ->
                val text = "Brev från Stockholm i maj"
                importer.importDurably(listOf(importer.writeText("letter.txt", text)), SingleLargeUnit(text))
            }
            val serverFolder = Files.createDirectories(tempDir.resolve("server-folder")).toRealPath()
            Files.writeString(serverFolder.resolve("brev.txt"), "Brev från Uppsala.\n")
            ApiTestServer(tempDir.resolve("data")).use { harness ->
                val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "multi-user-browser-acceptance.mjs")
                val outputFile = Files.createTempFile("infoscry-multi-user-browser-", ".log")
                try {
                    val process = ProcessBuilder("node", script.toString())
                        .directory(script.parent.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .apply {
                            environment()["BASE_URL"] = harness.url
                            environment()["INFOSCRY_SESSION_COOKIE"] = harness.sessionCookie
                            environment()["INFOSCRY_SERVER_FOLDER"] = serverFolder.toString()
                        }
                        .start()
                    if (!process.waitFor(180, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                        error("Multi-user browser acceptance timed out: ${Files.readString(outputFile)}")
                    }
                    check(process.exitValue() == 0) { "Multi-user browser acceptance failed: ${Files.readString(outputFile)}" }
                } finally {
                    Files.deleteIfExists(outputFile)
                }
                // The group Erik saved in his browser is the collection's.
                val groups = harness.context.glossaryService.snapshot(CollectionId("default")).groups
                assertEquals(1, groups.size)
                // The folder added in the browser is stored, attributed to the administrator.
                val added = harness.context.importRoots.views().single { it.source == ImportRootSource.ADMIN }
                assertEquals(serverFolder.toString(), added.path)
                assertEquals(harness.admin.displayName, added.addedBy)
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
