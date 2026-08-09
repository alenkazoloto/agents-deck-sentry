package dev.agentsdeck.sentry

import com.github.claudeagents.api.ClaudeAgentsApi
import com.github.claudeagents.api.TranscriptLinkContributor
import com.github.claudeagents.api.TranscriptLinkSpec
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * `BACKEND-1A2B` in agent output becomes a link to the issue.
 *
 * **Only for project slugs this organization actually has**, and here that gate carries more
 * weight than it does next door in Linear: a Sentry short id's counter is base-32, not
 * decimal, so the shape also fits `UTF-8`, `SHA-256`, `ISO-8601` and `RFC-822` — and, because
 * a project slug may itself contain hyphens, `CVE-2024-1234` too. Linking every match would
 * aim a link at an organization that has no such project, which is a 404 dressed as an answer.
 * So the pattern is broad and the *target* is gated: an unknown project slug returns null, the
 * documented way to say "recognised the shape, cannot resolve it", and the token stays plain
 * text.
 *
 * Until [SentryWorkspace] has been probed there are no project slugs and this contributes
 * nothing at all — the first `#` lookup or the first visit to Settings fills it.
 *
 * EDT, per rendered message: the list is built here and the target function does string work
 * only. The pattern is bounded and alternation-free — a catastrophic backtrack here freezes
 * the transcript with nothing to catch it (EXTENDING.md §7).
 */
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
