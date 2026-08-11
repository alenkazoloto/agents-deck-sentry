package dev.agentsdeck.sentry

import com.github.claudeagents.api.IssueProviderContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The contributed provider itself — what it claims, what it asks for, and what it does when
 * nothing is configured.
 */
class SentryProviderTest {

    @Before
    fun clearSessionState() = SentryWorkspace.reset()

    /** Every URL the provider fetched, in order. */
    private val fetched = mutableListOf<String>()

    private fun provider(
        base: String = SentryQueries.DEFAULT_BASE,
        org: String? = null,
        response: (String) -> String?,
    ) = SentryIssueProvider("tok-1", base, org, SentryTransport { url -> fetched += url; response(url) })

    @Test
    fun `no token means no contribution at all`() {
        val factory = SentryProviderFactory()
        assertTrue(factory.create(context(null)).isEmpty())
        assertTrue("a blank variable is not a credential", factory.create(context("   ")).isEmpty())
    }

    @Test
    fun `a token contributes exactly one provider, under the id the host reserves for us`() {
        val contributed = SentryProviderFactory().create(context("sntrys_abc"))
        assertEquals(1, contributed.size)
        assertEquals("sentry", contributed.single().id)
    }

    /**
     * The shape, not the project list — and this is the assertion that pins why. A slug-gated
     * claim answers false for the whole window before the organization probe lands, so the
     * provider would never be asked and would therefore never learn.
     */
    @Test
    fun `the provider claims the short-id shape before it knows a single project`() {
        val provider = provider { null }
        assertNull("nothing has been probed", SentryWorkspace.snapshot())
        assertTrue(provider.canResolve("BACKEND-1A2B"))
        assertTrue(provider.canResolve("MY-BACKEND-4"))
        assertFalse("a bare number is YouTrack's or GitHub's, never ours", provider.canResolve("42"))
        assertFalse(provider.canResolve(""))
    }

    @Test
    fun `a typed short id is resolved, not text-searched`() = runBlocking {
        val rows = provider(response = ::routes).search("BACKEND-1A2B", 10)
        assertEquals(listOf("BACKEND-1A2B"), rows.map { it.id })
        assertTrue("the text search matches event bodies, not ids", fetched.any { it.contains("/shortids/") })
        assertFalse(fetched.any { it.contains("/issues/?") })
    }

    @Test
    fun `free text goes to the issue search`() = runBlocking {
        assertEquals(listOf("BACKEND-9"), provider(response = ::routes).search("boom", 10).map { it.id })
        assertTrue(fetched.any { it.contains("/issues/?query=boom") })
    }

    /**
     * The organization is a precondition, not an error: with none resolved there is nothing to
     * put in the path, so the popup gets the empty list the api asks for rather than a request
     * built around a null.
     */
    @Test
    fun `an unresolvable organization costs the popup nothing`() = runBlocking {
        val provider = provider { """{"detail":"Invalid token"}""" }
        assertTrue(provider.search("boom", 10).isEmpty())
        assertNull(provider.byId("BACKEND-1"))
        assertFalse("no organization means no issue path was ever built", fetched.any { it.contains("/issues/") })
    }

    @Test
    fun `the organization is probed once, not once per lookup`() = runBlocking {
        val provider = provider(response = ::routes)
        provider.search("boom", 10)
        provider.search("crash", 10)
        assertEquals(1, fetched.count { it.endsWith("/organizations/") })
    }

    /** A self-hosted install must reach its own host, not sentry.io. */
    @Test
    fun `SENTRY_URL moves every path to the configured install`() = runBlocking {
        provider(base = "https://sentry.example.com", response = ::routes).search("boom", 10)
        assertTrue(fetched.isNotEmpty())
        assertTrue(fetched.all { it.startsWith("https://sentry.example.com/") })
    }

    @Test
    fun `the pasted-link pattern captures a token the host can resolve`() {
        val pattern = provider { null }.urlPatterns().single()
        assertEquals("98765", pattern.regex.find("https://acme.sentry.io/issues/98765/")!!.groupValues[1])
    }

    private fun routes(url: String): String? = when {
        url.endsWith("/organizations/") -> """[{"slug":"acme","name":"Acme"}]"""
        url.contains("/projects/") -> """[{"slug":"backend"}]"""
        url.contains("/shortids/") ->
            """{"groupId":"1","organizationSlug":"acme","group":{"id":"1","shortId":"BACKEND-1A2B",
               "title":"boom","permalink":"https://acme.sentry.io/issues/1/"}}"""
        url.contains("/events/latest/") -> "{}"
        url.contains("/issues/?") -> """[{"shortId":"BACKEND-9","title":"boom","permalink":"https://a.io/issues/9/"}]"""
        else -> null
    }

    private fun context(secret: String?) = object : IssueProviderContext {
        override val projectDir: String? = "/tmp/project"
        override fun resolveSecret(name: String): String? =
            secret.takeIf { name == SentryQueries.TOKEN_NAME }
    }
}
