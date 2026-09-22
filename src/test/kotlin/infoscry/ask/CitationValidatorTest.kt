package infoscry.ask

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class CitationValidatorTest {
    @Test
    fun unknownIdsAreInvalidAndNeverClickable() {
        val evidence = Evidence("S1", "c", "d", "u", SourceLocation.TextLines(1, 2), "lines 1-2", "text")
        val result = CitationValidator().validate("Claim [S1] and [S999]", listOf(evidence))
        assertEquals(listOf("S1"), result.valid)
        assertEquals(listOf("S999"), result.invalid)
    }
}
