package infoscry.ask

data class CitationValidation(val valid: List<String>, val invalid: List<String>)

/** Validates only citation ids that were supplied in the request that generated the answer. */
class CitationValidator {
    private val pattern = Regex("\\[(S[0-9]+)]")

    fun validate(answer: String, supplied: Collection<Evidence>): CitationValidation {
        val allowed = supplied.map { it.id }.toSet()
        val ids = pattern.findAll(answer).map { it.groupValues[1] }.distinct().toList()
        return CitationValidation(ids.filter { it in allowed }, ids.filter { it !in allowed })
    }
}
