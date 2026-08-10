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
        panel.add(
            dim(
                "Reads ${SentryQueries.TOKEN_NAME} from your shell environment, from the env block of a " +
                    "configured stdio MCP server, or from a token saved in Settings › Extensions.",
            ).leftAligned(),
        )
        panel.add(
            dim(
                "${SentryQueries.ORG_NAME} picks one when the token sees several organizations; " +
                    "${SentryQueries.URL_NAME} points at a self-hosted install. Both optional, both " +
                    "the names sentry-cli already reads.",
            ).leftAligned(),
        )
        panel.add(
            ActionLink("Create an auth token in Sentry") {
                BrowserUtil.browse(TOKEN_SETTINGS_URL)
            }.leftAligned(),
        )

        // The one network call this extension makes without the user typing `#`, and it happens
        // only because they opened this section to ask whether the token works.
        var alive = true
        Disposer.register(parent) { alive = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = probe()
            ApplicationManager.getApplication().invokeLater(
                { if (alive) status.text = text },
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
        tokenPresent == false -> "No ${SentryQueries.TOKEN_NAME}"
        tokenPresent == true -> "Auth token found"
        else -> null
    }

    /**
     * The line inside the expanded section, after a probe has been given its chance.
     *
     * The project count is the half that matters and the half a user cannot otherwise see:
     * links are gated on it, so an organization that answered with **no** projects explains a
     * transcript where nothing linkified.
     */
    fun expanded(info: SentryOrgInfo?, error: String?, tokenPresent: Boolean): String = when {
        !tokenPresent -> "No ${SentryQueries.TOKEN_NAME} — Sentry issues will not be offered in chat."
        info != null && info.projectSlugs.isNotEmpty() ->
            "Connected · ${info.name} · ${info.projectSlugs.size} " +
                (if (info.projectSlugs.size == 1) "project" else "projects") +
                (if (info.chosenByEnv) " · chosen by ${SentryQueries.ORG_NAME}" else "")
        info != null -> "Connected · ${info.name} · no projects visible to this token"
        else -> error ?: "Sentry did not answer."
    }
}
