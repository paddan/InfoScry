package infoscry.llm

/** A Default-shaped prompt for tests that do not exercise prompt selection. */
fun defaultTestPrompt(version: Int = 1): ResolvedPrompt =
    ResolvedPrompt(id = null, name = "Default", version = version, body = "Answer from the sources.")
