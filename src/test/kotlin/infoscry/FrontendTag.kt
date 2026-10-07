package infoscry

/**
 * The JUnit tag for a test that needs the compiled frontend on the classpath.
 *
 * `./gradlew test -PskipFrontend` leaves the frontend out of the resources and excludes this tag, so a
 * backend-only run does not need Node. A normal `check` runs these tests. `build.gradle.kts` declares the
 * same value, and `verifyTestTags` fails the build if the two drift apart.
 */
const val FRONTEND_TAG: String = "frontend"
