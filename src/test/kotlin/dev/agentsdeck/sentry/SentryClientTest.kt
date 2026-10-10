package dev.agentsdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure shapes a caller must not be able to tell apart by catching something: no answer
 * at all, Sentry's own `detail`, and an answer in a shape this build does not read. All of them
 * return a value *and* a sentence, and none of them throws.
 *
 * Plus the one decision that only lives here: which organization a token is taken to mean.
 */
class SentryClientTest {

    @Test
    fun `no response at all is offline, not empty`() {
        val client = SentryClient({ null })
        assertEquals("Could not reach sentry.io.", client.search("acme", "x", 5).error)
        assertTrue(client.search("acme", "x", 5).value.isEmpty())
        assertEquals("Could not reach sentry.io.", client.issue("acme", "BACKEND-1").error)
        assertNull(client.issue("acme", "BACKEND-1").value)
    }

    /** A self-hosted install must not be reported as an outage at sentry.io. */
    @Test
    fun `the offline sentence names the host actually being called`() {
        val client = SentryClient({ null }, "https://sentry.example.com")
        assertEquals("Could not reach sentry.example.com.", client.organization(null).error)
    }

    @Test
    fun `sentry's detail travels beside the empty answer`() {
        val client = SentryClient({ """{"detail":"Invalid token"}""" })
        val answer = client.search("acme", "x", 5)
        assertTrue(answer.value.isEmpty())
        assertEquals("Invalid token", answer.error)
    }

    @Test
    fun `a token bound to one organization needs no configuration at all`() {
        val client = SentryClient(routes())
        val info = client.organization(null).value!!
        assertEquals("acme", info.slug)
        assertEquals("Acme", info.name)
        assertEquals(setOf("BACKEND"), info.projectSlugs)
        assertTrue("nothing chose it — the token is bound to it", !info.chosenByEnv)
    }

    /**
     * The case the plan called out as Jira's problem and this extension's only multi-value
     * one: several organizations and no `SENTRY_ORG` has no correct answer, so it must be a
     * stated requirement rather than the first slug in the list.
     */
    @Test
    fun `several organizations and no SENTRY_ORG is a requirement, not a guess`() {
        val client = SentryClient(routes(orgs = """[{"slug":"acme","name":"Acme"},{"slug":"beta","name":"Beta"}]"""))
        val answer = client.organization(null)
        assertNull(answer.value)
        assertTrue(answer.error!!.contains("SENTRY_ORG"))
        assertTrue("the user cannot act on a requirement that hides the options", answer.error!!.contains("acme, beta"))
    }

    @Test
    fun `SENTRY_ORG picks one of several, case-insensitively`() {
        val client = SentryClient(routes(orgs = """[{"slug":"acme","name":"Acme"},{"slug":"beta","name":"Beta"}]"""))
        val info = client.organization("BETA").value!!
        assertEquals("beta", info.slug)
        assertTrue(info.chosenByEnv)
    }

    @Test
    fun `a token that can see nothing says so`() {
        assertEquals(
            "This token can see no Sentry organization.",
            SentryClient(routes(orgs = "[]")).organization(null).error,
        )
    }

    /**
     * The whole reason this extension beats a link: the row the agent receives carries the
     * stack trace, and that costs the *second* request this is the only caller of.
     */
    @Test
    fun `a resolved short id carries the latest event's trace as its description`() {
        val calls = mutableListOf<String>()
        val client = SentryClient(routes(record = calls))
        val ref = client.issue("acme", "backend-1a2b").value!!
        assertEquals("BACKEND-1A2B", ref.id)
        assertTrue(ref.description!!.contains("TypeError: boom"))
        assertTrue(ref.description!!.contains("src/app.ts:42"))
        assertEquals(2, calls.size)
        assertTrue(calls[0].contains("/shortids/"))
        assertTrue(calls[1].contains("/events/latest/"))
    }

    /** An issue whose events have aged out of retention is still the issue the user asked for. */
    @Test
    fun `a missing trace costs the description, never the row`() {
        val client = SentryClient(routes(event = null))
        val ref = client.issue("acme", "BACKEND-1A2B").value!!
        assertEquals("BACKEND-1A2B", ref.id)
        assertNull(ref.description)
    }

    /** N rows must not be N round trips: the trace is fetched for a named issue only. */
    @Test
    fun `a search never fetches an event`() {
        val calls = mutableListOf<String>()
        SentryClient(routes(record = calls)).search("acme", "boom", 10)
        assertEquals(1, calls.size)
        assertTrue(calls.single().contains("/issues/?"))
    }

    private fun routes(
        orgs: String = """[{"slug":"acme","name":"Acme"}]""",
        event: String? = EVENT,
        record: MutableList<String>? = null,
    ) = SentryTransport { url ->
        record?.add(url)
        when {
            url.endsWith("/organizations/") -> orgs
            url.contains("/projects/") -> """[{"slug":"backend"}]"""
            url.contains("/shortids/") -> SHORT_ID
            url.contains("/events/latest/") -> event
            url.contains("/issues/?") -> """[{"shortId":"BACKEND-9","title":"boom","permalink":"https://a.io/issues/9/"}]"""
            else -> null
        }
    }

    private companion object {
        const val SHORT_ID = """
            {"shortId":"BACKEND-1A2B","groupId":"98765","organizationSlug":"acme","projectSlug":"backend",
             "group":{"id":"98765","shortId":"BACKEND-1A2B","title":"TypeError: boom","status":"unresolved",
                      "permalink":"https://acme.sentry.io/issues/98765/"}}
        """

        const val EVENT = """
            {"entries":[{"type":"exception","data":{"values":[{"type":"TypeError","value":"boom",
             "stacktrace":{"frames":[{"filename":"src/app.ts","lineNo":42,"function":"handleClick","inApp":true}]}}]}}]}
        """
    }
}
