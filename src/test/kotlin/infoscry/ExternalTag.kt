package infoscry

/**
 * The JUnit tag that marks a test as needing a tool installed on this machine.
 *
 * The default suite excludes this tag (see `build.gradle.kts`), because a test that needs Tesseract or
 * Calibre cannot pass wherever they are absent, and a suite that failed on those machines would teach
 * people to ignore it. That only works while the build and the tests agree on the tag's exact spelling, so
 * it is spelled here once and `build.gradle.kts` declares the same value: a second spelling in a test file
 * would silently put one test back into the default run, which is the failure the tag exists to prevent.
 *
 * A verification task fails the build if the two ever drift apart.
 */
const val EXTERNAL_TAG: String = "external"
