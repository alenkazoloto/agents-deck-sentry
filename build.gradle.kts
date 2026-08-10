plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "dev.agentsdeck"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** The host plugin's distribution zip — see `agentsDeckPluginZip` in `gradle.properties`. */
val agentsDeckPluginZip = providers.gradleProperty("agentsDeckPluginZip")
    .map { layout.projectDirectory.file(it).asFile }

dependencies {
    intellijPlatform {
        // The same platform the host compiles against.
        intellijIdea("2026.1.4") { useInstaller = false }
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
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}
