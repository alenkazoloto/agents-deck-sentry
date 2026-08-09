// Agents Deck — Sentry extension.
//
// A standalone Gradle build, like `extensions/linear` and `extensions/tessl`: a first-party
// extension must compile against the *published host artifact*, not against a sibling source
// project, or the api's only regression gate (`./gradlew verifyExtensions` in the host) would
// be verifying a lie.
//
// The two builds meet in one place: the `agentsDeckPluginZip` property in `gradle.properties`.
//
// Line comments, not a block comment: Kotlin block comments nest, so a path glob written
// inside one opens a second comment the closing marker only half closes.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "agents-deck-sentry"
