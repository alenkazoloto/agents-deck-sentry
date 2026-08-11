package dev.agentsdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two surfaces that are decisions rather than Swing: what becomes a link, and what the
 * settings section says. Both are pinned here without a `Project` and without a settings page,
 * which is otherwise the only thing standing between these rules and a test.
 */
class SentrySurfacesTest {

    private val acme = SentryOrgInfo("acme", "Acme", projectSlugs = setOf("BACKEND", "MY-FRONTEND"))

    @Test
    fun `a short id in a known project becomes a link`() {
        assertEquals(
            "https://sentry.io/organizations/acme/issues/?query=BACKEND-1A2B",
            SentryLinks.target(acme, "BACKEND-1A2B"),
        )
    }

    /**
     * The gate that carries this extension. A Sentry counter is base-32, not decimal, so the
     * shape fits every one of these — and a link aimed at an organization with no such project
     * is a 404 dressed as an answer.
     */
    @Test
    fun `a shape we do not own stays plain text`() {
        for (token in listOf("UTF-8", "SHA-256", "ISO-8601", "RFC-822", "CVE-2024-1234", "GPT-4O")) {
            assertNull("$token is not ours", SentryLinks.target(acme, token))
        }
    }

    @Test
    fun `a project slug with a hyphen links, because the split is the last hyphen`() {
        assertTrue(SentryLinks.target(acme, "MY-FRONTEND-7")!!.endsWith("query=MY-FRONTEND-7"))
    }

    /** Before the probe there are no slugs, so nothing linkifies — never everything. */
    @Test
    fun `an unprobed session links nothing at all`() {
        assertNull(SentryLinks.target(null, "BACKEND-1A2B"))
        assertNull(SentryLinks.target(acme.copy(projectSlugs = emptySet()), "BACKEND-1A2B"))
    }

    @Test
    fun `the pattern matches the canonical form only`() {
        assertTrue(SentryLinks.SHORT_ID.matches("BACKEND-1A2B"))
        assertTrue(SentryLinks.SHORT_ID.matches("MY-BACKEND-4"))
        assertTrue("agent output writes the canonical id; my-backend-4 is a path", !SentryLinks.SHORT_ID.matches("my-backend-4"))
    }

    /**
     * The install travels on the organization, because the linker runs on the EDT and cannot
     * resolve `SENTRY_URL` itself. A default here sends every self-hosted user's links to
     * sentry.io — a 404 on a host they may not even have an account on.
     */
    @Test
    fun `a self-hosted link stays on the self-hosted install`() {
        assertTrue(
            SentryLinks.target(acme.copy(base = "https://sentry.example.com/"), "BACKEND-1")!!
                .startsWith("https://sentry.example.com/organizations/acme/"),
        )
    }

    /** And the probe is what puts it there — not a constant the linker could have guessed. */
    @Test
    fun `the organization carries the install it was found on`() {
        val info = SentryClient(
            { url ->
                when {
                    url.endsWith("/organizations/") -> """[{"slug":"acme","name":"Acme"}]"""
                    else -> """[{"slug":"backend"}]"""
                }
            },
            "https://sentry.example.com/",
        ).organization(null).value!!
        assertEquals("https://sentry.example.com", info.base)
        assertTrue(SentryLinks.target(info, "BACKEND-1")!!.startsWith("https://sentry.example.com/"))
    }

    /** "Not connected" before anyone has looked would be a guess wearing the grammar of a fact. */
    @Test
    fun `the collapsed line says nothing while nothing is known`() {
        assertNull(SentryStatus.collapsed(null, null, null))
        assertEquals("No auth token", SentryStatus.collapsed(null, null, tokenPresent = false))
        assertEquals("Auth token found", SentryStatus.collapsed(null, null, tokenPresent = true))
        assertEquals("Acme", SentryStatus.collapsed(acme, null, tokenPresent = true))
    }

    /**
     * A failure is stated, not swallowed. A bad token and a schema drift both produce an empty
     * popup; only this line tells them apart, and only one of them is the user's to fix.
     */
    @Test
    fun `a failure is stated, not swallowed`() {
        assertEquals("Invalid token", SentryStatus.collapsed(null, "Invalid token", tokenPresent = true))
        assertEquals("Invalid token", SentryStatus.expanded(null, "Invalid token", tokenPresent = true))
        assertEquals(
            "No auth token — Sentry issues will not be offered in chat.",
            SentryStatus.expanded(null, null, tokenPresent = false),
        )
    }

    /**
     * The no-token state offers two links and names neither the environment variable nor the
     * three places this extension looks for one.
     *
     * The reported version read `No SENTRY_AUTH_TOKEN` above a paragraph naming all of them —
     * our own plumbing, described to a reader who wanted a route — and a second paragraph about
     * `SENTRY_ORG` and `SENTRY_URL`, which are a *tuning* for a token that already works. The
     * variable is the setup form's to print, on the field they paste into.
     */
    @Test
    fun `no token names both routes and neither the variable nor where we look for it`() {
        val state = SentryStatus.expanded(null, null, tokenPresent = false)
        assertFalse("the variable belongs on the setup form's field: $state", state.contains(SentryQueries.TOKEN_NAME))

        val whole = "$state $CREATE_TOKEN_ACTION $SETUP_ACTION"
        assertTrue("a requirement with no route is a dead end: $whole", whole.contains("Create an auth token in Sentry"))
        assertTrue("and a token nobody can hand over is another: $whole", whole.contains("Add the token to Agents Deck"))
        listOf("env block", "shell environment", "stdio MCP", "resolveSecret", SentryQueries.ORG_NAME).forEach {
            assertFalse("not a reader with no token's business: $it", whole.contains(it))
        }
    }

    /**
     * The order is the instruction: mint one at Sentry, then hand it to this IDE. Offered the
     * other way round, the first thing a reader with nothing is told is to paste what they have
     * not got — and [WHERE_TO_ADD] is the sentence an older host gets in place of the second
     * link, because a link that answers false is worse than the page it replaced.
     */
    @Test
    fun `the two routes are distinct, ordered, and have a fallback for an older host`() {
        assertNotEquals(CREATE_TOKEN_ACTION, SETUP_ACTION)
        assertTrue(CREATE_TOKEN_ACTION, CREATE_TOKEN_ACTION.contains("Sentry"))
        assertTrue(SETUP_ACTION, SETUP_ACTION.contains("Agents Deck"))
        assertTrue(WHERE_TO_ADD, WHERE_TO_ADD.contains("Settings"))
        assertFalse("an action a user clicks may not also name the page it opens", SETUP_ACTION.contains("Settings"))
    }

    @Test
    fun `the expanded line counts the projects, because links are gated on them`() {
        assertEquals("Connected · Acme · 2 projects", SentryStatus.expanded(acme, null, tokenPresent = true))
        assertEquals(
            "Connected · Acme · 1 project",
            SentryStatus.expanded(acme.copy(projectSlugs = setOf("BACKEND")), null, tokenPresent = true),
        )
        assertEquals(
            "an organization with no visible projects is why a transcript linkified nothing",
            "Connected · Acme · no projects visible to this token",
            SentryStatus.expanded(acme.copy(projectSlugs = emptySet()), null, tokenPresent = true),
        )
    }

    @Test
    fun `an organization chosen by the variable says so, so a wrong one is visible`() {
        assertTrue(
            SentryStatus.expanded(acme.copy(chosenByEnv = true), null, tokenPresent = true)
                .endsWith("chosen by SENTRY_ORG"),
        )
    }

    /**
     * Precedence, and it is not cosmetic: three writers fill these fields at three different
     * moments — the startup activity sets presence with no network, the probe sets the
     * organization, and a failed probe sets the error. A known organization is the newest and
     * most specific of the three, so a stale "No token" or a since-fixed error must not
     * outrank it.
     */
    @Test
    fun `a known organization outranks both the presence flag and a stale error`() {
        assertEquals("Acme", SentryStatus.collapsed(acme, "Invalid token", tokenPresent = false))
        assertEquals("Connected · Acme · 2 projects", SentryStatus.expanded(acme, "Invalid token", tokenPresent = true))
    }
}
