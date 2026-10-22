package infoscry

import infoscry.cli.productionImportEmbedder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * One process holds one accelerator session for the pinned model. Search, imports, restores and reindexing
 * all embed with the same model, and each extra session costs another 1.1 GB load and CoreML compilation —
 * which, built lazily by the first search while an import builds its own, is what made that search slow.
 */
class AppContextEmbedderTest {

    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `the import worker embeds with the context's one pinned embedder`() {
        val dataDir = Files.createTempDirectory("infoscry-embedder").also(temporaryDirectories::add)
        AppContext.open(dataDir).use { context ->
            assertSame(context.pinnedEmbedder, productionImportEmbedder(context))
        }
    }
}
