package dev.agentsdeck.sentry

import com.github.claudeagents.api.ExtIssueRef
import com.github.claudeagents.api.ExtUrlPattern
import com.github.claudeagents.api.ExternalIssueProvider
import com.github.claudeagents.api.IssueProviderContext
import com.github.claudeagents.api.IssueProviderFactory
import com.intellij.openapi.application.ApplicationManager

/**
 * `dev.agentsdeck.jetbrains.issueProvider` — Sentry behind the chat's `#` completion, its
 * chips, its hover cards and its prompt enrichment.
 *
 * **No token, no contribution.** An empty list is the api's own way of saying "not
 * configured", and it is what keeps a plugin that is installed but unconfigured invisible:
 * the popup is then exactly the popup the host ships.
 *
 * The credential is a Sentry **auth token** resolved by name through the host's own precedence
 * (process environment, then a configured stdio MCP server's `env` block). Nothing is stored
 * by this extension — there is no settings field to type a token into on purpose, so a secret
 * cannot end up in this plugin's state.
 */
class SentryProviderFactory : IssueProviderFactory {

    override fun create(context: IssueProviderContext): List<ExternalIssueProvider> {
        val token = context.resolveSecret(SentryQueries.TOKEN_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val base = SentryQueries.normalizeBase(context.resolveSecret(SentryQueries.URL_NAME))
        val preferredOrg = context.resolveSecret(SentryQueries.ORG_NAME)?.trim()?.takeIf { it.isNotEmpty() }
        val provider = SentryIssueProvider(token, base, preferredOrg)

        // Fire-and-forget: the organization probe is what every lookup, the transcript linker
        // and the settings line read, and none of them may block. It must not be awaited here —
        // the popup this call is answering would then wait on a round trip. `ensure` returns
        // immediately once this session has an answer for this credential.
        ApplicationManager.getApplication()?.executeOnPooledThread {
            SentryWorkspace.ensure(token, base, preferredOrg, provider.client)
        }
        return listOf(provider)
    }
}

/**
 * One configured Sentry organization.
 *
 * Blocking calls in `suspend` bodies on purpose: the extension point states that every method
 * is called on a background thread and that blocking is expected, so wrapping them in a
 * dispatcher would buy a thread hop and nothing else.
 *
 * Nothing here throws. The api's contract is that a failure costs this extension its
 * contribution rather than the user their completion popup, and the message that would have
 * been a stack trace goes to [SentryWorkspace] for the settings section to say out loud.
 */
internal class SentryIssueProvider(
    private val token: String,
    private val base: String = SentryQueries.DEFAULT_BASE,
    private val preferredOrg: String? = null,
    transport: SentryTransport = HttpSentryTransport(token),
) : ExternalIssueProvider {

    internal val client = SentryClient(transport, base)

    override val id: String = PROVIDER_ID

    /**
     * `BACKEND-1A2B` yes, `42` no.
     *
     * **The shape, not the project list.** A slug-gated claim would answer false for the whole
     * window before the organization probe lands — which is exactly the token a user types in
     * the first seconds after opening the IDE — and this provider would then never be asked,
     * so it would never learn. Claiming a shape is not winning it: the host resolves a
     * contested token against each claimant in turn and the negative is cached, which is what
     * makes an over-broad claim cheap. The *link* is the surface that gates on real slugs,
     * because a link is painted with no user intent behind it.
     */
    override fun canResolve(token: String): Boolean = SentryQueries.isShortId(token)

    /**
     * Rows for the popup. A typed full short id is looked up directly — the search endpoint
     * matches event *text*, so `BACKEND-1A2B` would otherwise find only issues that mention it.
     */
    override suspend fun search(text: String, limit: Int): List<ExtIssueRef> {
        val org = organization() ?: return emptyList()
        val typed = text.trim()
        if (SentryQueries.isShortId(typed)) return listOfNotNull(byId(typed))
        return client.search(org, typed, limit).value
    }

    override suspend fun byId(token: String): ExtIssueRef? {
        val org = organization() ?: return null
        return client.issue(org, token).value
    }

    /**
     * `https://<org>.sentry.io/issues/<id>/` in a pasted draft.
     *
     * The captured group is the *numeric group id*, not a short id — which is what the URL
     * carries — so it round-trips through [byId] only where Sentry's own short-id endpoint
     * accepts it. It does: `shortids/` resolves either form.
     */
    override fun urlPatterns(): List<ExtUrlPattern> = listOf(ExtUrlPattern(SentryQueries.URL_PATTERN))

    /**
     * The organization slug, or null while nobody knows it.
     *
     * Blocking on purpose here, unlike the fire-and-forget in the factory: this runs on the
     * background thread the api promises, and a lookup with no organization has nothing to
     * ask. `ensure` is a no-op once the session has an answer.
     */
    private fun organization(): String? {
        SentryWorkspace.ensure(token, base, preferredOrg, client)
        return SentryWorkspace.snapshot()?.slug
    }

    companion object {
        const val PROVIDER_ID = "sentry"
    }
}
