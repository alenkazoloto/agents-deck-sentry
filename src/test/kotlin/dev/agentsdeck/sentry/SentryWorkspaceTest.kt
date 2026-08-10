package dev.agentsdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The session memo: how often a token is probed, and what makes a cached answer stop applying.
 *
 * The rules here are the ones a live workspace would never fail loudly on — a cache keyed too
 * loosely just answers for the wrong install, and a probe with no back-off just costs a round
 * trip per keystroke. Neither shows up in a screenshot.
 */
class SentryWorkspaceTest {

    @Before
    fun clearSessionState() = SentryWorkspace.reset()

    private var probes = 0

    private fun client(base: String = SentryQueries.DEFAULT_BASE, answer: String = ORGS) =
        SentryClient(
            SentryTransport { url ->
                if (url.endsWith("/organizations/")) probes++
                if (url.contains("/projects/")) """[{"slug":"backend"}]""" else answer
            },
            base,
        )

    @Test
    fun `one probe answers for the whole session`() {
        repeat(5) { SentryWorkspace.ensure("tok", SentryQueries.DEFAULT_BASE, null, client()) }
        assertEquals(1, probes)
        assertEquals("acme", SentryWorkspace.snapshot()?.slug)
    }

    /**
     * A rotated token belongs to a different organization. A memo keyed by nothing would keep
     * linkifying short ids into the workspace the user just left.
     */
    @Test
    fun `a different token is a different answer`() {
        SentryWorkspace.ensure("tok-a", SentryQueries.DEFAULT_BASE, null, client())
        SentryWorkspace.ensure("tok-b", SentryQueries.DEFAULT_BASE, null, client())
        assertEquals(2, probes)
    }

    /** The same token against a self-hosted host is a different install entirely. */
    @Test
    fun `the same token at a different base is a different answer`() {
        SentryWorkspace.ensure("tok", SentryQueries.DEFAULT_BASE, null, client())
        SentryWorkspace.ensure("tok", "https://sentry.example.com", null, client("https://sentry.example.com"))
        assertEquals(2, probes)
    }

    @Test
    fun `a failure is remembered, so a bad token is not one round trip per keystroke`() {
        val failing = client(answer = """{"detail":"Invalid token"}""")
        SentryWorkspace.ensure("tok", SentryQueries.DEFAULT_BASE, null, failing, nowNanos = 0)
        SentryWorkspace.ensure("tok", SentryQueries.DEFAULT_BASE, null, failing, nowNanos = 60_000_000_000)
        assertEquals(1, probes)
        assertEquals("Invalid token", SentryWorkspace.error())
        assertNull(SentryWorkspace.snapshot())
    }

    /** …but "the token is wrong" stops being true the moment the user fixes it. */
    @Test
    fun `a remembered failure expires`() {
        val failing = client(answer = """{"detail":"Invalid token"}""")
        SentryWorkspace.ensure("tok", SentryQueries.DEFAULT_BASE, null, failing, nowNanos = 0)
        SentryWorkspace.ensure("tok", SentryQueries.DEFAULT_BASE, null, failing, nowNanos = 6L * 60 * 1_000_000_000)
        assertEquals(2, probes)
    }

    /** Cheap, no network, and the only thing the settings line can say before first use. */
    @Test
    fun `token presence is a separate question from the organization`() {
        assertNull(SentryWorkspace.tokenPresent())
        SentryWorkspace.rememberTokenPresence(false)
        assertEquals(false, SentryWorkspace.tokenPresent())
        assertNull("presence is not a probe", SentryWorkspace.snapshot())
    }

    private companion object {
        const val ORGS = """[{"slug":"acme","name":"Acme"}]"""
    }
}
