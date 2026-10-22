package infoscry.correction

import infoscry.config.AppPaths
import infoscry.domain.AppliedCorrection
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.ReadingText
import infoscry.extract.ContentUnitDraft
import infoscry.ocr.StructureReference
import infoscry.pdf.LayoutReference
import infoscry.storage.CorrectionStore
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.RevisionPageText
import java.nio.file.Path

/**
 * Applies a collection's correction list to text that is about to be published: the pages of a staged candidate
 * revision (a rescan, a check-and-improve import, a restore, a correction pass), and the units of a plain import.
 *
 * Every page is corrected from its reading, never from its current text, so the result is a function of the reading
 * and today's list: applying it again changes nothing, and a removed rule brings the reading back.
 */
class CandidateCorrector(
    private val revisions: DocumentRevisionStore,
    private val corrections: CorrectionStore,
    private val paths: AppPaths,
) {

    /**
     * Corrects every page of [candidateRevisionId] with the collection's current list and records the list on the
     * revision. A page whose corrected text differs from what is staged is rewritten and loses its passages, so the
     * caller chunks and embeds it again; the other pages keep theirs.
     *
     * @return what each rule changed across the whole revision.
     */
    fun correct(collectionId: CollectionId, documentId: DocumentId, candidateRevisionId: String): List<AppliedCorrection> {
        val rules = corrections.list(collectionId)
        val pages = revisions.pages(candidateRevisionId)
        if (rules.isEmpty() && pages.none { it.reading != null }) return emptyList()
        val root = paths.artifactsDir(collectionId, documentId)
        val corrector = PageCorrector(CorrectionMatcher(rules))
        val counts = mutableMapOf<String, Int>()
        pages.forEach { page ->
            val (base, structure, layout) = baseOf(page)
            val corrected = corrector.correct(base, page.ordinal, structure, layout, root)
            corrected.matchesByRule.forEach { (ruleId, matches) -> counts.merge(ruleId, matches, Int::plus) }
            if (corrected.differsFrom(page)) revisions.replaceCorrectedPage(candidateRevisionId, page.ordinal, corrected)
        }
        val applied = appliedOf(rules, counts)
        revisions.recordCorrections(candidateRevisionId, CorrectionRules.fingerprint(rules), applied)
        return applied
    }

    /**
     * Whether the collection's current list would change any page of [revisionId]. It reads only text and writes
     * nothing, so a pass over a large collection can skip the documents that already follow the list.
     */
    fun wouldChange(collectionId: CollectionId, revisionId: String): Boolean {
        val matcher = CorrectionMatcher(corrections.list(collectionId))
        return revisions.pages(revisionId).any { page ->
            val base = page.reading?.extractedText ?: page.extractedText
            val corrected = matcher.apply(base).text
            corrected != page.extractedText || (corrected == base) != (page.reading == null)
        }
    }

    /**
     * Records on a published import revision what the collection's list changed in it. A plain import corrects each
     * unit as it is committed ([correctDraft]), so the counts are read back from the units' readings here.
     */
    fun recordApplied(collectionId: CollectionId, revisionId: String) {
        val rules = corrections.list(collectionId)
        if (rules.isEmpty()) return
        val matcher = CorrectionMatcher(rules)
        val counts = mutableMapOf<String, Int>()
        revisions.pages(revisionId).forEach { page ->
            page.reading?.let { reading ->
                matcher.apply(reading.extractedText).matchesByRule().forEach { (id, n) -> counts.merge(id, n, Int::plus) }
            }
        }
        revisions.recordCorrections(revisionId, CorrectionRules.fingerprint(rules), appliedOf(rules, counts))
    }

    /** The directory a document's artifacts, the corrected structures and layouts among them, are written under. */
    fun artifactRoot(collectionId: CollectionId, documentId: DocumentId): Path = paths.artifactsDir(collectionId, documentId)

    /** A matcher with the collection's current list, for a sink that corrects units one at a time. */
    fun matcherFor(collectionId: CollectionId): CorrectionMatcher? =
        corrections.list(collectionId).takeIf { it.isNotEmpty() }?.let(::CorrectionMatcher)

    private fun baseOf(page: RevisionPageText): Triple<ReadingText, StructureReference?, LayoutReference?> =
        page.reading?.let { Triple(it, it.structure, it.layout) }
            ?: Triple(ReadingText(page.extractedText, page.searchText), page.structureReference, page.layoutReference)

    private fun CorrectedPage.differsFrom(page: RevisionPageText): Boolean =
        extractedText != page.extractedText || searchText != page.searchText || reading != page.reading ||
            structureReference != page.structureReference || layoutReference != page.layoutReference

    private fun appliedOf(rules: List<CorrectionRule>, counts: Map<String, Int>): List<AppliedCorrection> =
        rules.mapNotNull { rule -> counts[rule.id]?.let { AppliedCorrection(rule.find, rule.replace, it) } }
}

/** [draft] with [matcher] applied, the way [CandidateCorrector] corrects a staged page. */
fun correctDraft(draft: ContentUnitDraft, matcher: CorrectionMatcher, ordinal: Int, artifactRoot: Path): ContentUnitDraft {
    val base = draft.reading ?: ReadingText(draft.extractedText, draft.searchText)
    val structure = draft.reading?.structure ?: draft.structureReference
    val layout = draft.reading?.layout ?: draft.layoutReference
    val corrected = PageCorrector(matcher).correct(base, ordinal, structure, layout, artifactRoot)
    return draft.copy(
        extractedText = corrected.extractedText,
        searchText = corrected.searchText,
        reading = corrected.reading,
        structureReference = corrected.structureReference,
        layoutReference = corrected.layoutReference,
    )
}
