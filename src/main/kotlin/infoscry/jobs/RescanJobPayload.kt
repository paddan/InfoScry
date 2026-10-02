package infoscry.jobs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one rescan *attempt* was asked to do: continue the operation it names.
 *
 * The payload names the operation rather than restating the work, and that is the point. A rescan outlives
 * its attempts: an attempt that paused for external approval, failed on a missing tool or was cancelled is
 * resumed by queueing another job, and every one of them must continue the same reading — the same snapshot,
 * the same candidate revision, the same distinct-page counters. A payload that carried the settings would let
 * a resume run with another engine than the operation was admitted with, which is exactly what the operation
 * row exists to prevent.
 *
 * [collectionId] and [documentId] travel beside the operation id because a job needs a collection to be
 * scoped to and because the attempt re-reads both rows before it does anything: a document deleted between
 * admission and the attempt is not work this job may resurrect.
 */
@Serializable
data class RescanJobPayload(
    val collectionId: String,
    val documentId: String,
    val operationId: String,
) {

    init {
        require(collectionId.isNotBlank()) { "a rescan payload needs a collection" }
        require(documentId.isNotBlank()) { "a rescan payload needs a document" }
        require(operationId.isNotBlank()) { "a rescan payload needs the operation it continues" }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {

        /**
         * Reads a job's payload back.
         *
         * A payload that cannot be read is a programming error rather than a user error: this application
         * wrote it, and failing loudly beats reading a different document than the one that was admitted.
         */
        fun decode(payload: String?): RescanJobPayload {
            require(!payload.isNullOrBlank()) { "a rescan job has no payload to read" }
            return runCatching { json.decodeFromString(serializer(), payload) }
                .getOrElse { failure ->
                    throw IllegalArgumentException("the rescan job's payload could not be read", failure)
                }
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}
