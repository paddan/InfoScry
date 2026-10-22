package infoscry.correction

import infoscry.collection.CollectionService
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.Job
import infoscry.domain.JobType
import infoscry.domain.ReadingText
import infoscry.storage.CorrectionStore
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.RevisionPageText

/** A correction failed for a reason the job reports by [code]. */
class CorrectionJobFailedException(val code: String, message: String) : IllegalStateException(message)

/** One place a draft rule matches, as the text reads now and as it would read. */
data class CorrectionExample(val documentId: DocumentId, val unitId: ContentUnitId, val before: String, val after: String)

/** What a draft rule, or removing one, would do to the collection. */
data class CorrectionPreview(val matches: Int, val documents: Int, val examples: List<CorrectionExample>)

/** A stored rule and how many places it changed in the collection's published text. */
data class CorrectionRuleView(val rule: CorrectionRule, val matches: Int)

/** A collection's rules and the correction job that applies them (running, or the last one). */
data class CorrectionListView(val rules: List<CorrectionRuleView>, val job: Job?)

/**
 * A collection's standing corrections: the list, its preview, and the job that makes every document follow it.
 *
 * Saving or removing a rule stores it at once and starts (or joins) the collection's one correction job, so the
 * list and the text converge without a separate apply step. Every operation is safe to repeat. A preview reads
 * the documents' readings, the same text the job corrects, and changes nothing.
 */
class CorrectionService(
    private val store: CorrectionStore,
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val jobs: JobStore,
    private val collections: CollectionService,
    private val mutations: MutationCoordinator,
) {

    private val enqueueLock = Any()

    fun list(collectionId: CollectionId): CorrectionListView {
        collections.requireActive(collectionId)
        val counts = mutableMapOf<String, Int>()
        documentIds(collectionId).mapNotNull { revisions.activeRevisionId(it) }.forEach { revisionId ->
            revisions.revision(revisionId)?.correctionsApplied?.forEach { applied ->
                counts.merge(applied.find, applied.matches, Int::plus)
            }
        }
        val rules = store.list(collectionId).map { rule -> CorrectionRuleView(rule, counts[rule.find] ?: 0) }
        val job = jobs.activeJob(JobType.CORRECTIONS, collectionId) ?: jobs.latestJob(JobType.CORRECTIONS, collectionId)
        return CorrectionListView(rules, job)
    }

    /** Stores rule [id] and starts the collection's correction job. */
    suspend fun put(collectionId: CollectionId, id: String, input: CorrectionRuleInput): CorrectionRule {
        val rule = mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            store.put(collectionId, id, input)
        }
        apply(collectionId)
        return rule
    }

    /** Removes rule [id], when it is there, and starts the collection's correction job. */
    suspend fun delete(collectionId: CollectionId, id: String) {
        mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            store.delete(collectionId, id)
        }
        apply(collectionId)
    }

    /** The collection's running correction job, or a new one: there is never more than one. */
    fun apply(collectionId: CollectionId): Job {
        collections.requireActive(collectionId)
        synchronized(enqueueLock) {
            jobs.activeJob(JobType.CORRECTIONS, collectionId)?.let { return it }
            return jobs.enqueue(JobType.CORRECTIONS, collectionId, total = documents.countByCollection(collectionId))
        }
    }

    /**
     * What [draft] would change, with the rest of the list as it is ([replacingId] is left out, being the rule the
     * draft replaces). Only the draft's own matches are counted.
     */
    fun preview(collectionId: CollectionId, draft: CorrectionRuleInput, replacingId: String?): CorrectionPreview {
        collections.requireActive(collectionId)
        val normalized = CorrectionRules.normalize(draft)
        val others = store.list(collectionId).filter { it.id != replacingId }
        val draftRule = CorrectionRule(DRAFT_ID, collectionId, normalized.find, normalized.replace, normalized.exactCase, "", "")
        val matcher = CorrectionMatcher(others + draftRule)
        var matches = 0
        var changedDocuments = 0
        val examples = mutableListOf<CorrectionExample>()
        documentPages(collectionId).forEach { (documentId, pages) ->
            var inDocument = 0
            pages.forEach { page ->
                val base = baseOf(page)
                matcher.apply(base.extractedText).edits.filter { it.ruleId == DRAFT_ID }.forEach { edit ->
                    inDocument++
                    if (examples.size < MAX_EXAMPLES) examples += example(documentId, page, base.extractedText, edit)
                }
            }
            matches += inDocument
            if (inDocument > 0) changedDocuments++
        }
        return CorrectionPreview(matches, changedDocuments, examples)
    }

    /** What removing rule [id] would change: the documents whose text would return to the reading there. */
    fun previewRemoval(collectionId: CollectionId, id: String): CorrectionPreview {
        collections.requireActive(collectionId)
        val rules = store.list(collectionId)
        val withRule = CorrectionMatcher(rules)
        val without = CorrectionMatcher(rules.filter { it.id != id })
        var matches = 0
        var changedDocuments = 0
        val examples = mutableListOf<CorrectionExample>()
        documentPages(collectionId).forEach { (documentId, pages) ->
            var changed = false
            pages.forEach { page ->
                val base = baseOf(page)
                val edits = withRule.apply(base.extractedText).edits.filter { it.ruleId == id }
                matches += edits.size
                if (without.apply(base.extractedText).text != page.extractedText) changed = true
                edits.forEach { edit ->
                    if (examples.size < MAX_EXAMPLES) {
                        val shown = example(documentId, page, base.extractedText, edit)
                        examples += shown.copy(before = shown.after, after = shown.before)
                    }
                }
            }
            if (changed) changedDocuments++
        }
        return CorrectionPreview(matches, changedDocuments, examples)
    }

    // ---- reading the collection ----

    /** Each document's published pages, by document. */
    private fun documentPages(collectionId: CollectionId): Sequence<Pair<DocumentId, List<RevisionPageText>>> =
        documentIds(collectionId).asSequence().mapNotNull { documentId ->
            revisions.activeRevisionId(documentId)?.let { documentId to revisions.pages(it) }
        }

    private fun documentIds(collectionId: CollectionId): List<DocumentId> {
        val count = documents.countByCollection(collectionId)
        if (count == 0) return emptyList()
        return documents.listByCollection(collectionId, limit = count).map { it.id }
    }

    private fun baseOf(page: RevisionPageText): ReadingText = page.reading ?: ReadingText(page.extractedText, page.searchText)

    private fun example(documentId: DocumentId, page: RevisionPageText, text: String, edit: TextEdit): CorrectionExample {
        val start = safeBoundary(text, maxOf(0, edit.start - CONTEXT_CHARS))
        val end = safeBoundary(text, minOf(text.length, edit.endExclusive + CONTEXT_CHARS))
        val prefix = text.substring(start, edit.start)
        val suffix = text.substring(edit.endExclusive, end)
        return CorrectionExample(
            documentId = documentId,
            unitId = page.unitId,
            before = prefix + text.substring(edit.start, edit.endExclusive) + suffix,
            after = prefix + edit.replacement + suffix,
        )
    }

    /** [offset], moved back off the second half of a surrogate pair. */
    private fun safeBoundary(text: String, offset: Int): Int =
        if (offset in 1 until text.length && Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1])) {
            offset - 1
        } else {
            offset
        }

    private companion object {
        const val DRAFT_ID = "\u0000draft"
        const val MAX_EXAMPLES = 5
        const val CONTEXT_CHARS = 60
    }
}
