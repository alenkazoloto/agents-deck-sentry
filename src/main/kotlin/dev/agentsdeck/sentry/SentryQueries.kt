package dev.agentsdeck.sentry

import com.github.claudeagents.api.ExtIssueRef
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Uses documented REST endpoints (verified 2026-08-05):
 * [short IDs](https://docs.sentry.io/api/organizations/resolve-a-short-id/),
 * [issues](https://docs.sentry.io/api/events/list-an-organizations-issues/),
 * [events](https://docs.sentry.io/api/events/retrieve-an-issue-event/) (`latest`), and
 * [organizations](https://docs.sentry.io/api/users/list-your-organizations/).
 * Unknown response shapes return null/empty instead of throwing from completion. */
internal object SentryQueries {

    /**
     * The three env-style names, all of them **`sentry-cli`'s own**
     * ([configuration](https://docs.sentry.io/cli/configuration/)). This extension invents no
     * configuration: a user who has already set up `sentry-cli` is configured here too, and
     * one who has not gets instructions that are useful outside this plugin.
     */
    const val TOKEN_NAME = "SENTRY_AUTH_TOKEN"

    /** Optional. Only consulted when the token can see more than one organization. */
    const val ORG_NAME = "SENTRY_ORG"

    /** Optional. Self-hosted and regional installs; the default is Sentry's SaaS host. */
    const val URL_NAME = "SENTRY_URL"

    const val DEFAULT_BASE = "https://sentry.io"

    /**
     * `BACKEND-1A2B`, and also `MY-BACKEND-1A2B` — a Sentry short id is the project slug
     * upper-cased, then a base-32 counter, and **a project slug may itself contain hyphens**.
     * So the last hyphen is the split, not the first, and [projectSlug] does that rather than
     * a capture group.
     *
     * Bounded repetition throughout, and no nested unbounded quantifier: this is matched
     * against rendered agent text on the EDT, where a pattern that backtracks has nothing to
     * catch it (EXTENDING.md §7).
     */
    private val SHORT_ID = Regex("""[A-Za-z][A-Za-z0-9_]{0,31}(?:-[A-Za-z0-9_]{1,32}){1,4}""")

    fun isShortId(token: String): Boolean = SHORT_ID.matches(token.trim())

    /** `backend-1a2b` → `BACKEND-1A2B`. Sentry renders a short id upper case everywhere. */
    fun canonicalize(token: String): String = token.trim().uppercase()

    /**
     * The project slug of a short id (`MY-BACKEND` of `MY-BACKEND-1A2B`), or null when the
     * token is not one. **Split at the last hyphen**, because the counter is the one segment
     * guaranteed not to contain another.
     */
    fun projectSlug(token: String): String? =
        token.trim().takeIf { isShortId(it) }?.substringBeforeLast('-')?.uppercase()

    /**
     * `https://sentry.io`, or whatever [URL_NAME] holds, with any trailing slash and any
     * `/api/0` suffix taken off — a user who pasted the API root rather than the host would
     * otherwise build every path twice.
     */
    fun normalizeBase(raw: String?): String {
        val text = raw?.trim().orEmpty().ifEmpty { return DEFAULT_BASE }
        val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        return withScheme.trimEnd('/').removeSuffix("/api/0").trimEnd('/')
    }

    fun organizationsPath(base: String): String = "${normalizeBase(base)}/api/0/organizations/"

    fun shortIdPath(base: String, org: String, token: String): String =
        "${normalizeBase(base)}/api/0/organizations/${encode(org)}/shortids/${encode(canonicalize(token))}/"

    fun issuesPath(base: String, org: String, text: String, limit: Int): String {
        val query = listOf(
            "query=${encode(text.trim())}",
            "limit=${limit.coerceIn(1, 100)}",
            // 90d is the widest window the endpoint documents. A `#` lookup that silently
            // ignored anything older than a fortnight — the endpoint's own default — would
            // look like "no results" for exactly the long-lived issues worth asking about.
            "statsPeriod=90d",
        ).joinToString("&")
        return "${normalizeBase(base)}/api/0/organizations/${encode(org)}/issues/?$query"
    }

    /** The documented `latest` sentinel stands in for an event id. */
    fun latestEventPath(base: String, org: String, groupId: String): String =
        "${normalizeBase(base)}/api/0/organizations/${encode(org)}/issues/${encode(groupId)}/events/latest/"

    /**
     * The organization's projects — which is where the *link* gate's slugs come from.
     * `per_page` is the documented maximum; an organization with more projects than that
     * simply links fewer of them, which is the safe direction to be wrong in.
     */
    fun projectsPath(base: String, org: String): String =
        "${normalizeBase(base)}/api/0/organizations/${encode(org)}/projects/?per_page=100"

    /** `https://<org>.sentry.io/issues/<id>/` and the `/organizations/<org>/` form both appear. */
    val URL_PATTERN = Regex("""https://[\w.-]{1,64}/(?:organizations/[\w.-]{1,64}/)?issues/(\d{1,19})""")

    /** The organizations this token can see. For a token bound to one, that is the answer. */
    fun parseOrganizations(response: String): List<SentryOrg> {
        val array = json(response)?.asArrayOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val node = element.asObjectOrNull() ?: return@mapNotNull null
            val slug = node["slug"]?.string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SentryOrg(slug = slug, name = node["name"]?.string()?.takeIf { it.isNotBlank() } ?: slug)
        }
    }

    /**
     * Upper-cased project slugs. These are the prefixes a short id of ours can start with,
     * and the only thing standing between the transcript linker and every `UTF-8` on screen.
     */
    fun parseProjectSlugs(response: String): Set<String> {
        val array = json(response)?.asArrayOrNull() ?: return emptySet()
        return array.mapNotNullTo(LinkedHashSet()) {
            it.asObjectOrNull()?.get("slug")?.string()?.takeIf { slug -> slug.isNotBlank() }?.uppercase()
        }
    }

    /**
     * One issue out of the short-id resolution, plus the numeric group id the event fetch
     * needs. Null when the payload carries no group at all.
     */
    fun parseShortId(response: String, base: String): SentryResolution? {
        val root = json(response)?.asObjectOrNull() ?: return null
        val group = root["group"]?.asObjectOrNull() ?: return null
        val org = root["organizationSlug"]?.string()
        val ref = toRef(group, base, org) ?: return null
        val groupId = root["groupId"]?.string() ?: group["id"]?.string() ?: return null
        return SentryResolution(
            ref = ref,
            groupId = groupId,
            projectSlug = root["projectSlug"]?.string()?.uppercase(),
        )
    }

    /** Rows for the completion popup. Unparseable entries are dropped, not failed on. */
    fun parseIssues(response: String, base: String, org: String?): List<ExtIssueRef> {
        val array = json(response)?.asArrayOrNull() ?: return emptyList()
        return array.mapNotNull { toRef(it.asObjectOrNull(), base, org) }
    }

    /**
     * The prompt payload this whole extension exists for: the exception, its message, and the
     * frames — which is what a coding agent can act on, unlike a link to a page it cannot open.
     *
     * **In-app frames first, and only [FRAME_BUDGET] of them.** A production stack is dozens
     * of frames deep and mostly framework; sending all of it spends the user's context on code
     * they do not own. Null when the event carries no stack trace at all, so a caller can tell
     * "nothing to add" from "an empty trace".
     */
    fun parseEventTrace(response: String): String? {
        val root = json(response)?.asObjectOrNull() ?: return null
        val entries = root["entries"]?.asArrayOrNull() ?: return null
        val exception = entries.firstNotNullOfOrNull { entry ->
            entry.asObjectOrNull()?.takeIf { it["type"]?.string() == "exception" }?.get("data")?.asObjectOrNull()
        } ?: return null
        val value = exception["values"]?.asArrayOrNull()?.firstNotNullOfOrNull { it.asObjectOrNull() }
            ?: return null
        val header = listOfNotNull(
            value["type"]?.string()?.takeIf { it.isNotBlank() },
            value["value"]?.string()?.takeIf { it.isNotBlank() },
        ).joinToString(": ")
        val frames = value["stacktrace"]?.asObjectOrNull()?.get("frames")?.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull() }
            .orEmpty()
        if (header.isBlank() && frames.isEmpty()) return null
        // Sentry orders frames oldest-first; the crash is the last one, so read from the end.
        val ordered = frames.reversed()
        val chosen = ordered.filter { it["inApp"]?.boolean() == true }.ifEmpty { ordered }
        val lines = chosen.take(FRAME_BUDGET).mapNotNull(::frameLine)
        return (listOf(header).filter { it.isNotBlank() } + lines).joinToString("\n").takeIf { it.isNotBlank() }
    }

    /**
     * Sentry's own error shape. `detail` is what every 4xx carries; a permission failure names
     * the scope it wanted, which is the one thing the user can act on.
     */
    fun parseError(response: String): String? =
        json(response)?.asObjectOrNull()?.get("detail")?.string()?.takeIf { it.isNotBlank() }

    private fun frameLine(frame: JsonObject): String? {
        val where = frame["filename"]?.string()?.takeIf { it.isNotBlank() }
            ?: frame["absPath"]?.string()?.takeIf { it.isNotBlank() }
            ?: frame["module"]?.string()?.takeIf { it.isNotBlank() }
            ?: return null
        val line = frame["lineNo"]?.intOrNull()
        val function = frame["function"]?.string()?.takeIf { it.isNotBlank() }
        return buildString {
            append("  at ").append(where)
            if (line != null) append(':').append(line)
            if (function != null) append(" in ").append(function)
        }
    }

    private fun toRef(node: JsonObject?, base: String, org: String?): ExtIssueRef? {
        if (node == null) return null
        val shortId = node["shortId"]?.string()?.takeIf { it.isNotBlank() } ?: return null
        val status = node["status"]?.string()?.lowercase()
        val url = node["permalink"]?.string()?.takeIf { it.startsWith("http") }
            ?: node["id"]?.string()?.let { fallbackUrl(base, org, it) }
            ?: return null
        return ExtIssueRef(
            id = shortId,
            summary = node["title"]?.string().orEmpty(),
            state = status?.replaceFirstChar(Char::uppercase),
            // Sentry's terminal states. `ignored`/`archived` is muted, not fixed — treating it
            // as done would strike through an issue the user deliberately parked.
            resolved = status == "resolved",
            url = url,
            // Sentry tracks crashes; it has no pull requests of its own.
            isPullRequest = false,
        )
    }

    /** Only when the payload carried no `permalink` — and only when the org is actually known. */
    private fun fallbackUrl(base: String, org: String?, groupId: String): String? {
        if (org.isNullOrBlank()) return null
        return "${normalizeBase(base)}/organizations/${encode(org)}/issues/${encode(groupId)}/"
    }

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private fun json(response: String): JsonElement? =
        runCatching { JsonParser.parseString(response) }.getOrNull()

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asArrayOrNull(): List<JsonElement>? =
        takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonElement.string(): String? = when {
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonPrimitive && asJsonPrimitive.isNumber -> asString
        else -> null
    }

    private fun JsonElement.boolean(): Boolean? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonElement.intOrNull(): Int? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.runCatching { asInt }?.getOrNull()

    /** Enough to orient an agent; past this it is the framework's stack, not the user's. */
    private const val FRAME_BUDGET = 12
}

/** One organization the configured token can see. */
internal data class SentryOrg(val slug: String, val name: String)

/** A resolved short id: the row, plus what the event fetch and the link gate need. */
internal data class SentryResolution(
    val ref: ExtIssueRef,
    val groupId: String,
    val projectSlug: String?,
)

/** What one credential turned out to belong to. */
internal data class SentryOrgInfo(
    val slug: String,
    val name: String,
    /**
     * The install this organization was found on. Carried here rather than re-resolved, because
     * the one reader that needs it — the transcript linker — runs on the EDT per rendered
     * message and cannot resolve a secret. Without it a self-hosted user's links would all point
     * at sentry.io, which is a 404 on a host they may not even have an account on.
     */
    val base: String = SentryQueries.DEFAULT_BASE,
    /** Upper-cased project slugs — the prefixes a short id of ours can actually start with. */
    val projectSlugs: Set<String>,
    /** True when the token saw several organizations and [SentryQueries.ORG_NAME] chose one. */
    val chosenByEnv: Boolean = false,
)
