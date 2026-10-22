package infoscry.cli

import infoscry.server.toApiView
import infoscry.server.CorrectionRuleWire
import infoscry.server.CorrectionPreviewWire
import infoscry.server.CorrectionListWire
import infoscry.server.CorrectionExampleWire
import infoscry.correction.CorrectionRuleInput
import infoscry.AppContext
import infoscry.collection.CollectionPrompts
import infoscry.config.AppPaths
import infoscry.config.RuntimeInfo
import infoscry.domain.Collection
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.llm.CollectionPrompt
import infoscry.llm.LlmPromptRole

/**
 * Where a CLI command does its work: in this process, or through the server that already owns the data
 * directory.
 *
 * Only one process may write a data directory, so the choice is not a preference — if a server is
 * running, opening the database here would either fail or corrupt the state the server is holding. The
 * CLI therefore asks the server when it can, and works locally only when nothing else holds the lock.
 * Both cases go through the same operations, so a command does not care which one it got.
 */
sealed interface CliSession : AutoCloseable {

    /** Whether the work is being done by a server this process is talking to. */
    val isRemote: Boolean

    suspend fun listCollections(): List<Collection>

    suspend fun createCollection(
        name: String,
        description: String? = null,
    ): Collection

    /** The ignore patterns of [collection], named or identified. */
    suspend fun ignorePatterns(collection: String): List<String>

    /** Replaces the ignore patterns of [collection]; an invalid pattern fails and changes nothing. */
    suspend fun setIgnorePatterns(collection: String, patterns: List<String>): List<String>

    /** Every prompt of [collection] by role, with the shipped Default first in each role. */
    suspend fun listPrompts(collection: String): CollectionPrompts

    suspend fun createPrompt(collection: String, role: LlmPromptRole, name: String, body: String): CollectionPrompt

    suspend fun updatePrompt(
        collection: String,
        role: LlmPromptRole,
        promptId: String,
        name: String,
        body: String,
    ): CollectionPrompt

    /** Deletes a stored prompt; deleting one that is already gone succeeds. */
    suspend fun deletePrompt(collection: String, promptId: String)

    /** Selects the default prompt for [role] and returns the id now selected. */
    suspend fun setPromptDefault(collection: String, role: LlmPromptRole, promptId: String): String

    suspend fun listJobs(limit: Int): List<Job>

    /** The correction rules of [collection] with their matches, and its correction job. */
    suspend fun listCorrections(collection: String): CorrectionListWire

    /** Stores rule [id] and starts the collection's correction job. */
    suspend fun putCorrection(collection: String, id: String, input: CorrectionRuleInput): CorrectionRuleWire

    /** Removes rule [id]; removing one that is already gone succeeds. */
    suspend fun deleteCorrection(collection: String, id: String)

    /** What [input] would change, without storing anything. */
    suspend fun previewCorrection(collection: String, input: CorrectionRuleInput): CorrectionPreviewWire

    suspend fun job(id: JobId): Job

    /** Persists the cancellation request and, when this process owns the attempt, interrupts it. */
    suspend fun cancelJob(id: JobId): Job

    /** Works directly on the data directory, holding the process lock for the duration. */
    class Local(val context: AppContext) : CliSession {

        override val isRemote: Boolean get() = false

        override suspend fun listCollections(): List<Collection> = context.collectionService.list()

        override suspend fun createCollection(name: String, description: String?): Collection =
            context.collectionService.create(name = name, description = description)

        override suspend fun ignorePatterns(collection: String): List<String> =
            context.collectionService.ignorePatterns(context.collectionService.requireActiveByNameOrId(collection).id).patterns

        override suspend fun setIgnorePatterns(collection: String, patterns: List<String>): List<String> =
            context.collectionService.updateIgnorePatterns(
                context.collectionService.requireActiveByNameOrId(collection).id,
                patterns,
            ).patterns

        override suspend fun listPrompts(collection: String): CollectionPrompts =
            context.collectionPrompts.list(context.collectionService.requireActiveByNameOrId(collection).id)

        override suspend fun createPrompt(collection: String, role: LlmPromptRole, name: String, body: String): CollectionPrompt =
            context.collectionPrompts.create(
                context.collectionService.requireActiveByNameOrId(collection).id,
                role,
                name,
                body,
            ).prompt

        override suspend fun updatePrompt(
            collection: String,
            role: LlmPromptRole,
            promptId: String,
            name: String,
            body: String,
        ): CollectionPrompt = context.collectionPrompts.update(
            context.collectionService.requireActiveByNameOrId(collection).id,
            promptId,
            name,
            body,
        )

        override suspend fun deletePrompt(collection: String, promptId: String) {
            context.collectionPrompts.delete(context.collectionService.requireActiveByNameOrId(collection).id, promptId)
        }

        override suspend fun setPromptDefault(collection: String, role: LlmPromptRole, promptId: String): String =
            context.collectionPrompts.setDefault(
                context.collectionService.requireActiveByNameOrId(collection).id,
                role,
                promptId,
            )

        override suspend fun listJobs(limit: Int): List<Job> = context.jobs.list(limit = limit)

        // A short-lived CLI has no worker, so the durable request is the whole action: no other process
        // can be running this data directory's jobs, because this process holds its lock.
        override suspend fun cancelJob(id: JobId): Job = context.cancelJob(id)

        private fun collectionIdOf(collection: String) = context.collectionService.requireActiveByNameOrId(collection).id

        override suspend fun listCorrections(collection: String): CorrectionListWire {
            val view = context.correctionService.list(collectionIdOf(collection))
            return CorrectionListWire(
                view.rules.map { CorrectionRuleWire(it.rule.id, it.rule.find, it.rule.replace, it.rule.exactCase, it.matches) },
                view.job?.toApiView(context),
            )
        }

        override suspend fun putCorrection(collection: String, id: String, input: CorrectionRuleInput): CorrectionRuleWire =
            context.correctionService.put(collectionIdOf(collection), id, input)
                .let { CorrectionRuleWire(it.id, it.find, it.replace, it.exactCase) }

        override suspend fun deleteCorrection(collection: String, id: String) =
            context.correctionService.delete(collectionIdOf(collection), id)

        override suspend fun previewCorrection(collection: String, input: CorrectionRuleInput): CorrectionPreviewWire =
            context.correctionService.preview(collectionIdOf(collection), input, replacingId = null).let { preview ->
                CorrectionPreviewWire(
                    preview.matches,
                    preview.documents,
                    preview.examples.map { CorrectionExampleWire(it.documentId.value, it.unitId.value, it.before, it.after) },
                )
            }

        override suspend fun job(id: JobId): Job = context.jobs.get(id) ?: throw NoSuchElementException("no job with id ${id.value}")

        override fun close() {
            context.close()
        }
    }

    /** Works through a server that owns the data directory. */
    class Remote(private val api: LoopbackApi) : CliSession {

        override val isRemote: Boolean get() = true

        override suspend fun listCollections(): List<Collection> = api.listCollections()

        override suspend fun createCollection(name: String, description: String?): Collection =
            api.createCollection(name = name, description = description)

        override suspend fun ignorePatterns(collection: String): List<String> = api.ignorePatterns(collection)

        override suspend fun setIgnorePatterns(collection: String, patterns: List<String>): List<String> =
            api.setIgnorePatterns(collection, patterns)

        override suspend fun listPrompts(collection: String): CollectionPrompts = api.listPrompts(collection)

        override suspend fun createPrompt(collection: String, role: LlmPromptRole, name: String, body: String): CollectionPrompt =
            api.createPrompt(collection, role, name, body)

        override suspend fun updatePrompt(
            collection: String,
            role: LlmPromptRole,
            promptId: String,
            name: String,
            body: String,
        ): CollectionPrompt = api.updatePrompt(collection, role, promptId, name, body)

        override suspend fun deletePrompt(collection: String, promptId: String) = api.deletePrompt(collection, promptId)

        override suspend fun setPromptDefault(collection: String, role: LlmPromptRole, promptId: String): String =
            api.setPromptDefault(collection, role, promptId)

        override suspend fun listJobs(limit: Int): List<Job> = api.listJobs(limit)

        override suspend fun cancelJob(id: JobId): Job = api.cancelJob(id)

        override suspend fun listCorrections(collection: String): CorrectionListWire = api.listCorrections(collection)

        override suspend fun putCorrection(collection: String, id: String, input: CorrectionRuleInput): CorrectionRuleWire =
            api.putCorrection(collection, id, input)

        override suspend fun deleteCorrection(collection: String, id: String) = api.deleteCorrection(collection, id)

        override suspend fun previewCorrection(collection: String, input: CorrectionRuleInput): CorrectionPreviewWire =
            api.previewCorrection(collection, input)

        override suspend fun job(id: JobId): Job = api.getJob(id)

        override fun close() {
            api.close()
        }
    }

    companion object {

        /**
         * The running server for [paths], or this process when there is none.
         *
         * A `runtime.json` whose process is gone is treated as absent rather than as a server: the file
         * outlives a killed process, and being sent to a dead port would be a confusing way to learn
         * that nothing is running.
         */
        fun connect(paths: AppPaths): CliSession {
            val runtime = RuntimeInfo.discover(paths.runtimeFile)
            return if (runtime == null) {
                Local(AppContext.open(paths))
            } else {
                Remote(LoopbackApi(runtime))
            }
        }
    }
}
