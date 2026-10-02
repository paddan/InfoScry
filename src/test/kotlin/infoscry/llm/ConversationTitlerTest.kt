package infoscry.llm

import infoscry.storage.Database
import infoscry.storage.LlmStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The title refresh dispatches the opening question, so the rule that a switched-off profile is not a
 * dispatch destination has to hold here too: the caller's route refused it for the answer, and a profile
 * disabled between the answer and this call must not receive the question either.
 *
 * The server is what proves it rather than the title's absence: a title that is never written and a title
 * whose call failed look the same from the outside, so the assertion is on the requests the fake received.
 */
class ConversationTitlerTest {

    private val directory: Path = Files.createTempDirectory("infoscry-titler")
    private val database = Database(directory.resolve("state.db"))

    init {
        SchemaMigrator(database).migrate()
    }

    @AfterTest
    fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a switched-off profile writes no title and makes no call`() = runBlocking {
        val written = mutableMapOf<String, String>()
        val titler = titler(written)
        FakeOpenAiServer(listOf(FakeOpenAiResponse(body = COMPLETION))).use { server ->
            titler.title("conversation", QUESTION, profile(server.url, enabled = false))

            assertEquals(0, server.handledRequests, "a switched-off profile is not a dispatch destination")
            assertEquals(emptyMap(), written, "no title may be written from a call that never happened")
        }
    }

    @Test
    fun `an enabled profile still writes the title`() = runBlocking {
        val written = mutableMapOf<String, String>()
        val titler = titler(written)
        FakeOpenAiServer(listOf(FakeOpenAiResponse(body = COMPLETION))).use { server ->
            titler.title("conversation", QUESTION, profile(server.url, enabled = true))

            assertEquals(1, server.handledRequests, "the control: an enabled profile is called once")
            assertEquals(mapOf("conversation" to "A short title"), written)
        }
    }

    @Test
    fun `a profile a client cannot be built from leaves the row without a title and throws nothing`() = runBlocking {
        // The method's contract is that no titling failure reaches the answer. An enabled profile with no
        // endpoint is accepted by the profile record and refused by the client constructors, so the build
        // itself is one of the failures this contract has to absorb.
        val written = mutableMapOf<String, String>()
        val titler = titler(written)

        titler.title("conversation", QUESTION, profile(endpoint = "", enabled = true))

        assertEquals(emptyMap(), written)
    }

    private fun titler(written: MutableMap<String, String>) =
        ConversationTitler(PromptService(LlmStore(database))) { id, title -> written[id] = title }

    private fun profile(endpoint: String, enabled: Boolean) = LlmProfile(
        id = "profile",
        name = "test",
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "model",
        contextWindow = 10_000,
        maxOutputTokens = 64,
        inputPricePerMillion = 0.0,
        outputPricePerMillion = 0.0,
        cacheReadPricePerMillion = 0.0,
        enabled = enabled,
        endpoint = endpoint,
    )

    private companion object {
        const val QUESTION: String = "What is this about?"

        /** A minimal OpenAI-compatible completion, shaped the way the adapter decodes one. */
        const val COMPLETION: String =
            """{"choices":[{"message":{"role":"assistant","content":"A short title"}}]}"""
    }
}
