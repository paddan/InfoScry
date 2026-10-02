package infoscry.ocr

import infoscry.domain.DocumentId
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.extract.OcrUnavailableException
import infoscry.extract.PdfExtractor
import infoscry.extract.TesseractOcr
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/**
 * One page of one document, as an image an engine can read.
 *
 * Two roots, because a page image is not always something this pipeline produced. A rendered PDF page
 * lives in the attempt directory that rendered it, while an imported picture *is* the managed copy and
 * lives in the document's managed area. [imageRoot] is the directory [imageReference] resolves against, so
 * a reference can only ever name a file inside the root its producer chose — the check is what keeps a
 * filesystem path from being an argument a caller could hand in — and [artifactRoot] is the one directory
 * the attempt's own readings may be written into, inside the document's artifact root.
 *
 * The path is *derived* rather than carried: what a caller may hand to a response is the relative
 * reference, the hash and the dimensions, and the path itself stays inside this process.
 *
 * [renderDpi] is absent when this pipeline chose no resolution for the image — an imported picture is the
 * document, not a rendering of one — and [rotationDegrees] is the rotation the page declares, which is
 * what a reviewer has to know to look at the image the way a reader does. [width] and [height] are absent
 * when the artifact could not be measured, which is not a claim of zero by zero, exactly as an absent
 * confidence is not a zero confidence.
 */
data class PageImage(
    val documentId: DocumentId,
    /** The page's stable identity inside its document: its extraction key, not a per-attempt identifier. */
    val unitId: String,
    val ordinal: Int,
    val imageRoot: Path,
    val imageReference: String,
    val artifactRoot: Path,
    val sha256: String,
    val width: Int?,
    val height: Int?,
    val renderDpi: Int?,
    val rotationDegrees: Int,
    val renderVersion: Int = RENDER_VERSION,
) {

    init {
        require(unitId.isNotBlank()) { "a page image needs the page's stable unit id" }
        require(ordinal >= 0) { "a page image needs the page's ordinal, was $ordinal" }
        require(sha256.length == SHA256_HEX_LENGTH && sha256.all { character -> character.isHexCharacter() }) {
            "a page image needs the artifact's SHA-256, was '${sha256.take(MAX_REPORTED_HASH_CHARACTERS)}'"
        }
        require((width == null) == (height == null)) { "a page image either has both dimensions or neither" }
        require(width == null || width > 0) { "a measured page image has a positive width, was $width" }
        require(height == null || height > 0) { "a measured page image has a positive height, was $height" }
        require(renderDpi == null || renderDpi > 0) {
            "a rendered page image has a positive resolution, was $renderDpi"
        }
        require(rotationDegrees in 0..MAX_ROTATION_DEGREES) {
            "a page's rotation is a quarter-turn count, was $rotationDegrees"
        }
        require(renderVersion > 0) { "a page image names the rendering that produced it, was $renderVersion" }
        // Validating here as well as in the factory is what makes the rule a property of the record: a
        // caller that builds one directly cannot make it name a file outside its own root either.
        resolveInside(imageRoot, imageReference)
    }

    /** Where the artifact actually is. Derived on purpose: no caller stores a raw path in a value. */
    val imagePath: Path get() = resolveInside(imageRoot, imageReference)

    /**
     * How a durable record names this page image: the artifact reference of the pixels a reading was made
     * from, relative to the *document's* artifact root [documentArtifactRoot].
     *
     * The reference a record keeps may not be one that only resolves while this attempt's directory still
     * exists, and it may not be an absolute path, so it is the attempt-relative reference this image already
     * has joined with the attempt's own place under the document's artifacts — the same form, and the same
     * root, as the word boxes of the reading it belongs to. The file has to be inside that root, which is
     * what makes the stored reference resolvable by whoever reads the record back.
     */
    fun artifactProvenance(documentArtifactRoot: Path): SourceImageProvenance {
        val root = documentArtifactRoot.normalize()
        require(imagePath.startsWith(root)) {
            "a page image sits under the artifact root its provenance is named against"
        }
        return SourceImageProvenance(
            root = SourceImageRoot.ARTIFACTS,
            relativePath = root.relativize(imagePath).toString(),
            sha256 = sha256,
            width = width,
            height = height,
            renderVersion = renderVersion,
        )
    }

    /**
     * How a durable record names a picture that *is* the document: the managed copy, read as itself.
     *
     * Such an image is not something this pipeline wrote, so its reference resolves against the directory
     * holding the managed copy rather than against the attempt's artifacts — and the copy is immutable, so
     * the reference stays valid for the life of the document. The hash and dimensions are still the
     * artifact's own, measured when it was read.
     */
    fun managedCopyProvenance(): SourceImageProvenance = SourceImageProvenance(
        root = SourceImageRoot.MANAGED_COPY,
        relativePath = imageReference,
        sha256 = sha256,
        width = width,
        height = height,
        renderVersion = renderVersion,
    )

    /**
     * Whether the artifact is still the image this record was made from.
     *
     * An image is used again — by a reviewer, by a comparison — only after this says so: a page image that
     * was replaced, truncated or removed is not the image the recorded hash describes, and a reading may
     * not be attributed to it.
     */
    fun isIntact(): Boolean = try {
        sha256Of(imagePath) == sha256
    } catch (unreadable: IOException) {
        false
    }

    /**
     * Whether this page's artifact is blank paper.
     *
     * This is the only thing that may call a page blank: an engine that returned no text may have read
     * blank paper or may have missed what is on it, and only the raster can tell the two apart. An artifact
     * that cannot be read back is not called blank, because the question is whether the page's image
     * arrived rather than whether this process can re-open its own file — and an artifact whose *header*
     * declares a raster past [MAX_BLANK_SCAN_PIXELS] is answered the same way, without being decoded.
     *
     * *Every* picture the artifact holds has to be white paper. An artifact can hold more than one — a
     * multi-page TIFF is a single unit — and a scan that asked about frame 0 alone would call a container
     * whose *second* picture carries ink blank paper, which is the wrong way round in a way nothing
     * downstream can detect. Anything this process cannot establish is answered "not blank" rather than
     * guessed: an artifact no reader claims, a frame count no reader will state, and frames whose declared
     * raster together is past [MAX_BLANK_SCAN_PIXELS].
     *
     * The conservative direction is the safe one because the two mistakes are not symmetric. A page wrongly
     * called blank is committed as a picture with no text, so ink that is really there is silently dropped
     * and nothing tells a person to look at it. A page wrongly *not* called blank only keeps the empty
     * reading the engine gave — visible, citable, and fixable by a rescan or by a person opening the
     * picture — which is the answer that loses nothing.
     */
    fun isBlankPaper(): Boolean {
        val stream = try {
            ImageIO.createImageInputStream(imagePath.toFile())
        } catch (failure: IOException) {
            return false
        } ?: return false
        stream.use {
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return false
            val reader = readers.next()
            try {
                reader.input = stream
                return reader.everyFrameIsWhitePaper()
            } catch (failure: IOException) {
                return false
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * Whether every frame this reader can account for is white paper.
     *
     * The declared dimensions are read from each frame's header before any raster is allocated, because the
     * declared size is a number a small, highly compressed file chooses: decoding first and asking
     * afterwards is how one page image exhausts the process. The bound is spent across the artifact's
     * frames rather than once per frame, because the frames of one artifact are the one page image this
     * bound is about — an artifact holding a thousand pictures must not cost a thousand decodes — and the
     * frames are decoded one at a time and dropped as the walk goes on, so what is held is one raster.
     */
    private fun ImageReader.everyFrameIsWhitePaper(): Boolean {
        val frames = try {
            getNumImages(true)
        } catch (unsupported: UnsupportedOperationException) {
            // A reader that will not say how many pictures the artifact holds cannot be asked whether all
            // of them are white paper, and "the one I looked at was white" is not that answer.
            return false
        }
        if (frames < 1) return false
        var declared = 0L
        for (frame in 0 until frames) {
            declared += getWidth(frame).toLong() * getHeight(frame).toLong()
            if (declared > MAX_BLANK_SCAN_PIXELS) return false
            val raster = read(frame)
            val row = IntArray(raster.width)
            for (y in 0 until raster.height) {
                raster.getRGB(0, y, raster.width, 1, row, 0, raster.width)
                if (row.any { pixel -> pixel and WHITE_PIXEL != WHITE_PIXEL }) return false
            }
        }
        return true
    }

    companion object {

        /**
         * The version of the rendering this seam produces.
         *
         * It travels with every page image because a rendering procedure is part of what an image is: a
         * later renderer, or another raster for the same page, produces a page image an old reading was not
         * made from.
         */
        const val RENDER_VERSION: Int = 1

        /**
         * The version of a page image that is a *bounded copy* of the artifact it was made from.
         *
         * A picture whose declared raster is past the bound a tool is handed is decoded with integer source
         * subsampling and read from that copy, so the pixels a reading was made from are not the pixels of
         * the managed original. The record's dimensions and hash describe the copy — what was actually read
         * — and this version says there is an original it was reduced from: a later comparison, or a person
         * shown the image, can then tell a reading of a reduced picture from a reading of the whole one
         * instead of attributing the first to the second.
         */
        const val REDUCED_RENDER_VERSION: Int = 2

        /**
         * The version of the rule that decides which pixels of a picture a reading is made from.
         *
         * It is a version of its own because it is not a version of anything in a picture or a setting: the
         * bound a raster is held to, and what a picture past that bound is reduced by, decide whether a tool
         * is handed the managed original or a copy of it — and two attempts that would decode the same
         * picture differently have not read the same image. An OCR attempt carries this in its identity, so
         * a reading under one policy is not reused under another; an extraction that runs no OCR attempt
         * carries the picture reading in the extractor's own schema, so a policy change is published by
         * raising `EXTRACTOR_SCHEMA_VERSION` together with it.
         */
        const val PICTURE_DECODING_POLICY_VERSION: Int = 1

        /**
         * A page image over an image file that already exists, with its hash and dimensions read from it.
         *
         * Measuring the artifact is what makes the record a statement about a *file* rather than about what
         * a caller believed it wrote, and it is the one step a producer cannot skip. A producer that names
         * something no reader can hash is not producing a page image at all, and this says so by failing.
         */
        fun ofFile(
            documentId: DocumentId,
            unitId: String,
            ordinal: Int,
            imageRoot: Path,
            imageReference: String,
            artifactRoot: Path,
            renderDpi: Int?,
            rotationDegrees: Int,
            renderVersion: Int = RENDER_VERSION,
        ): PageImage {
            val path = resolveInside(imageRoot, imageReference)
            val size = imageSize(path)
            return PageImage(
                documentId = documentId,
                unitId = unitId,
                ordinal = ordinal,
                imageRoot = imageRoot,
                imageReference = imageReference,
                artifactRoot = artifactRoot,
                sha256 = sha256Of(path),
                width = size?.first,
                height = size?.second,
                renderDpi = renderDpi,
                rotationDegrees = rotationDegrees,
                renderVersion = renderVersion,
            )
        }

        /** The pixel dimensions an image file declares, or `null` when it is not one this build reads. */
        private fun imageSize(path: Path): Pair<Int, Int>? = try {
            ImageIO.createImageInputStream(path.toFile())?.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) {
                    null
                } else {
                    val reader = readers.next()
                    try {
                        reader.input = stream
                        // The header is enough: a producer that measured its own raster by decoding it
                        // again would pay for the whole page to learn two numbers.
                        reader.getWidth(0) to reader.getHeight(0)
                    } finally {
                        reader.dispose()
                    }
                }
            }
        } catch (unreadable: IOException) {
            null
        }

        private fun sha256Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // Streamed rather than read whole: a managed copy may be hundreds of megabytes, and a hash
            // that has to fit in memory is a hash that can exhaust it.
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        /**
         * The path a reference names, refused unless it stays inside the root it was produced under.
         *
         * A relative reference that climbs out of its root, an absolute one, and one that names the root
         * itself are all refused: the point of the pair is that the root decides where an image may be, so
         * nothing here may widen it.
         */
        private fun resolveInside(root: Path, reference: String): Path {
            require(reference.isNotBlank()) { "a page image reference must not be blank" }
            val relative = Path.of(reference)
            require(!relative.isAbsolute) {
                "a page image reference is relative to the root it was produced under, was '$reference'"
            }
            val resolved = root.resolve(relative).normalize()
            require(resolved != root.normalize() && resolved.startsWith(root.normalize())) {
                "a page image reference may not name a file outside the root it was produced under, " +
                    "was '$reference'"
            }
            return resolved
        }

        private const val SHA256_HEX_LENGTH: Int = 64

        /** How much of an artifact is read at a time while it is hashed. */
        private const val HASH_BUFFER_BYTES: Int = 64 * 1024

        /** Every channel of the white a rendered page's paper has. */
        private const val WHITE_PIXEL: Int = 0xFFFFFF

        /**
         * The most pixels a raster this process decodes to look for paper may hold.
         *
         * Decoding allocates the whole image, so the bound is what keeps a file whose *header* declares an
         * enormous raster from allocating it: a page past this is answered "not verified blank" instead of
         * being decoded. It is the same bound a rendered page is held to, because the two bound the same
         * thing — one page's raster — and a page over it is refused rather than materialised.
         */
        private const val MAX_BLANK_SCAN_PIXELS: Long = PdfExtractor.MAX_RENDERED_PIXELS

        /** Hex as `HexFormat` writes it — the only form a digest in this pipeline has. */
        private fun Char.isHexCharacter(): Boolean = this in '0'..'9' || this in 'a'..'f'
        private const val MAX_REPORTED_HASH_CHARACTERS: Int = 16
        private const val MAX_ROTATION_DEGREES: Int = 359
    }
}

/** One word of a page's reading, with the box it was read from. */
data class OcrWordBox(
    val text: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** The engine's own confidence for this word, absent when it reported none. */
    val confidence: Double? = null,
) {
    init {
        require(text.isNotEmpty()) { "a word box carries a word" }
        require(width >= 0 && height >= 0) { "a word box has a non-negative size, was ${width}x$height" }
        require(confidence == null || confidence.isFinite()) {
            "a word confidence is a finite number, was $confidence"
        }
    }
}

/** A stretch of a reading an engine reported it could not read, as offsets into the reading's text. */
data class UnreadableSpan(val startOffset: Int, val endOffset: Int) {
    init {
        require(startOffset >= 0) { "an unreadable span starts at or after the first character" }
        require(endOffset > startOffset) {
            "unreadable span $startOffset..$endOffset is empty rather than a stretch of the reading"
        }
    }
}

/**
 * What one page's reading is, and what may honestly be said about it.
 *
 * The two rules this record exists to keep are the ones an empty reading makes easy to get wrong:
 *
 * - **[meanConfidence] is absent when the engine reported none**, which is a different statement from
 *   being certain it read nothing (a confidence of zero), and nothing downstream may turn one into the
 *   other.
 * - **[verifiedBlank] is set only when a page's *image* was verified to be blank paper.** Empty output
 *   alone never establishes blankness: an engine that returned nothing may have read blank paper or may
 *   have missed what is on it. The verification belongs to the caller that holds the raster, which is why
 *   this is a field a result can carry rather than something an engine decides.
 *
 * [unreadableSpans] is the engine naming what it could not read. An empty list means the engine reported
 * nothing unreadable, not that it read the page completely — the two are not the same claim, and neither
 * is evidence of blankness.
 *
 * [errorCode] is a safe status for a reading that failed, and never a provider's or a tool's own message:
 * it travels into queues, logs and the CLI. An engine that fails to read a page may either return a result
 * with a code or throw its own failure, and both are that page's failure rather than the document's.
 */
data class OcrPageResult(
    val text: String,
    /** The engine that produced this reading, which is the one the settings selected. */
    val engine: OcrEngine,
    /** The hash of the page image this reading was made from. */
    val imageSha256: String,
    val boxes: List<OcrWordBox> = emptyList(),
    val meanConfidence: Double? = null,
    val unreadableSpans: List<UnreadableSpan> = emptyList(),
    val verifiedBlank: Boolean = false,
    /** The engine's own reading artifact — the word boxes — relative to the page image's artifact root. */
    val artifactRelativePath: String? = null,
    val artifactSha256: String? = null,
    /** The model or tool version the engine reported, when it reports one. */
    val modelVersion: String? = null,
    val errorCode: String? = null,
) {
    companion object {

        /**
         * The code an engine answers an empty or whitespace-only reading under.
         *
         * An empty reading is not an unqualified success: a page that carries ink and comes back as nothing
         * is a reading that missed what is on it, and the engine cannot tell that from paper that is blank.
         * Which is why an engine reports it as this failure and leaves blankness to the raster — a consumer
         * that receives it fails the page, and one that verifies the page's own image as blank reports the
         * page as blank instead. It is the engine contract rather than one engine's habit: both engines this
         * build has answer with it.
         */
        const val EMPTY_READING_CODE: String = "OCR_EMPTY"
    }

    init {
        require(imageSha256.isNotBlank()) { "a reading names the page image it was made from" }
        require((artifactRelativePath == null) == (artifactSha256 == null)) {
            "a reading's artifact reference is either complete or absent"
        }
        require(meanConfidence == null || meanConfidence.isFinite()) {
            "a mean confidence is a finite number, was $meanConfidence"
        }
        require(errorCode == null || errorCode.isNotBlank()) {
            "an error code is either absent or says which failure it is"
        }
    }

    /**
     * This reading with the page's blankness verified against its own image.
     *
     * An engine cannot tell blank paper from a page it failed to read, so its own answer about blankness is
     * not evidence *unless* the image says so, and the caller that holds the raster is what can ask. This is
     * a no-op for a reading that says something — a page with text on it is not blank whatever its paper
     * looks like — so the raster is only decoded when a page came back with no text at all, or with nothing
     * but whitespace, which is a reading of nothing rather than a reading. The image has to be the one this
     * reading was made from, which is what the hash check enforces.
     */
    fun verifiedAgainst(page: PageImage): OcrPageResult {
        require(page.sha256 == imageSha256) {
            "a reading is verified against the page image it was made from, not against another one"
        }
        return if (text.isNotBlank()) this else copy(verifiedBlank = page.isBlankPaper())
    }
}

/**
 * One page-reading engine: what a settings snapshot selects, and the only thing an extraction asks to read.
 *
 * An engine is handed a page image and the settings one attempt was admitted with, and it answers with one
 * reading. Three things it deliberately cannot do:
 *
 * - **It cannot choose where it dispatches.** A configured client is the only client it has: the call
 *   carries no endpoint, no profile and no key, so an engine cannot pick another destination than the one
 *   its configuration names.
 * - **It cannot change engines.** Nothing here reports "try another one": a failure is this page's or this
 *   document's failure, and a missing tool is reported as the thing that has to be installed.
 * - **It cannot describe a page as blank.** Blankness is a fact about the image, and the caller that holds
 *   the raster is what establishes it.
 *
 * The call is suspendable and cancellable, and a cancellation is not a failed reading: an attempt stopped
 * between pages leaves the pages it did commit committed and nothing else.
 */
interface PageOcrEngine {

    /** Which of the three engines this is, which is the field a settings snapshot selects on. */
    val engine: OcrEngine

    suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult

    /**
     * What this engine's runtime is, discovered without reading a page, or `null` when it cannot be.
     *
     * This exists because whether a committed page may be reused is decided by the *attempt's* fingerprint,
     * which is computed before any page is read — so an identity that arrives with a reading (its
     * `modelVersion`) arrives after the decision it belongs in. What the engine is asked here is therefore
     * what it would read with right now: the installed tool's version, its backend, the weights with their
     * revisions, the inference server's build — the facts that make two readings comparable.
     *
     * An engine whose runtime cannot be described answers **`null`, not a failure and not a guess**. Null is
     * an identity of its own rather than "equal to whatever the other attempt said": a fingerprint over "no
     * identity" never matches one over a discovered identity, so the safe direction is taken — the page is
     * read again instead of being reused under weights nobody described. The default is that answer, for an
     * engine that is a plain installed tool whose version already travels in the extraction settings
     * (`ocrTool`) rather than in a runtime of its own.
     *
     * The call is bounded by the engine that implements it: it may spawn a short-lived process, and it may
     * never start a model or a server to answer.
     */
    suspend fun runtimeIdentity(): String? = null
}

/**
 * The page-reading engines this build has, by kind.
 *
 * The set is what makes "select the engine" a decision made from settings: an attempt whose settings name
 * an engine this build does not have is refused with the code that names it, and the engines that *are*
 * configured are never consulted for it. There is no fallback chain here and no preference order — two
 * engines of one kind are a wiring mistake rather than a coin toss.
 */
class PageOcrEngines(engines: List<PageOcrEngine>) {

    private val byKind: Map<OcrEngine, PageOcrEngine> = buildMap {
        engines.forEach { engine ->
            val previous = put(engine.engine, engine)
            require(previous == null) {
                "two engines are configured for ${engine.engine}: " +
                    "${previous?.javaClass?.simpleName} and ${engine.javaClass.simpleName}"
            }
        }
    }

    /** The kinds this build can read a page with. */
    val configuredKinds: Set<OcrEngine> get() = byKind.keys

    /**
     * The engine for [kind], or null when this build has none.
     *
     * The nullable form exists for the questions that are *not* about reading a page — a runtime identity
     * probe, for one — where an engine this build does not have is an absence to record rather than a
     * document to refuse. Reading a page still goes through [forKind], which refuses by name.
     */
    fun engineFor(kind: OcrEngine): PageOcrEngine? = byKind[kind]

    /** The engine for [kind], or a refusal that names what has to be installed or configured. */
    fun forKind(kind: OcrEngine): PageOcrEngine = byKind[kind] ?: throw OcrUnavailableException(
        NEEDS_ENGINE_CODE_PREFIX + kind.name,
        "no $kind engine is configured in this build, so this document cannot be read with the engine its " +
            "settings select. Nothing was read with another engine: ${remedyFor(kind)} Or select an engine " +
            "this build has.",
    )

    /**
     * What a person can do about an engine this build does not have, in the terms of what has to be
     * installed.
     *
     * A build omits an engine its machine is not set up for rather than offering it and failing on the first
     * page, so reaching here is the *missing runtime* case as well as a wiring mistake — and "install or
     * configure SURYA" is not something a person can act on. The local engines therefore name their own
     * pinned install line, the same one they name when they run and find their runtime missing; an engine
     * whose install line this build does not know keeps the generic words.
     */
    private fun remedyFor(kind: OcrEngine): String = when (kind) {
        OcrEngine.SURYA ->
            "Surya is configured from the local runtime this build spawns its worker with, and that " +
                "runtime was not found: ${SuryaOcr.installRemedy()}."
        OcrEngine.TESSERACT ->
            "Tesseract is not configured in this build, and nothing else reads a page without a text " +
                "layer: ${TesseractOcr.installRemedy()}."
        OcrEngine.LLM -> "Install or configure $kind."
    }

    /**
     * Reads [page] with the engine [kind] selects.
     *
     * A result that names another engine is refused rather than accepted: a reading attributed to an engine
     * that did not produce it is exactly the silent substitution this seam exists to prevent, and an engine
     * answering for another one is a wiring error rather than a page's failure.
     */
    suspend fun transcribe(kind: OcrEngine, page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val reading = forKind(kind).transcribe(page, settings)
        check(reading.engine == kind) {
            "the engine configured as $kind answered as ${reading.engine}; a reading is not moved between " +
                "engines"
        }
        return reading
    }

    companion object {

        /** The prefix of the code that names the engine a build does not have, as `NEEDS_TESSERACT` is. */
        const val NEEDS_ENGINE_CODE_PREFIX: String = "NEEDS_"
    }
}
