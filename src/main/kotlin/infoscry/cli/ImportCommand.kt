package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.jobs.ExtensionFilter
import infoscry.server.ApiJson
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** What `infoscry import --json` reports when it did not wait for the outcome. */
@Serializable
data class ImportAccepted(
    val accepted: Boolean,
    val jobId: String,
    val state: String,
)

/** What `infoscry import --json --wait` reports once the import ended. */
@Serializable
data class ImportResult(
    val jobId: String,
    val state: String,
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val items: List<ImportItemResult>,
)

@Serializable
data class ImportItemResult(
    val id: String,
    val jobId: infoscry.domain.JobId,
    val documentId: infoscry.domain.DocumentId?,
    val sourcePath: String?,
    val sourceName: String?,
    val outcome: ImportItemOutcome,
    val errorCode: String?,
    val errorMessage: String?,
)

/**
 * `infoscry import` — ask the server to put files into a collection.
 *
 * The paths are paths on the server's file system. The command previews the import, confirms sending pages
 * to an external endpoint when the reading method does, and answers with the job the server accepted; with
 * `--wait` it follows the job and reports every document.
 */
class ImportCommand : CliktCommand(name = "import") {

    private val collection by option(
        "--collection",
        help = "Collection to import into, by name or id",
    ).required()

    private val sources by argument(
        name = "paths",
        help = "Files or directories on this machine to import; their files are uploaded to the server",
    ).multiple(required = true)

    private val methodOption by option("--method", help = "Reading method: none, tesseract, surya, or llm:<profileId>")
    private val imagesOption by option(
        "--images",
        help = "Image extraction: off, embedded or all; without it the collection default applies",
    )
    private val yesFlag by option("--yes", help = "Confirm sending the previewed pages to an external endpoint").flag()

    private val waitFlag by option(
        "--wait",
        help = "Wait for the import to finish and report every document",
    ).flag()

    private val recursiveFlag by option(
        "--recursive",
        help = "Recurse into subdirectories when importing a directory",
    ).flag()

    private val includeTypes by option(
        "--include",
        help = "Import only files with these extensions, comma-separated, e.g. --include=pdf,docx",
    ).split(",").default(emptyList())

    private val excludeTypes by option(
        "--exclude",
        help = "Import every file except those with these extensions, comma-separated, e.g. --exclude=tmp,log",
    ).split(",").default(emptyList())

    private val jsonFlag by option("--json", help = JSON_HELP).flag()


    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, null)
        // Refused before anything is sent, so a bad extension list has no effect at all.
        val extensions = extensionFilter()
        val images = imagesOption?.let { raw ->
            try {
                infoscry.image.ImageExtractionMode.parse(raw)
            } catch (_: IllegalArgumentException) {
                throw CliFailure("--images must be off, embedded or all, was '$raw'")
            }
        }
        val requested = canonicalSources(sources)
        try {
            importThroughServer(requested, extensions, options, images)
        } catch (invalid: IllegalArgumentException) {
            throw CliFailure(invalid.message.orEmpty(), invalid)
        }
    }

    /** The extension filter the flags name, or a refusal when both lists are given. */
    private fun extensionFilter(): ExtensionFilter {
        if (includeTypes.isNotEmpty() && excludeTypes.isNotEmpty()) {
            throw CliFailure("--include and --exclude cannot be used together; name the types to keep or the types to skip")
        }
        return try {
            ExtensionFilter.of(includeTypes, excludeTypes)
        } catch (invalid: IllegalArgumentException) {
            throw CliFailure(invalid.message.orEmpty(), invalid)
        }
    }

    /** Previews and enqueues the import on the server. */
    private fun importThroughServer(
        requested: List<String>,
        extensions: ExtensionFilter,
        options: CliOptions,
        images: infoscry.image.ImageExtractionMode?,
    ) {
        CliSession.connect().use { session ->
            val api = session.api
            val accepted = try {
                runBlocking {
                    val selected = api.listCollections().let { all ->
                        all.firstOrNull { it.id.value == collection } ?: all.firstOrNull { it.name.equals(collection, ignoreCase = true) }
                    } ?: throw CliFailure("no usable collection named or identified by '$collection'")
                    val method = methodOption?.let(infoscry.ocr.ReadingMethod::parse) ?: selected.ocrSettings().defaultMethod
                    // Keep current text carries a document's published text forward, so only a rescan has meaning for it.
                    if (method == infoscry.ocr.ReadingMethod.KeepText) {
                        throw CliFailure("--method keep is for rescans; an import has no published text to keep")
                    }
                    // Local files travel to the server first; a file whose bytes are already a document of the
                    // collection is not sent twice, and is reported as a duplicate.
                    val uploads = localFiles(requested).mapNotNull { file ->
                        val answer = api.upload(selected.id.value, file)
                        if (answer.existingDocumentId != null) {
                            alreadyImported += 1
                            echo("DUPLICATE  ${file.fileName}  -> document ${answer.existingDocumentId}", err = true)
                        }
                        answer.uploadId?.let { it to answer.sha256 }
                    }
                    if (uploads.isEmpty()) return@runBlocking null
                    val request = infoscry.server.ImportPreviewRouteRequest(collection = selected.id.value,
                        uploads = uploads.map { it.first },
                        include = extensions.include, exclude = extensions.exclude, method = method.id)
                    val preview = api.previewImport(request)
                    confirmPreview(preview, method.id)
                    val identity = uploads.map { it.second }
                    api.enqueueImport(infoscry.server.ImportRequest(collection = selected.id.value,
                        uploads = uploads.map { it.first },
                        include = extensions.include, exclude = extensions.exclude,
                        method = method.id, previewHash = preview.previewHash,
                        requestId = commandRequestId(identity, request, preview.previewHash, images), restartStopped = true,
                        imageExtraction = images?.id))
                }
            } catch (failure: RemoteApiFailure) {
                throw CliFailure("${failure.code}: ${failure.message}", failure)
            }
            if (accepted == null) {
                // Every file was already a document of the collection: there is nothing to import.
                if (options.json) {
                    echo(ApiJson.encodeToString(ImportResult("", "COMPLETE", 0, alreadyImported, 0, emptyList())))
                } else {
                    echo("Imported 0, duplicate $alreadyImported, failed 0 of $alreadyImported.")
                }
                return
            }
            if (!waitFlag) {
                reportAccepted(accepted.job.id, accepted.job.state, options)
                return
            }
            val finished = runBlocking { awaitTerminalJob(lookup = { api.getJob(accepted.job.id) }, what = "import") }
            val items = runBlocking { api.importItems(accepted.job.id) }
            report(finished, items, options)
        }
    }

    private fun confirmPreview(preview: infoscry.jobs.ImportPreview, method: String) {
        if (method == infoscry.ocr.ReadingMethod.None.id) {
            echo("No OCR: no page is read; each page keeps the document's own text.", err = true)
            return
        }
        val count = (if (preview.atLeast) "at least " else "") + preview.totalPages
        val action = if (preview.external) "Send" else "Read"
        echo("$action $count pages ${if (preview.external) "to" else "on"} ${preview.destination} with $method.", err = true)
        if (preview.external) {
            if (preview.atLeast) {
                echo("All pages in these files will be sent; the total cost is unavailable because their page count is unknown:", err = true)
                preview.files.forEach { file ->
                    echo("${file.path}: ${file.pages?.let { "$it pages" } ?: "page count unknown"}", err = true)
                }
            }
            preview.estimatedCostUsd?.let { echo("Estimated cost: USD $it.", err = true) }
            preview.costBasis?.let { echo(it, err = true) }
            if (!yesFlag) {
                echo("Send these pages? [y/N]", err = true)
                if (readlnOrNull()?.trim()?.lowercase() !in setOf("y", "yes")) {
                    throw CliFailure("Import cancelled before any job was queued.")
                }
            }
        }
    }

    /** One id per identical command: the same server paths or the same file contents, settings and preview. */
    private fun commandRequestId(identity: List<String>, request: infoscry.server.ImportPreviewRouteRequest, previewHash: String,
        images: infoscry.image.ImageExtractionMode?): String {
        val canonical = listOf(request.collection, request.method, recursiveFlag.toString()) +
            listOfNotNull(images?.let { "images=${it.id}" }) +
            identity.distinct().sorted() + request.include.sorted() + listOf("|") + request.exclude.sorted()
        val bytes = (canonical.joinToString("\n") + "\n" + previewHash).toByteArray(Charsets.UTF_8)
        return "cli-import-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun reportAccepted(jobId: JobId, state: JobState, options: CliOptions) {
        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ImportAccepted(
                        accepted = true,
                        jobId = jobId.value,
                        state = state.name,
                    ),
                ),
            )
            return
        }
        echo("Accepted job ${jobId.value} ($state).")
        echo("Use `infoscry jobs` or `infoscry import --wait` to watch it.", err = true)
    }

    /**
     * Prints one line per document and exits nonzero when any of them failed.
     *
     * The exit code is the contract a script reads, so a partial import must not look like a successful
     * one: the warnings are printed, and the command still fails.
     */
    private fun report(
        job: Job,
        items: List<ImportItem>,
        options: CliOptions,
    ) {
        val counts = items.groupingBy { it.outcome }.eachCount()
        val imported = counts[ImportItemOutcome.IMPORTED] ?: 0
        // Files found to be in the collection already while uploading never became items of the job.
        val duplicates = (counts[ImportItemOutcome.DUPLICATE] ?: 0) + alreadyImported
        val failed = counts[ImportItemOutcome.FAILED] ?: 0

        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ImportResult(
                        jobId = job.id.value,
                        state = job.state.name,
                        imported = imported,
                        duplicates = duplicates,
                        failed = failed,
                        items = items.map { item ->
                            ImportItemResult(
                                id = item.id,
                                jobId = item.jobId,
                                documentId = item.documentId,
                                sourcePath = item.sourcePath.takeIf(String::isNotEmpty),
                                sourceName = item.sourceName,
                                outcome = item.outcome,
                                errorCode = item.errorCode,
                                errorMessage = item.errorMessage,
                            )
                        },
                    ),
                ),
            )
        } else {
            items.forEach { item -> echo(describe(item)) }
            echo("Imported $imported, duplicate $duplicates, failed $failed of ${items.size + alreadyImported}.")
            if (job.state == JobState.FAILED) {
                val detail = ". Detailed failure text is omitted by the API; see the server log."
                echo("The job failed: ${job.errorCode ?: "JOB_FAILED"}$detail".trim(), err = true)
            }
        }

        if (job.state == JobState.CANCELLED) {
            throw CliFailure("the import was cancelled; ${items.size - failed} document(s) had been processed")
        }
        if (job.state == JobState.FAILED) {
            val detail = ". Detailed failure text is omitted by the API; see the server log."
            throw CliFailure(
                "the import failed: ${job.errorCode ?: "JOB_FAILED"}$detail".trim(),
            )
        }
        if (failed > 0) {
            throw CliFailure("$failed of ${items.size} document(s) could not be imported")
        }
        if (job.state != JobState.COMPLETE) {
            throw CliFailure("the import ended as ${job.state} without processing every document")
        }
    }

    private fun describe(item: ImportItem): String = buildString {
        append(item.outcome)
        append("  ")
        append(item.sourcePath.ifEmpty { item.sourceName ?: "selected source (path not reported)" })
        item.documentId?.let { document ->
            append("  -> document ")
            append(document.value)
        }
        item.errorCode?.let { code ->
            append("  ")
            append(code)
            item.errorMessage?.let { message -> append(": ").append(message) }
        }
    }

    /** Files already in the collection, counted while uploading. */
    private var alreadyImported = 0

    /**
     * The local files [paths] name: a file itself, and a folder's files (its whole tree with --recursive). Hidden
     * files and folders are left out, and links are not followed, as the server's own folder walk does.
     */
    private fun localFiles(paths: List<String>): List<Path> = paths.flatMap { raw ->
        val path = Path.of(raw)
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@flatMap listOf(path)
        Files.walk(path, if (recursiveFlag) Int.MAX_VALUE else 1).use { stream ->
            stream.filter { candidate ->
                Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    path.relativize(candidate).none { it.toString().startsWith(".") }
            }.sorted().toList()
        }
    }

    /** Fails before anything is queued when a named path does not exist: a typo is not a durable job. */
    private fun canonicalSources(paths: List<String>): List<String> {
        require(paths.isNotEmpty()) { "an import needs at least one path" }
        return paths.map { raw ->
            val given = Path.of(raw)
            if (!Files.exists(given)) throw CliFailure("no such file or directory: $raw")
            given.toAbsolutePath().normalize().toString()
        }
    }



}
