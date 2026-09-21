package infoscry.diagnostics

import infoscry.extract.ExternalProcess
import infoscry.extract.ExternalToolMissingException
import infoscry.extract.TesseractOcr
import java.time.Duration

/**
 * What one external tool this machine has, or has not.
 *
 * [available] says the executable is there and reports a version — the minimum for it to be worth trying.
 * [languages] is what the tool says it can read, which is a separate matter: Tesseract installed without a
 * language pack is installed and useless, and the difference between those two states is exactly what a
 * person needs to see. [remedy] is present when something has to be done about it, in the words of the
 * platform's own package manager.
 */
data class ToolStatus(
    val name: String,
    val executable: String,
    val available: Boolean,
    val version: String? = null,
    val languages: List<String> = emptyList(),
    val remedy: String? = null,
) {

    /** One line a person can read: what it is, what it is at, and what it can read. */
    val summary: String
        get() = when {
            !available -> "$name is not installed"
            else -> "$name ${version ?: "unknown version"} reads ${languages.size} languages"
        }
}

/**
 * Asks the machine what it has, without changing anything.
 *
 * Every answer here comes from running the tool, never from an assumption about a platform: a package
 * manager can install a tool under a name this project did not expect, a language pack can be absent, and
 * a tool can be present but not runnable. The probes are bounded and read-only, and the version command is
 * consulted before the language list because a tool that cannot report a version cannot be used at all.
 *
 * Nothing here decides what a problem means. A missing tool is a fact; whether it stops the work is the
 * caller's judgement — the doctor reports it, and the pipeline fails only the documents that need it.
 */
object ToolProbe {

    /** How long a tool is given to answer a question about itself. */
    private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(30)

    /** The line Tesseract prints before its language list, which is not a language. */
    private const val LANGUAGE_LIST_HEADER_MARKER: String = "List of available languages"

    /**
     * What Tesseract reports about itself: its version, and the languages it can read.
     *
     * A tool that cannot be run at all is reported as unavailable and nothing else is asked of it. A tool
     * that runs but cannot list its languages is reported as available with no languages, because that is
     * what happened: the fix for it is a language pack, and the fix for a missing tool is an install.
     */
    suspend fun tesseract(executable: String = TesseractOcr.DEFAULT_EXECUTABLE): ToolStatus {
        val version = probe(executable, VERSION_FLAG)
        if (!version.available) {
            return ToolStatus(
                name = NAME,
                executable = executable,
                available = false,
                remedy = TesseractOcr.installRemedy(),
            )
        }
        return ToolStatus(
            name = NAME,
            executable = executable,
            available = true,
            version = version.firstLine,
            languages = probe(executable, LANGUAGES_FLAG).let { listing ->
                if (listing.available) parseLanguages(listing.output) else emptyList()
            },
        )
    }

    private suspend fun probe(executable: String, flag: String): Probe = try {
        val outcome = ExternalProcess.run(command = listOf(executable, flag), timeout = PROBE_TIMEOUT, cwd = null)
        if (outcome.succeeded) {
            Probe(available = true, output = outcome.stdout)
        } else {
            Probe(available = false, output = "")
        }
    } catch (notInstalled: ExternalToolMissingException) {
        Probe(available = false, output = "")
    }

    /**
     * The languages from Tesseract's own listing.
     *
     * The listing starts with a sentence naming the directory the packs came from and how many there are,
     * which is not a language; the codes follow, one per line. Blank lines are dropped because the tool
     * ends its list with one on some platforms.
     */
    private fun parseLanguages(listing: String): List<String> = listing.lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .filterNot { line -> line.contains(LANGUAGE_LIST_HEADER_MARKER) }
        .toList()

    /** What one probe answered. */
    private class Probe(val available: Boolean, val output: String) {

        val firstLine: String? get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    /** The name this tool is known by in the product's own copy. */
    const val NAME: String = "Tesseract"

    private const val VERSION_FLAG: String = "--version"
    private const val LANGUAGES_FLAG: String = "--list-langs"
}
