package infoscry.correction

import infoscry.domain.ApiStatus
import infoscry.domain.ApiFaultException
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.Normalizer2
import java.security.MessageDigest

/** A rule as a person writes it, before it has an identity. */
data class CorrectionRuleInput(val find: String, val replace: String, val exactCase: Boolean)

/** A rule that cannot be stored as written; the message names the problem, never the rule's words. */
class InvalidCorrectionException(message: String) : IllegalArgumentException(message)

/** Another rule of the collection already finds the same word. */
class CorrectionFindTakenException : ApiFaultException(
    ApiStatus.CONFLICT,
    "CORRECTION_FIND_TAKEN",
    "another correction already finds that word",
)

/** The rules every stored correction obeys, and the identity of a whole list. */
object CorrectionRules {

    /** The longest `find` or `replace`, in characters. */
    const val MAX_LENGTH = 200

    private val NFC: Normalizer2 = Normalizer2.getNFCInstance()

    private val WHITESPACE = Regex("\\s+")

    /**
     * The rule as it is stored: NFC, trimmed, inner whitespace collapsed to one space.
     *
     * @throws InvalidCorrectionException for a blank value, a value over [MAX_LENGTH] characters, a `find` that does
     *   not begin and end with a letter, mark or digit, or a rule that would change nothing.
     */
    fun normalize(input: CorrectionRuleInput): CorrectionRuleInput {
        val find = clean(input.find)
        val replace = clean(input.replace)
        if (find.isEmpty()) throw InvalidCorrectionException("a correction needs the text to find")
        if (replace.isEmpty()) throw InvalidCorrectionException("a correction needs the text to write instead")
        if (find.length > MAX_LENGTH || replace.length > MAX_LENGTH) {
            throw InvalidCorrectionException("a correction is at most $MAX_LENGTH characters on each side")
        }
        if (!isWordCodePoint(find.codePointAt(0)) || !isWordCodePoint(find.codePointBefore(find.length))) {
            throw InvalidCorrectionException("the text to find begins and ends with a letter or digit")
        }
        if (find == replace) throw InvalidCorrectionException("a correction changes the text it finds")
        return CorrectionRuleInput(find, replace, input.exactCase)
    }

    /** What two rules of one collection may not share: the folded `find`, or the exact one for an exact-case rule. */
    fun findKey(input: CorrectionRuleInput): String =
        if (input.exactCase) "exact:${input.find}" else "fold:${UCharacter.foldCase(input.find, UCharacter.FOLD_CASE_DEFAULT)}"

    /**
     * The identity of a correction list: SHA-256 over its rules' words and case flags in a canonical order. It does
     * not depend on the list's order, the rules' ids or when they were edited, so an edit that changes nothing leaves
     * it unchanged.
     */
    fun fingerprint(rules: List<CorrectionRule>): String {
        val lines = rules
            .map { rule -> CorrectionRuleInput(rule.find, rule.replace, rule.exactCase) }
            .sortedWith(compareBy({ findKey(it) }, { it.replace }))
            .joinToString("") { "${if (it.exactCase) 1 else 0}\t${it.find}\t${it.replace}\n" }
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun clean(value: String): String = NFC.normalize(value).trim().replace(WHITESPACE, " ")

    private fun isWordCodePoint(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || Character.getType(codePoint).toByte().let {
            it == Character.NON_SPACING_MARK || it == Character.ENCLOSING_MARK || it == Character.COMBINING_SPACING_MARK
        }
}
