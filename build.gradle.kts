plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "dev.agentsdeck"

// Comment cleanup; behavior unchanged.
version = "0.1.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** The host plugin's distribution zip — see `agentsDeckPluginZip` in `gradle.properties`. */
val agentsDeckPluginZip = providers.gradleProperty("agentsDeckPluginZip")
    .map { layout.projectDirectory.file(it).asFile }

// Mirrors the host's gate: `-PplatformVersion=2025.3.6.1` compiles this module against the
// declared `sinceBuild` floor, which is the only way to prove a 2026.1-only class has not
// crept in. Published artifacts always use the default.
val platformVersion: String =
    providers.gradleProperty("platformVersion").orNull?.trim()?.ifEmpty { null } ?: "2026.1.4"

dependencies {
    intellijPlatform {
        // The same platform the host compiles against.
        intellijIdea(platformVersion) { useInstaller = false }
        // Puts Agents Deck on this module's compile classpath and installs it into the runIde
        // sandbox, so one IDE starts with both plugins loaded.
        localPlugin(agentsDeckPluginZip)
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            // Match the host: an extension cannot load into an IDE the host itself refuses.
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}
