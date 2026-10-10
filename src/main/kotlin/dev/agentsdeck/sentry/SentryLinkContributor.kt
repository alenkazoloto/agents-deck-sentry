package dev.agentsdeck.sentry

import com.github.claudeagents.api.ClaudeAgentsApi
import com.github.claudeagents.api.TranscriptLinkContributor
import com.github.claudeagents.api.TranscriptLinkSpec
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Links only cached organization project slugs; the ID shape also matches UTF-8 and CVE IDs.
 * The first lookup or Settings visit fills [SentryWorkspace]. Rendering runs on the EDT:
 * target resolution is string-only, and the regex must stay bounded (EXTENDING.md §7). */
class SentryLinkContributor : TranscriptLinkContributor {

    override fun linkers(project: Project): List<TranscriptLinkSpec> {
        val info = SentryWorkspace.snapshot()?.takeIf { it.projectSlugs.isNotEmpty() } ?: return emptyList()
        return listOf(
            // `inCode = false`: the host's own rule is that identifiers stay literal inside
            // code spans, and a short id is an identifier.
            TranscriptLinkSpec(SentryLinks.SHORT_ID) { match -> SentryLinks.target(info, match.value) },
        )
    }
}

/**
 * The two decisions the linker makes, kept out of the extension point so both are pinned
 * without a `Project` — which is otherwise the only thing standing between these rules and a
 * test.
 */
internal object SentryLinks {

    /** Upper case only: agent output writes the canonical id, and `my-backend-1` is a path. */
    val SHORT_ID = Regex("""\b[A-Z][A-Z0-9_]{0,31}(?:-[A-Z0-9_]{1,32}){1,4}\b""")

    /**
     * Null for a project slug this organization does not have — `UTF-8` is the case that
     * matters, and `CVE-2024-1234` is the one the hyphen rule adds.
     *
     * The target is the organization's issue stream filtered to this short id, **not** a
     * `/issues/<id>/` permalink: that form needs the numeric group id, which a token appearing
     * in agent output has never been resolved to. A filtered stream is one row and one click;
     * a permalink built from an id we do not have would be a guess.
     */
    fun target(info: SentryOrgInfo?, token: String): String? {
        if (info == null || info.projectSlugs.isEmpty()) return null
        val slug = SentryQueries.projectSlug(token) ?: return null
        if (slug !in info.projectSlugs) return null
        val canonical = SentryQueries.canonicalize(token)
        // The install comes off the probe that found this organization, never a default: the
        // linker runs on the EDT and cannot resolve `SENTRY_URL` itself, so a default here
        // would send every self-hosted user's links to sentry.io.
        return "${SentryQueries.normalizeBase(info.base)}/organizations/${info.slug}/issues/?query=$canonical"
    }
}

/**
 * Resolves whether a token exists at all, once per project open, off the EDT — **and makes no
 * network call**.
 *
 * Without it the settings section can say nothing until the user expands it, because the two
 * questions it answers are asked on the EDT and resolving a secret reads a file. The
 * *organization* probe is deliberately not here: it would send the user's token to sentry.io
 * on every IDE start, which is a request nobody asked for. That one happens on first use.
 */
class SentryStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val api = ApplicationManager.getApplication().getService(ClaudeAgentsApi::class.java) ?: return
        val token = runCatching { api.resolveSecret(SentryQueries.TOKEN_NAME) }.getOrNull()
        SentryWorkspace.rememberTokenPresence(!token.isNullOrBlank())
    }
}
