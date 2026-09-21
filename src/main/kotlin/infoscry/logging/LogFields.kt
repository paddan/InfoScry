package infoscry.logging

/**
 * The names InfoScry uses for the structured fields it logs.
 *
 * One spelling per concept, shared by the writers (job runner, import handler, server) and the
 * readers (the JSON encoder, `infoscry logs`, the web log panel). A field that is not named here
 * still reaches the log, but only inside the generic `fields` object.
 */
object LogFields {

    /** Which part of the product produced the record, for example `ingest` or `search`. */
    const val COMPONENT = "component"

    /** The job a record belongs to. */
    const val JOB = "job"

    /** The document a record belongs to. */
    const val DOCUMENT = "document"
}
