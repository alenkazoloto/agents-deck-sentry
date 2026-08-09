package dev.agentsdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half: every path this extension can build, and every payload it can be handed.
 *
 * No network and no IDE, so the shapes are pinned against the documented ones rather than
 * against whatever a live organization happened to return the day this was written.
 */
class SentryQueriesTest {

    @Test
    fun `a short id is a project slug and a base-32 counter, and the slug may hold hyphens`() {
        assertTrue(SentryQueries.isShortId("BACKEND-1A2B"))
        assertTrue("a project slug with a hyphen is still one project", SentryQueries.isShortId("MY-BACKEND-4"))
        assertTrue("the chat is not Sentry — case comes back canonical", SentryQueries.isShortId("backend-1a2b"))
        assertFalse("a bare number is YouTrack's or GitHub's, never ours", SentryQueries.isShortId("42"))
        assertFalse(SentryQueries.isShortId(""))
        assertFalse(SentryQueries.isShortId("BACKEND"))
    }

    /**
     * The split is the *last* hyphen, and this is the case that proves it: split at the first,
     * `MY-BACKEND-4` would be gated against a project called `MY`, which does not exist, and
     * every link in a multi-word project would silently stop working.
     */
    @Test
    fun `the project slug is everything before the last hyphen`() {
        assertEquals("BACKEND", SentryQueries.projectSlug("BACKEND-1A2B"))
        assertEquals("MY-BACKEND", SentryQueries.projectSlug("MY-BACKEND-4"))
        assertEquals("BACKEND", SentryQueries.projectSlug("backend-1a2b"))
        assertNull(SentryQueries.projectSlug("42"))
    }

    @Test
    fun `the base url tolerates what a user actually pastes`() {
        assertEquals("https://sentry.io", SentryQueries.normalizeBase(null))
        assertEquals("https://sentry.io", SentryQueries.normalizeBase("   "))
        assertEquals("https://sentry.example.com", SentryQueries.normalizeBase("https://sentry.example.com/"))
        assertEquals("https://sentry.example.com", SentryQueries.normalizeBase("sentry.example.com"))
        assertEquals(
            "a pasted API root would otherwise build every path twice",
            "https://sentry.example.com",
            SentryQueries.normalizeBase("https://sentry.example.com/api/0/"),
        )
    }

    @Test
    fun `the paths are the documented ones`() {
        assertEquals("https://sentry.io/api/0/organizations/", SentryQueries.organizationsPath("https://sentry.io"))
        assertEquals(
            "https://sentry.io/api/0/organizations/acme/shortids/BACKEND-1A2B/",
            SentryQueries.shortIdPath("https://sentry.io", "acme", "backend-1a2b"),
        )
        assertEquals(
            "https://sentry.io/api/0/organizations/acme/issues/12/events/latest/",
            SentryQueries.latestEventPath("https://sentry.io", "acme", "12"),
        )
        assertTrue(SentryQueries.projectsPath("https://sentry.io", "acme").endsWith("/projects/?per_page=100"))
    }

    @Test
    fun `a search path carries the typed text, a bounded limit and the widest documented window`() {
        val path = SentryQueries.issuesPath("https://sentry.io", "acme", "login fails", 500)
        assertTrue(path.startsWith("https://sentry.io/api/0/organizations/acme/issues/?"))
        assertTrue("spaces must survive as an encoded query", path.contains("query=login%20fails"))
        assertTrue("the endpoint's own maximum is 100", path.contains("limit=100"))
        assertTrue("the endpoint defaults to a fortnight, which reads as 'no results'", path.contains("statsPeriod=90d"))
    }

    @Test
    fun `an organization slug with a slash in it cannot escape its path segment`() {
        assertTrue(SentryQueries.shortIdPath("https://sentry.io", "a/../b", "X-1").contains("a%2F..%2Fb"))
    }

    @Test
    fun `a short-id resolution yields the row, the group id and the project`() {
        val resolution = SentryQueries.parseShortId(SHORT_ID_RESPONSE, "https://sentry.io")!!
        assertEquals("BACKEND-1A2B", resolution.ref.id)
        assertEquals("TypeError: undefined is not a function", resolution.ref.summary)
        assertEquals("https://acme.sentry.io/issues/98765/", resolution.ref.url)
        assertEquals("Unresolved", resolution.ref.state)
        assertFalse(resolution.ref.resolved)
        assertEquals("98765", resolution.groupId)
        assertEquals("BACKEND", resolution.projectSlug)
    }

    /**
     * `ignored` is muted, not fixed. Treating it as done would strike an issue through in the
     * chip and the popup that the user deliberately parked and still wants to work on.
     */
    @Test
    fun `only resolved is resolved`() {
        assertTrue(refWithStatus("resolved").resolved)
        assertFalse(refWithStatus("ignored").resolved)
        assertFalse(refWithStatus("unresolved").resolved)
    }

    @Test
    fun `a list of issues drops what it cannot read rather than failing the lot`() {
        val rows = SentryQueries.parseIssues(
            """[{"shortId":"BACKEND-1","title":"a","permalink":"https://acme.sentry.io/issues/1/"},
               {"title":"no short id"},
               {"shortId":"BACKEND-2","title":"b","permalink":"https://acme.sentry.io/issues/2/"}]""",
            "https://sentry.io",
            "acme",
        )
        assertEquals(listOf("BACKEND-1", "BACKEND-2"), rows.map { it.id })
    }

    /** A row with no permalink is still a row — the org is known, so the URL is derivable. */
    @Test
    fun `a missing permalink falls back to a url built from the organization`() {
        val row = SentryQueries.parseIssues(
            """[{"shortId":"BACKEND-1","title":"a","id":"77"}]""",
            "https://sentry.io",
            "acme",
        ).single()
        assertEquals("https://sentry.io/organizations/acme/issues/77/", row.url)
    }

    /** …but never from an organization we do not have: a link needs a target, not a shape. */
    @Test
    fun `with no organization and no permalink there is no row at all`() {
        assertTrue(
            SentryQueries.parseIssues("""[{"shortId":"BACKEND-1","title":"a","id":"77"}]""", "https://sentry.io", null)
                .isEmpty(),
        )
    }

    @Test
    fun `the stack trace is the exception, then the frames, crash-first`() {
        val trace = SentryQueries.parseEventTrace(EVENT_RESPONSE)!!
        val lines = trace.lines()
        assertEquals("TypeError: undefined is not a function", lines[0])
        assertEquals(
            "Sentry sends frames oldest-first; the crash is the one worth reading first",
            "  at src/app.ts:42 in handleClick",
            lines[1],
        )
        assertEquals("  at src/main.ts:7 in start", lines[2])
        assertEquals("only the in-app frames — the rest is the framework's stack", 3, lines.size)
    }

    /** Every frame being framework code is not "no trace" — it is all the trace there is. */
    @Test
    fun `an event with no in-app frames falls back to the whole stack`() {
        val trace = SentryQueries.parseEventTrace(
            """{"entries":[{"type":"exception","data":{"values":[{"type":"E","value":"v",
               "stacktrace":{"frames":[{"filename":"vendor.js","lineNo":1,"inApp":false}]}}]}}]}""",
        )!!
        assertTrue(trace.contains("vendor.js:1"))
    }

    @Test
    fun `every parser answers rather than throws on garbage`() {
        for (payload in listOf("", "not json", "[]", "{}", """{"group":null}""", "null")) {
            assertNull("parseShortId($payload)", SentryQueries.parseShortId(payload, "https://sentry.io"))
            assertNull("parseEventTrace($payload)", SentryQueries.parseEventTrace(payload))
            assertTrue("parseIssues($payload)", SentryQueries.parseIssues(payload, "https://sentry.io", "acme").isEmpty())
            assertTrue("parseOrganizations($payload)", SentryQueries.parseOrganizations(payload).isEmpty())
            assertTrue("parseProjectSlugs($payload)", SentryQueries.parseProjectSlugs(payload).isEmpty())
        }
    }

    @Test
    fun `sentry's own error field is read, not dropped`() {
        assertEquals(
            "You do not have permission to perform this action.",
            SentryQueries.parseError("""{"detail":"You do not have permission to perform this action."}"""),
        )
        assertNull(SentryQueries.parseError("""{"detail":""}"""))
        assertNull("a good answer must not look like an error", SentryQueries.parseError("""[{"slug":"acme"}]"""))
    }

    @Test
    fun `project slugs come back upper-cased, because a short id is`() {
        assertEquals(setOf("BACKEND", "MY-FRONTEND"), SentryQueries.parseProjectSlugs(PROJECTS_RESPONSE))
    }

    @Test
    fun `the pasted-link pattern captures the numeric group id`() {
        assertEquals(
            "98765",
            SentryQueries.URL_PATTERN.find("https://acme.sentry.io/issues/98765/")!!.groupValues[1],
        )
        assertEquals(
            "12",
            SentryQueries.URL_PATTERN.find("https://sentry.io/organizations/acme/issues/12/events/")!!.groupValues[1],
        )
    }

    private fun refWithStatus(status: String) = SentryQueries.parseIssues(
        """[{"shortId":"BACKEND-1","title":"a","status":"$status","permalink":"https://acme.sentry.io/issues/1/"}]""",
        "https://sentry.io",
        "acme",
    ).single()

    private companion object {
        const val SHORT_ID_RESPONSE = """
            {"shortId":"BACKEND-1A2B","groupId":"98765","organizationSlug":"acme","projectSlug":"backend",
             "group":{"id":"98765","shortId":"BACKEND-1A2B","title":"TypeError: undefined is not a function",
                      "culprit":"handleClick(src/app.ts)","status":"unresolved","level":"error",
                      "permalink":"https://acme.sentry.io/issues/98765/"}}
        """

        const val EVENT_RESPONSE = """
            {"id":"ev1","eventID":"ev1","entries":[
              {"type":"breadcrumbs","data":{"values":[]}},
              {"type":"exception","data":{"values":[
                {"type":"TypeError","value":"undefined is not a function","stacktrace":{"frames":[
                  {"filename":"node_modules/react/index.js","lineNo":900,"function":"render","inApp":false},
                  {"filename":"src/main.ts","lineNo":7,"function":"start","inApp":true},
                  {"filename":"src/app.ts","lineNo":42,"function":"handleClick","inApp":true}]}}]}}]}
        """

        const val PROJECTS_RESPONSE = """[{"slug":"backend","name":"Backend"},{"slug":"my-frontend"},{"name":"x"}]"""
    }
}
