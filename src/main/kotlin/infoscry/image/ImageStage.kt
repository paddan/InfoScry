package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.extract.UnitBoundary
import infoscry.image.analysis.AnalysisRules
import infoscry.image.analysis.ImageAnalysisStep
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageStructure
import infoscry.storage.ContentAddressedFile
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentStore
import infoscry.storage.ImageCheckpoint
import infoscry.storage.ImageCheckpointOutcome
import infoscry.storage.ImageStore
import infoscry.storage.StoredImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.EnumMap
import java.util.HexFormat
import javax.imageio.ImageIO

/** The version of the rules that decide which images are kept; a checkpoint of another version is looked at again. */
const val IMAGE_EXTRACTOR_VERSION: String = "2"

/**
 * A parent unit the text stage emitted, with what the image stage needs from it.
 *
 * @param text the parent's extracted text, which a known [structure] was derived into.
 * @param page renders the parent's page raster; called only when a region pass is needed, and returns null when the
 *   parent has no page.
 */
data class ParentUnitContext(
    val parent: ParentUnit,
    val text: String,
    val structure: PageStructure?,
    val page: suspend () -> PageImage?,
)

/**
 * What a stage run holds for the whole document, whether this run or an earlier one committed it.
 *
 * @param warnings distinct codes only, never captions or text.
 */
data class ImageStageResult(val images: Int, val skipped: Map<SkipReason, Int>, val warnings: List<String>) {
    companion object {
        val NONE = ImageStageResult(0, emptyMap(), emptyList())
    }
}

/**
 * Extracts the images of a document one parent unit at a time, each parent with its own checkpoint.
 *
 * Per parent the artifact is written, its metadata recorded, the unit delivered to the target and the checkpoint
 * committed, all in one unit boundary. Unit IDs, ordinals and artifact names depend only on the document, so a run
 * interrupted anywhere converges on the same rows when it runs again, and a parent with a committed checkpoint is
 * not looked at again.
 *
 * Repetition across pages is decided in a first pass that hashes each parent's embedded images and keeps only the
 * hashes found on too many pages, so the bytes of the whole document are never held at once; the second pass asks
 * the finder again (it is deterministic). A resumed run hashes the already committed parents again whenever another
 * parent is still to do, so a logo is judged by the same count however often the run was interrupted. The first pass
 * is skipped when the document has no more parents than an image may repeat on.
 *
 * The per-document cap is an invariant: a parent may deliver only what is left after every committed parent and
 * the images delivered earlier in this run, so which images survive near the cap can depend on which parent was
 * retried (only for a document over the cap).
 *
 * A parent whose regions could not be located because Surya is unavailable commits a `FAILED` checkpoint after its
 * embedded images, so the next run tries it again.
 */
class ImageStage(
    private val finders: ImageFinders,
    private val regions: LayoutRegions,
    private val artifacts: ImageArtifacts,
    private val store: ImageStore,
    private val documents: DocumentStore,
    /** How each picture is described and tagged, or null when this build analyses no pictures. */
    private val analysis: ImageAnalysisStep? = null,
) {

    /**
     * Forgets what any mode checkpointed for [target], after an attempt with images off removed the image units those
     * checkpoints described. Without it a later run of the earlier mode would find its checkpoints, skip every parent
     * and never deliver the removed units again.
     */
    fun clearCheckpoints(documentId: DocumentId, target: String) = store.clearCheckpoints(documentId, target)

    suspend fun run(
        document: Document,
        managedPath: Path,
        artifactRoot: Path,
        parents: List<ParentUnitContext>,
        mode: ImageExtractionMode,
        target: ImageUnitTarget,
        boundary: UnitBoundary,
        settings: OcrSettingsSnapshot,
        /** The run's permit authority for picture analysis, when the snapshot asked for an external one. */
        dispatch: OcrDispatchAuthority? = null,
    ): ImageStageResult {
        if (mode == ImageExtractionMode.OFF || parents.isEmpty()) return ImageStageResult.NONE
        val committed = store.checkpoints(document.id, target.target, mode, IMAGE_EXTRACTOR_VERSION)
            .filterValues { it.outcome == ImageCheckpointOutcome.DONE }
        val summary = Summary()
        // Committed parents contribute their stored counts, so a rerun reports the whole document.
        parents.mapNotNull { committed[it.parent.unitId] }.forEach(summary::addCommitted)
        if (parents.all { it.parent.unitId in committed }) return summary.result()

        val finder = finders.forMediaType(document.mediaType) ?: return summary.result()
        val source = try {
            finder.open(managedPath)
        } catch (unreadable: IOException) {
            summary.warn(SOURCE_UNREADABLE)
            return summary.result()
        } catch (unreadable: RuntimeException) {
            summary.warn(SOURCE_UNREADABLE)
            return summary.result()
        }
        source.use {
            val repeated = repeatedHashes(finder, managedPath, parents)
            // Every committed parent counts, before or after the current one, so the document never exceeds its cap.
            var total = parents.sumOf { committed[it.parent.unitId]?.imageCount ?: 0 }
            var regionsAvailable = true
            for (context in parents) {
                if (context.parent.unitId in committed) continue
                if (documents.isDeletionTarget(document.id)) throw DocumentBeingDeletedException(document.id)
                val run = ParentRun(document, managedPath, artifactRoot, context, mode, target, boundary, settings, dispatch)
                val outcome = extractParent(run, source, repeated, total, regionsAvailable, summary)
                total += outcome.delivered
                if (outcome.regionsUnavailable) regionsAvailable = false
            }
        }
        return summary.result()
    }

    /**
     * The hashes of embedded images that appear on more pages than [ImageFilter.MAX_REPEAT_PAGES] allows.
     *
     * The pass opens its own source: a format that bounds what one open document may read (an archive's cumulative
     * budget) is charged once per pass, not once for both.
     */
    private fun repeatedHashes(finder: ImageFinder, managedPath: Path, parents: List<ParentUnitContext>): Set<String> {
        if (parents.size <= ImageFilter.MAX_REPEAT_PAGES) return emptySet()
        val source = try {
            finder.open(managedPath)
        } catch (unreadable: IOException) {
            return emptySet()
        } catch (unreadable: RuntimeException) {
            return emptySet()
        }
        val pages = HashMap<String, Int>()
        source.use {
            for (context in parents) {
                val found = try {
                    source.imagesOf(context.parent, withPageText = false)
                } catch (unreadable: RuntimeException) {
                    // The second pass reports the failure; here the parent just adds no hashes.
                    continue
                }
                // A page counts once however many times it draws the image.
                found.images.filter { it.kind == ImageSourceKind.EMBEDDED }
                    .map { contentHash(it.candidate) }
                    .toSet()
                    .forEach { pages.merge(it, 1, Int::plus) }
            }
        }
        return pages.filterValues { it > ImageFilter.MAX_REPEAT_PAGES }.keys
    }

    private class ParentRun(
        val document: Document,
        val managedPath: Path,
        val artifactRoot: Path,
        val context: ParentUnitContext,
        val mode: ImageExtractionMode,
        val target: ImageUnitTarget,
        val boundary: UnitBoundary,
        val settings: OcrSettingsSnapshot,
        val dispatch: OcrDispatchAuthority?,
    )

    private class ParentOutcome(val delivered: Int, val regionsUnavailable: Boolean)

    private suspend fun extractParent(
        run: ParentRun,
        source: OpenedSource,
        repeated: Set<String>,
        totalBefore: Int,
        regionsAvailable: Boolean,
        summary: Summary,
    ): ParentOutcome {
        val parent = run.context.parent
        val counts = Counts()

        if (parent.ordinal > ImageIdentity.MAX_PARENT_ORDINAL) {
            counts.warn(PARENT_TOO_LATE)
            run.boundary.unit {
                checkNotDeleting(run)
                commit(run, counts, ImageCheckpointOutcome.DONE, 0)
            }
            summary.addRun(counts, 0)
            return ParentOutcome(0, false)
        }

        val found = try {
            source.imagesOf(parent)
        } catch (unreadable: RuntimeException) {
            ParentImages(emptyList(), listOf(ImageFindFailure.PARENT_UNREADABLE), scanned = false)
        }
        var images = found.images
        repeat(found.vectorSkipped) { counts.skip(SkipReason.VECTOR) }
        var failed = false
        found.failures.forEach { code ->
            counts.warn(code)
            if (code == ImageFindFailure.PARENT_UNREADABLE) failed = true
        }

        var regionsUnavailable = false
        if (run.mode == ImageExtractionMode.ALL && found.scanned) {
            if (!regionsAvailable) {
                regionsUnavailable = true
            } else {
                try {
                    val page = run.context.page()
                    if (page != null) {
                        val structure = run.context.structure
                        val pageText = if (structure != null) run.context.text else null
                        val located = regions.regionsOf(parent, page, structure, pageText, run.settings)
                        images = images + located.images
                        located.failures.forEach(counts::warn)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (deleted: DocumentBeingDeletedException) {
                    throw deleted
                } catch (unavailable: SuryaLayoutUnavailableException) {
                    regionsUnavailable = true
                } catch (unrenderable: IOException) {
                    // One page that cannot be rendered or read must not stop the rest of the document: this parent
                    // keeps its embedded images, records a failed checkpoint, and is tried again next run.
                    counts.warn(ImageFindFailure.PARENT_UNREADABLE)
                    failed = true
                } catch (unrenderable: RuntimeException) {
                    counts.warn(ImageFindFailure.PARENT_UNREADABLE)
                    failed = true
                }
            }
            if (regionsUnavailable) {
                counts.warn(SuryaLayoutUnavailableException.NEEDS_SURYA_LAYOUT)
                summary.warn(SuryaLayoutUnavailableException.NEEDS_SURYA_LAYOUT)
            }
        }

        val outcome = if (failed || regionsUnavailable) ImageCheckpointOutcome.FAILED else ImageCheckpointOutcome.DONE
        val delivered = run.boundary.unit { deliverParent(run, images, repeated, totalBefore, counts, outcome, failed || regionsUnavailable) }
        summary.addRun(counts, delivered)
        return ParentOutcome(delivered, regionsUnavailable)
    }

    private suspend fun deliverParent(
        run: ParentRun,
        images: List<FoundImage>,
        repeated: Set<String>,
        totalBefore: Int,
        counts: Counts,
        outcome: ImageCheckpointOutcome,
        unreadable: Boolean,
    ): Int {
        checkNotDeleting(run)
        val parent = run.context.parent
        // Analysing a picture is a model call: it must not hold the unit boundary, which is this process's
        // mutation permit, for as long as a provider takes to answer. Artifacts are content-addressed and
        // written idempotently, so what is prepared here is safe to prepare again after an interruption.
        val prepared = prepare(run, images, repeated, totalBefore, counts)
        return run.boundary.unit {
            checkNotDeleting(run)
            // Invalidate first: a checkpoint of another mode that outlives a kill between the changes below would
            // make a later run of that mode skip a parent whose units this run changed. A missing checkpoint only
            // costs a repeat.
            store.clearOtherModeCheckpoints(run.document.id, run.target.target, parent.unitId, run.mode)
            val produced = HashSet<ContentUnitId>()
            for (image in prepared) {
                store.record(image.record)
                run.target.deliver(image.ordinal, image.unitId, image.locator, image.text, image.written, image.root)
                produced += image.unitId
            }
            // What an earlier run (another mode, or more images) left in this parent's band and this one did not
            // produce is stale. A parent that could not be read at all says nothing about what it holds, so it keeps
            // its units.
            if (!unreadable && parent.ordinal <= ImageIdentity.MAX_PARENT_ORDINAL) {
                val first = checkNotNull(ImageIdentity.ordinal(parent.ordinal, 1))
                val last = checkNotNull(ImageIdentity.ordinal(parent.ordinal, ImageIdentity.MAX_PER_PARENT))
                run.target.retainImages(first, last, produced)
            }
            commit(run, counts, outcome, prepared.size)
            prepared.size
        }
    }

    /** One deliverable picture, decided and written but not yet recorded or delivered. */
    private class PreparedImage(
        val ordinal: Int,
        val unitId: ContentUnitId,
        val locator: SourceLocation.ImageRegion,
        val text: String,
        val written: WrittenImage,
        val root: SourceImageRoot,
        val record: StoredImage,
    )

    /**
     * Filters a parent's found images, writes the kept ones' artifacts and analyses them, outside the unit
     * boundary.
     *
     * The filtering and counting are the stage's own rules, unchanged: the document-wide cap, the per-parent cap,
     * size, repetition and uniformity. What is new is that each kept picture's text is the analysis of it — or,
     * when no analysis could be made, its caption and page text — computed here because it may be a model call.
     */
    private suspend fun prepare(
        run: ParentRun,
        images: List<FoundImage>,
        repeated: Set<String>,
        totalBefore: Int,
        counts: Counts,
    ): List<PreparedImage> {
        val parent = run.context.parent
        val occurrences = HashMap<String, Int>()
        val prepared = ArrayList<PreparedImage>()
        for (found in images) {
            // Equal images in one parent are told apart in finder order, whether or not each is kept.
            val key = identityKey(found)
            val occurrence = occurrences.merge(key, 1, Int::plus)!!
            val candidate = found.candidate

            // An imported picture is the document itself: the person chose it, so size and colour never drop it.
            val exempt = found.kind == ImageSourceKind.STANDALONE
            val early = (if (exempt) null else ImageFilter.skipReason(candidate, null))
                ?: SkipReason.REPEATED.takeIf {
                    found.kind == ImageSourceKind.EMBEDDED && contentHash(candidate) in repeated
                }
            if (early != null) {
                counts.skip(early)
                continue
            }
            if (totalBefore + prepared.size >= ImageFilter.MAX_PER_DOCUMENT) {
                counts.skip(SkipReason.LIMIT)
                counts.warn(LIMIT_REACHED)
                continue
            }
            if (prepared.size >= ImageIdentity.MAX_PER_PARENT) {
                counts.warn(ImageFindFailure.TOO_MANY_IMAGES)
                continue
            }
            if (!exempt) {
                val decoded = decodeForUniformity(candidate)
                if (decoded == null) {
                    counts.warn(ImageRejectedException.IMAGE_DECODE_FAILED)
                    continue
                }
                if (ImageFilter.skipReason(candidate, decoded) != null) {
                    counts.skip(SkipReason.UNIFORM)
                    continue
                }
            }

            val index = prepared.size + 1
            val ordinal = checkNotNull(ImageIdentity.ordinal(parent.ordinal, index)) {
                "a parent ordinal beyond the bound was admitted"
            }
            val standalone = found.kind == ImageSourceKind.STANDALONE
            val region = found.kind == ImageSourceKind.PAGE_REGION
            val sha = if (standalone) run.document.sha256 else contentHash(candidate)
            val unitId = ImageIdentity.unitId(
                run.document.id, parent.unitId, found.kind,
                sha256 = sha.takeIf { !region },
                box = candidate.box.takeIf { region },
                occurrence = occurrence,
            )
            val written: WrittenImage
            val root: SourceImageRoot
            try {
                if (standalone) {
                    // The managed copy is the artifact: only its thumbnail is written.
                    written = WrittenImage(
                        sha256 = run.document.sha256,
                        relativePath = run.managedPath.fileName.toString(),
                        thumbnailRelativePath =
                            artifacts.thumbnailFor(
                                run.artifactRoot, run.managedPath, run.document.sha256,
                                displayCopy = ImageArtifacts.needsDisplayCopy(run.document.mediaType),
                            ),
                        mediaType = run.document.mediaType,
                        widthPx = candidate.widthPx,
                        heightPx = candidate.heightPx,
                    )
                    root = SourceImageRoot.MANAGED_COPY
                } else {
                    written = artifacts.write(run.artifactRoot, candidate.bytes)
                    root = SourceImageRoot.ARTIFACTS
                }
            } catch (rejected: ImageRejectedException) {
                counts.warn(rejected.code)
                continue
            }
            val locator = SourceLocation.ImageRegion(parent.locator, index, candidate.box)
            prepared += PreparedImage(
                ordinal = ordinal,
                unitId = unitId,
                locator = locator,
                text = textOf(run, unitId, written, root, found, counts),
                written = written,
                root = root,
                record = StoredImage(
                    documentId = run.document.id,
                    unitId = unitId,
                    sha256 = written.sha256,
                    parentUnitId = parent.unitId,
                    kind = found.kind,
                    relativePath = written.relativePath,
                    thumbnailRelativePath = written.thumbnailRelativePath,
                    mediaType = written.mediaType,
                    widthPx = written.widthPx,
                    heightPx = written.heightPx,
                    box = candidate.box,
                    caption = found.caption,
                    captionSource = found.captionSource,
                ),
            )
        }
        return prepared
    }

    /**
     * The text one picture is delivered with: its analysis when this build can make one, else its caption and
     * page context.
     *
     * A picture that cannot be analysed is not a failed document: its caption and context are still its text,
     * and the stage reports the code as a warning. A run that ran past its confirmed external allowance is the one
     * exception — that is the run's own bound, and it ends the run rather than being recorded per picture.
     */
    private suspend fun textOf(
        run: ParentRun,
        unitId: ContentUnitId,
        written: WrittenImage,
        root: SourceImageRoot,
        found: FoundImage,
        counts: Counts,
    ): String {
        val step = analysis ?: return unitTextOf(found.caption, found.context)
        val path = when (root) {
            SourceImageRoot.MANAGED_COPY -> run.managedPath
            SourceImageRoot.ARTIFACTS -> run.artifactRoot.resolve(written.relativePath)
        }
        return try {
            val result = step.textFor(
                document = run.document,
                unitId = unitId,
                image = written,
                imagePath = path,
                caption = found.caption,
                context = found.context,
                choice = run.settings.imageAnalysis,
                language = run.settings.language,
                dispatch = run.dispatch,
            )
            result.warning?.let(counts::warn)
            result.text
        } catch (exceeded: infoscry.ocr.ExternalPageAllowanceExceededException) {
            throw exceeded
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (deleted: DocumentBeingDeletedException) {
            throw deleted
        } catch (_: Exception) {
            counts.warn(AnalysisRules.INVALID)
            unitTextOf(found.caption, found.context)
        }
    }

    /** The unit's indexed text: the caption, a blank line, then the page-text context; either part may be absent. */
    private fun unitTextOf(caption: String?, context: String?): String =
        listOfNotNull(caption?.takeIf { it.isNotBlank() }, context?.takeIf { it.isNotBlank() }).joinToString("\n\n")

    private fun checkNotDeleting(run: ParentRun) {
        if (documents.isDeletionTarget(run.document.id)) throw DocumentBeingDeletedException(run.document.id)
    }

    private fun commit(run: ParentRun, counts: Counts, outcome: ImageCheckpointOutcome, delivered: Int) {
        store.commitCheckpoint(
            run.document.id, run.target.target, run.mode, IMAGE_EXTRACTOR_VERSION,
            ImageCheckpoint(run.context.parent.unitId, outcome, delivered, counts.encode()),
        )
    }

    /** The pixels to judge uniformity by, bounded like a rendered page, or null when the image cannot be decoded. */
    private fun decodeForUniformity(candidate: ImageCandidate): BufferedImage? = when (val bytes = candidate.bytes) {
        is ImageBytes.Raster -> bytes.image
        is ImageBytes.Encoded -> try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes.bytes))?.use { stream ->
                val reader = ImageIO.getImageReaders(stream).next()
                try {
                    reader.input = stream
                    val parameter = reader.defaultReadParam
                    val step = ImageProbe.subsampleStep(reader.getWidth(0), reader.getHeight(0))
                    if (step > 1) parameter.setSourceSubsampling(step, step, 0, 0)
                    reader.read(0, parameter)
                } finally {
                    reader.dispose()
                }
            }
        } catch (unreadable: IOException) {
            null
        } catch (unreadable: RuntimeException) {
            null
        }
    }

    /** What tells equal images in one parent apart from different ones, in the terms identity uses. */
    private fun identityKey(found: FoundImage): String = when (found.kind) {
        ImageSourceKind.PAGE_REGION -> {
            val box = found.candidate.box
            "box:" + box?.let { listOf(it.left, it.top, it.right, it.bottom).joinToString(",") { v -> "${round3(v)}" } }
        }
        else -> "sha:" + contentHash(found.candidate)
    }

    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    /** SHA-256 of what the candidate carries: its encoded bytes, or its pixels. Stable across passes. */
    private fun contentHash(candidate: ImageCandidate): String = when (val bytes = candidate.bytes) {
        is ImageBytes.Encoded -> ContentAddressedFile.sha256Of(bytes.bytes)
        is ImageBytes.Raster -> {
            val image = bytes.image
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("${image.width}x${image.height}".toByteArray())
            val row = IntArray(image.width)
            val buffer = ByteBuffer.allocate(image.width * 4)
            for (y in 0 until image.height) {
                image.getRGB(0, y, image.width, 1, row, 0, image.width)
                buffer.clear()
                buffer.asIntBuffer().put(row)
                digest.update(buffer.array())
            }
            HexFormat.of().formatHex(digest.digest())
        }
    }

    /** Skip and warning counts of one parent, kept in its checkpoint as `CODE:count` pairs. */
    private class Counts {
        val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)
        val warnings = LinkedHashMap<String, Int>()

        fun skip(reason: SkipReason) {
            skipped.merge(reason, 1, Int::plus)
        }

        fun warn(code: String) {
            warnings.merge(code, 1, Int::plus)
        }

        fun encode(): String? {
            val parts = skipped.map { "${it.key.name}:${it.value}" } + warnings.map { "${it.key}:${it.value}" }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }

    private class Summary {
        var images = 0
        val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)
        val warnings = LinkedHashSet<String>()

        fun warn(code: String) {
            warnings += code
        }

        fun addRun(counts: Counts, delivered: Int) {
            images += delivered
            counts.skipped.forEach { (reason, n) -> skipped.merge(reason, n, Int::plus) }
            warnings += counts.warnings.keys
        }

        fun addCommitted(checkpoint: ImageCheckpoint) {
            images += checkpoint.imageCount
            checkpoint.warning?.split(',')?.forEach { part ->
                val code = part.substringBefore(':')
                val count = part.substringAfter(':', "1").toIntOrNull() ?: 1
                val reason = SkipReason.entries.firstOrNull { it.name == code }
                if (reason != null) skipped.merge(reason, count, Int::plus) else if (code.isNotEmpty()) warnings += code
            }
        }

        fun result(): ImageStageResult = ImageStageResult(images, skipped.toMap(), warnings.toList())
    }

    companion object {
        /** The document holds more images than [ImageFilter.MAX_PER_DOCUMENT]; the rest are not delivered. */
        const val LIMIT_REACHED: String = "IMAGE_LIMIT_REACHED"

        /** A parent too late in the reading order for its images to have ordinals. */
        const val PARENT_TOO_LATE: String = "IMAGE_PARENT_ORDINAL_TOO_LATE"

        /** The document could not be opened to look for images. */
        const val SOURCE_UNREADABLE: String = "IMAGE_SOURCE_UNREADABLE"
    }
}
