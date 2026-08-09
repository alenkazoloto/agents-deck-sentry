package dev.agentsdeck.sentry

import com.github.claudeagents.api.ExtIssueRef
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One GET of a path, and the response text.
 *
 * A `fun interface` so every test above this line runs with no network: the real one is
 * [HttpSentryTransport], and the tests hand in a lambda. Null means the request never produced
 * a body at all.
 */
internal fun interface SentryTransport {
    fun get(url: String): String?
}

/** A value and, when something went wrong, the one sentence worth showing a user. */
internal data class SentryAnswer<T>(val value: T, val error: String? = null)

/**
 * Sentry's REST API behind four calls.
 *
 * **Every method answers rather than throws** — the `issueProvider` api's contract, and the
 * reason the error travels *beside* the value instead of instead of it: the completion popup
 * wants the empty list, the settings section wants the sentence, and neither should have to
 * catch anything to get it.
 *
 * Blocking. Called on the host's background threads only.
 */
internal class SentryClient(
    private val transport: SentryTransport,
    private val base: String = SentryQueries.DEFAULT_BASE,
) {

    /**
     * Who this token belongs to, and what it can link.
     *
     * Two requests, not one: the organization comes from the token
     * ([list your organizations](https://docs.sentry.io/api/users/list-your-organizations/)
     * returns exactly the bound organization for an API-key request), and the project slugs
     * come from that organization. Both happen once per session, on first use — never at IDE
     * start, which would send the user's token to sentry.io for a session they may spend
     * entirely elsewhere.
     *
     * [preferred] is [SentryQueries.ORG_NAME] when it is set. It only decides anything when
     * the token sees more than one organization; a token bound to one is not overridden by it,
     * because the bound organization is a fact and the variable is a preference.
     */
    fun organization(preferred: String?): SentryAnswer<SentryOrgInfo?> {
        val response = transport.get(SentryQueries.organizationsPath(base))
            ?: return SentryAnswer(null, offline())
        SentryQueries.parseError(response)?.let { return SentryAnswer(null, it) }
        val orgs = SentryQueries.parseOrganizations(response)
        val chosen = when {
            orgs.isEmpty() -> return SentryAnswer(null, "This token can see no Sentry organization.")
            orgs.size == 1 -> orgs.first()
            else -> orgs.firstOrNull { it.slug.equals(preferred?.trim(), ignoreCase = true) }
                // Not a guess dressed as a fact: with several organizations and no
                // SENTRY_ORG there is no correct answer, and picking the first would aim
                // every lookup at whichever one sorted first.
                ?: return SentryAnswer(
                    null,
                    "This token sees ${orgs.size} organizations — set ${SentryQueries.ORG_NAME} to one of: " +
                        orgs.joinToString(", ") { it.slug },
                )
        }
        val projects = transport.get(SentryQueries.projectsPath(base, chosen.slug))
        return SentryAnswer(
            SentryOrgInfo(
                slug = chosen.slug,
                name = chosen.name,
                base = SentryQueries.normalizeBase(base),
                projectSlugs = projects?.let(SentryQueries::parseProjectSlugs).orEmpty(),
                chosenByEnv = orgs.size > 1,
            ),
        )
    }

    /**
     * A typed short id, with the latest event's stack trace as its description.
     *
     * **Two requests, and only here.** The trace is what makes this row worth more to an agent
     * than a link, but fetching one per *search* row would turn a single keystroke into a
     * round trip per result — so [search] never does, and this does it once for the issue the
     * user actually named.
     */
    fun issue(org: String, token: String): SentryAnswer<ExtIssueRef?> {
        val response = transport.get(SentryQueries.shortIdPath(base, org, token))
            ?: return SentryAnswer(null, offline())
        SentryQueries.parseError(response)?.let { return SentryAnswer(null, it) }
        val resolution = SentryQueries.parseShortId(response, base) ?: return SentryAnswer(null)
        val trace = transport.get(SentryQueries.latestEventPath(base, org, resolution.groupId))
            ?.let(SentryQueries::parseEventTrace)
        // A missing trace costs the description, never the row: an issue with no event left in
        // retention is still the issue the user asked for.
        return SentryAnswer(resolution.ref.copy(description = trace))
    }

    fun search(org: String, text: String, limit: Int): SentryAnswer<List<ExtIssueRef>> {
        val response = transport.get(SentryQueries.issuesPath(base, org, text, limit))
            ?: return SentryAnswer(emptyList(), offline())
        SentryQueries.parseError(response)?.let { return SentryAnswer(emptyList(), it) }
        return SentryAnswer(SentryQueries.parseIssues(response, base, org))
    }

    private fun offline(): String = "Could not reach ${SentryQueries.normalizeBase(base).removePrefix("https://")}."
}

/**
 * The real transport: `Authorization: Bearer <token>`
 * ([API authentication](https://docs.sentry.io/api/auth/)) — which is what a Sentry *auth
 * token* wants, and the reason this extension needs no OAuth, no browser redirect and no
 * password store.
 *
 * A non-2xx with no `detail` body of its own is turned into one, so an HTTP failure and a
 * schema failure arrive at the caller through a single channel rather than two that could be
 * picked between wrongly.
 */
internal class HttpSentryTransport(private val token: String) : SentryTransport {

    override fun get(url: String): String? {
        val request = HttpRequest.newBuilder(runCatching { URI.create(url) }.getOrNull() ?: return null)
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrNull() ?: return null
        val text = response.body().orEmpty()
        if (response.statusCode() in 200..299) return text.ifBlank { null }
        return if (SentryQueries.parseError(text) != null) text else httpError(response.statusCode())
    }

    private fun httpError(status: Int): String {
        val message = when (status) {
            401 -> "Sentry rejected the auth token."
            // The scopes are the actionable half: a token created for releases has neither.
            403 -> "This token lacks the org:read and event:read scopes."
            404 -> "Sentry has no such issue in this organization."
            429 -> "Sentry rate limit reached — try again shortly."
            else -> "Sentry returned HTTP $status."
        }
        return """{"detail":${quote(message)}}"""
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

        val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
