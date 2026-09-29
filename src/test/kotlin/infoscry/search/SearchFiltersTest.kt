package infoscry.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchFiltersTest {
    @Test
    fun `normalizes blank strings and inclusive ISO dates to persisted UTC millisecond bounds`() {
        val filters = SearchFilters(
            filenameOrPathContains = "  ",
            titleAuthorOrLanguageContains = "",
            importedFrom = "2026-03-01",
            importedUntil = "2026-03-01",
        ).normalized()

        assertEquals(null, filters.filenameOrPathContains)
        assertEquals(null, filters.titleAuthorOrLanguageContains)
        assertEquals("2026-03-01T00:00:00.000Z", filters.importedFrom)
        assertEquals("2026-03-01T23:59:59.999Z", filters.importedUntil)
    }

    @Test
    fun `normalizes offset instants and rounds bounds inward to stored milliseconds`() {
        val filters = SearchFilters(
            importedFrom = "2026-03-01T12:00:00.0000001+02:00",
            importedUntil = "2026-03-01T12:00:00.0009999+02:00",
        ).normalized()

        assertEquals("2026-03-01T10:00:00.001Z", filters.importedFrom)
        assertEquals("2026-03-01T10:00:00.000Z", filters.importedUntil)
    }

    @Test
    fun `rejects invalid and reversed date bounds without echoing input`() {
        val invalid = assertFailsWith<IllegalArgumentException> {
            SearchFilters(importedFrom = "private-input").normalized()
        }
        assertEquals("from must be an ISO date or ISO instant", invalid.message)

        val reversed = assertFailsWith<IllegalArgumentException> {
            SearchFilters(importedFrom = "2026-03-02", importedUntil = "2026-03-01").normalized()
        }
        assertEquals("from must be on or before until", reversed.message)
    }
}
