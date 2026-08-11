package dev.agentsdeck.sentry

import com.github.claudeagents.api.ClaudeAgentsApi
import com.github.claudeagents.api.SectionText
import com.github.claudeagents.api.SettingsSectionContributor
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings › Connections › Integrations › **Sentry** — which token is in force, which
 * organization it belongs to, and what to do when there is none.
 *
 * **Always available**, unlike the Tessl section's project gate: this extension is installed
 * on purpose and does exactly one thing, so a user who has it and no token needs somewhere to
 * be told which variable to set. Hiding the section would hide the only instruction.
 */
class SentrySettingsSection : SettingsSectionContributor {

    override val id = "sentry.connection"

    override val title = "Sentry"

    /**
     * EDT, every rebuild — fields only, never a resolution.
     *
     * Silent while nothing is known: the startup activity answers the token question without a
     * network call, and the organization behind the token is filled by the first real use.
     * Saying "Not connected" before either has happened would be a guess in the grammar of a
     * fact.
     */
    override fun statusLine(project: Project): String? =
        SentryStatus.collapsed(SentryWorkspace.snapshot(), SentryWorkspace.error(), SentryWorkspace.tokenPresent())

    override fun createContent(project: Project, parent: Disposable): JComponent {
        val panel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(4)
        }

        val status = SectionText.help("Checking the auth token…")
        panel.add(status.leftAligned())

        // Two links in the order the work happens — mint one at Sentry, then put it into this
        // IDE — shown only to a reader who has no working token.
        //
        // This replaces a permanent paragraph naming all three places `resolveSecret` looks. That
        // sentence described our plumbing to someone who wanted a route, and the one place it is
        // worth saying is the setup form, which prints the resolved source beside the field.
        val needsToken = mutableListOf<JComponent>()
        needsToken += ActionLink(CREATE_TOKEN_ACTION) { BrowserUtil.browse(TOKEN_SETTINGS_URL) }
        needsToken += if (SentrySetup.hostCanOpenSetup()) {
            ActionLink(SETUP_ACTION) { SentrySetup.openSetup(project) }
        } else {
            dim(WHERE_TO_ADD)
        }
        needsToken.forEach {
            it.isVisible = false
            panel.add(it.leftAligned())
        }

        // The optional variables, and the other half of the same disclosure: they are a *tuning*
        // for a token that already works, so a reader who has none is not shown two more names to
        // set. Only the first of them is a decision anyone makes twice, hence one line rather
        // than the paragraph this used to be.
        val tuning = dim(
            "${SentryQueries.ORG_NAME} picks one of several organizations; " +
                "${SentryQueries.URL_NAME} points at a self-hosted install. Both optional.",
        )
        tuning.isVisible = false
        panel.add(tuning.leftAligned())

        // The one network call this extension makes without the user typing `#`, and it happens
        // only because they opened this section to ask whether the token works.
        var alive = true
        Disposer.register(parent) { alive = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = probe()
            // Compared against both booleans rather than negated: `null` is "nobody answered",
            // which is neither a token to tune nor one the user can go and create.
            val present = SentryWorkspace.tokenPresent()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (alive) {
                        status.text = text
                        needsToken.forEach { it.isVisible = present == false }
                        tuning.isVisible = present == true
                    }
                },
                ModalityState.any(),
            )
        }
        return panel
    }

    /** Background. Resolves the token, then asks Sentry who it belongs to. */
    private fun probe(): String {
        val api = ApplicationManager.getApplication().getService(ClaudeAgentsApi::class.java)
            ?: return "Agents Deck is not available."
        val token = runCatching { api.resolveSecret(SentryQueries.TOKEN_NAME) }.getOrNull()?.trim()
        SentryWorkspace.rememberTokenPresence(!token.isNullOrEmpty())
        if (!token.isNullOrEmpty()) {
            val base = SentryQueries.normalizeBase(
                runCatching { api.resolveSecret(SentryQueries.URL_NAME) }.getOrNull(),
            )
            val org = runCatching { api.resolveSecret(SentryQueries.ORG_NAME) }.getOrNull()?.trim()
            SentryWorkspace.ensure(token, base, org, SentryClient(HttpSentryTransport(token), base))
        }
        return SentryStatus.expanded(
            SentryWorkspace.snapshot(),
            SentryWorkspace.error(),
            tokenPresent = !token.isNullOrEmpty(),
        )
    }

    private fun dim(text: String) = SectionText.help(text)

    private fun <T : JComponent> T.leftAligned(): T = apply { alignmentX = Component.LEFT_ALIGNMENT }

    private companion object {
        const val TOKEN_SETTINGS_URL = "https://sentry.io/settings/account/api/auth-tokens/"
    }
}

/**
 * The pair of links a section with no credential owes, and the host capability behind the second.
 *
 * Worded as a sequence rather than as two verbs — "Create…" then "Add the token…" — because the
 * two read alike otherwise and a reader with neither clicks whichever came first. Granola's
 * Meetings tab and Linear's section carry the same pair in the same order; the three extensions
 * are separate Gradle builds, so the shape is shared by convention and pinned by each one's test.
 */
internal const val CREATE_TOKEN_ACTION = "Create an auth token in Sentry"

internal const val SETUP_ACTION = "Add the token to Agents Deck"

/** What replaces [SETUP_ACTION] on a host with no token form: a destination, not an action. */
internal const val WHERE_TO_ADD = "Add it under Settings › Extensions."

/**
 * Whether this host can open the token form itself, and the call that opens it.
 *
 * Feature-checked on `apiVersion`, never on the host plugin's version string — a surface that
 * offered the link on an older host would paint a link that answers false and does nothing.
 */
internal object SentrySetup {

    fun hostCanOpenSetup(): Boolean = runCatching {
        val api = ApplicationManager.getApplication()?.getService(ClaudeAgentsApi::class.java) ?: return false
        api.apiVersion >= SETUP_API_VERSION
    }.getOrDefault(false)

    /** **EDT** — it shows a modal. Answers whether the host had a form to open. */
    fun openSetup(project: Project): Boolean = runCatching {
        val api = ApplicationManager.getApplication()?.getService(ClaudeAgentsApi::class.java) ?: return false
        api.openExtensionSetup(project, SentryQueries.TOKEN_NAME)
    }.getOrDefault(false)

    /** The `apiVersion` that added `openExtensionSetup`. */
    private const val SETUP_API_VERSION = 3
}

/**
 * What the section says, kept out of the Swing so both lines are pinned without a settings
 * page — and so the "visibly degraded, never broken" claim is a test rather than a screenshot
 * of one lucky state.
 *
 * Neither line ever carries the token itself: the user knows what they set, and a settings
 * page is a place people take screenshots of.
 */
internal object SentryStatus {

    /**
     * The dimmed line beside the collapsed title.
     *
     * Null while nothing is known — the startup activity answers the token question without a
     * network call, and until it has, "Not connected" would be a guess wearing the grammar of
     * a fact.
     */
    fun collapsed(info: SentryOrgInfo?, error: String?, tokenPresent: Boolean?): String? = when {
        info != null -> info.name
        error != null -> error
        tokenPresent == false -> NO_TOKEN
        tokenPresent == true -> "Auth token found"
        else -> null
    }

    /**
     * What a reader sees when there is no credential — **"No auth token"**, not the name of the
     * environment variable it would have been read from.
     *
     * `No SENTRY_AUTH_TOKEN` answers a question a first-time reader was not asking. The variable
     * matters to whoever is exporting one, and the place that is done is the setup form, which
     * prints the name on the field itself.
     */
    const val NO_TOKEN = "No auth token"

    /**
     * The line inside the expanded section, after a probe has been given its chance.
     *
     * The project count is the half that matters and the half a user cannot otherwise see:
     * links are gated on it, so an organization that answered with **no** projects explains a
     * transcript where nothing linkified.
     */
    fun expanded(info: SentryOrgInfo?, error: String?, tokenPresent: Boolean): String = when {
        !tokenPresent -> "$NO_TOKEN — Sentry issues will not be offered in chat."
        info != null && info.projectSlugs.isNotEmpty() ->
            "Connected · ${info.name} · ${info.projectSlugs.size} " +
                (if (info.projectSlugs.size == 1) "project" else "projects") +
                (if (info.chosenByEnv) " · chosen by ${SentryQueries.ORG_NAME}" else "")
        info != null -> "Connected · ${info.name} · no projects visible to this token"
        else -> error ?: "Sentry did not answer."
    }
}
