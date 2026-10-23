package infoscry.glossary

import infoscry.collection.CollectionService
import infoscry.domain.CollectionId
import infoscry.storage.GlossaryStore
import infoscry.storage.MutationCoordinator
import java.util.concurrent.ConcurrentHashMap

/**
 * A collection's glossary: names with the same meaning that search expands and Ask and Investigate are told about.
 *
 * Writes run under shared mutation admission and require an active collection; every operation is safe to repeat.
 * [snapshot] is what every search reads, so it is cached per collection and the entry is dropped on each write: a
 * change applies from the next search, and nothing is reindexed.
 */
class GlossaryService(
    private val store: GlossaryStore,
    private val collections: CollectionService,
    private val mutations: MutationCoordinator,
) {

    private val cache = ConcurrentHashMap<CollectionId, Glossary>()

    fun list(collectionId: CollectionId): Glossary {
        collections.requireActive(collectionId)
        return store.list(collectionId)
    }

    /** Creates or replaces group [id] with [terms]. */
    suspend fun put(collectionId: CollectionId, id: String, terms: List<GlossaryTermInput>): GlossaryGroup =
        mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            try {
                store.put(collectionId, id, terms)
            } finally {
                cache.remove(collectionId)
            }
        }

    /** Removes group [id]; removing one that is already gone succeeds. */
    suspend fun delete(collectionId: CollectionId, id: String) {
        mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            try {
                store.delete(collectionId, id)
            } finally {
                cache.remove(collectionId)
            }
        }
    }

    /**
     * The glossary search and the LLM requests use, without the active-collection check: callers ask only for
     * collections they are already reading, and a collection's rows go with it when it is deleted.
     */
    fun snapshot(collectionId: CollectionId): Glossary = cache.computeIfAbsent(collectionId) { store.list(it) }
}
