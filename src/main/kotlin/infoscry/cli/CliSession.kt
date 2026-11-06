package infoscry.cli

import infoscry.correction.CorrectionRuleInput

import infoscry.server.CorrectionRuleWire
import infoscry.server.GlossaryGroupWire
import infoscry.server.GlossaryListWire
import infoscry.server.GlossaryTermWire
import infoscry.server.CorrectionPreviewWire
import infoscry.server.CorrectionListWire
import infoscry.collection.CollectionPrompts
import infoscry.domain.Collection
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.llm.CollectionPrompt
import infoscry.llm.LlmPromptRole

/**
 * What a CLI command works through: the InfoScry server, as an HTTP client authenticated with the caller's
 * personal access token. The server owns the database and every job, so the CLI never opens either.
 */
class CliSession(val api: ApiClient) : AutoCloseable {


    suspend fun listCollections(): List<Collection> = api.listCollections()

    suspend fun createCollection(name: String, description: String?): Collection =
        api.createCollection(name = name, description = description)

    suspend fun ignorePatterns(collection: String): List<String> = api.ignorePatterns(collection)

    suspend fun setIgnorePatterns(collection: String, patterns: List<String>): List<String> =
        api.setIgnorePatterns(collection, patterns)

    suspend fun listPrompts(collection: String): CollectionPrompts = api.listPrompts(collection)

    suspend fun createPrompt(collection: String, role: LlmPromptRole, name: String, body: String): CollectionPrompt =
        api.createPrompt(collection, role, name, body)

    suspend fun updatePrompt(
        collection: String,
        role: LlmPromptRole,
        promptId: String,
        name: String,
        body: String,
    ): CollectionPrompt = api.updatePrompt(collection, role, promptId, name, body)

    suspend fun deletePrompt(collection: String, promptId: String) = api.deletePrompt(collection, promptId)

    suspend fun setPromptDefault(collection: String, role: LlmPromptRole, promptId: String): String =
        api.setPromptDefault(collection, role, promptId)

    suspend fun listJobs(limit: Int): List<Job> = api.listJobs(limit)

    suspend fun cancelJob(id: JobId): Job = api.cancelJob(id)

    suspend fun listCorrections(collection: String): CorrectionListWire = api.listCorrections(collection)

    suspend fun putCorrection(collection: String, id: String, input: CorrectionRuleInput): CorrectionRuleWire =
        api.putCorrection(collection, id, input)

    suspend fun deleteCorrection(collection: String, id: String) = api.deleteCorrection(collection, id)

    suspend fun previewCorrection(collection: String, input: CorrectionRuleInput): CorrectionPreviewWire =
        api.previewCorrection(collection, input)

    suspend fun listGlossary(collection: String): GlossaryListWire = api.listGlossary(collection)

    suspend fun putGlossaryGroup(collection: String, id: String, terms: List<GlossaryTermWire>): GlossaryGroupWire =
        api.putGlossaryGroup(collection, id, terms)

    suspend fun deleteGlossaryGroup(collection: String, id: String) = api.deleteGlossaryGroup(collection, id)

    suspend fun job(id: JobId): Job = api.getJob(id)

    override fun close() {
        api.close()
    }

    companion object {

        /** A session with the server named by `INFOSCRY_URL`, authenticated with `INFOSCRY_TOKEN`. */
        fun connect(): CliSession = CliSession(ApiClient(ServerAddress.fromEnvironment()))
    }
}
