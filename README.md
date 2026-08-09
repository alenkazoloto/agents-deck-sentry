<!-- Published from `extensions/sentry` in the Agents Deck host repository. Edit it there. -->

> **Install it from [the latest release](https://github.com/alenkazoloto/agents-deck-sentry/releases/latest)** — or from Agents Deck's
> Settings › Connections › Extensions › **+**, which installs this repository by name.
>
> Building from this checkout needs the host plugin's distribution zip, which is not published
> here: pass it as `./gradlew buildPlugin -PagentsDeckPluginZip=/path/to/agents-deck-<v>.zip`.
> The paths in the build section below are the host repository's own.

# Agents Deck: Sentry

A first-party extension for [Agents Deck](https://github.com/alenkazoloto). It puts your Sentry organization
behind the chat's `#` completion, links short ids like `BACKEND-1A2B` in agent output, and
reports which token is in force.

It is the first extension whose `#` row carries something the agent can **act** on rather than
open: a resolved short id arrives with the latest event's exception and its top in-app frames
already in the prompt.

## What it contributes

| Extension point | What you see |
|---|---|
| `issueProvider` | `#` completion rows, chips, hover cards, and — for a named short id — the stack trace travelling with the prompt |
| `transcriptLinker` | `BACKEND-1A2B` in agent output opens the issue, for projects your organization actually has |
| `settingsSection` | Settings › Connections › Integrations › **Sentry** — which organization the token belongs to |

## Configuring it

Create an **auth token** at
[sentry.io/settings/account/api/auth-tokens](https://sentry.io/settings/account/api/auth-tokens/)
with the `org:read` and `event:read` scopes, and put it in your environment:

```bash
export SENTRY_AUTH_TOKEN=sntrys_…
export SENTRY_ORG=acme                       # only when the token sees several
export SENTRY_URL=https://sentry.example.com # only for a self-hosted install
```

All three names are **`sentry-cli`'s own**
([configuration](https://docs.sentry.io/cli/configuration/)), so a machine already set up for
`sentry-cli` is configured here too. The host resolves them with its own precedence: the IDE's
process environment first, then the `env` block of a configured stdio MCP server. **This
extension stores nothing** — there is no field to type a token into, deliberately, so a secret
cannot end up in plugin state. With no token set it contributes nothing at all and the chat is
exactly the chat Agents Deck ships.

### The organization comes from the token, not from configuration

`GET /api/0/organizations/` returns exactly the bound organization for an API-key request, so a
token scoped to one organization needs no `SENTRY_ORG` at all. When a *personal* token can see
several, there is no correct default — the settings section names the variable and lists the
slugs rather than picking whichever sorted first.

## What it deliberately does not do

- **Two requests, and only for a named issue.** A resolved short id costs a short-id lookup and
  one `events/latest/` fetch. A free-text `#` search costs one request and returns titles only:
  fetching a trace per row would turn one keystroke into a round trip per result.
- **Links are gated on real project slugs.** A Sentry counter is base-32 and a project slug may
  contain hyphens, so the short-id shape also fits `UTF-8`, `SHA-256`, `ISO-8601` and
  `CVE-2024-1234`. Until the organization has been probed, nothing linkifies — never everything.
- **It only ever reads.** The transport exposes one `get`; `NeverWritesTest` pins that no source
  here can build a mutating request, so this extension cannot resolve, archive or delete an
  issue on your behalf.

## Building it

```bash
./gradlew buildPlugin            # in the host repo — produces the zip this compiles against
cd extensions/sentry && ./gradlew test buildPlugin
```

`./gradlew verifyExtensions` in the host builds this against `buildPlugin`'s freshly produced
zip. It **discovers** dependents rather than listing them, so nothing had to be edited to add
this one.

## Sources

Every endpoint used here is documented, and each was verified 2026-08-05:
[resolve a short id](https://docs.sentry.io/api/organizations/resolve-a-short-id/) ·
[list an organization's issues](https://docs.sentry.io/api/events/list-an-organizations-issues/) ·
[retrieve an issue event](https://docs.sentry.io/api/events/retrieve-an-issue-event/) ·
[list your organizations](https://docs.sentry.io/api/users/list-your-organizations/) ·
[authentication](https://docs.sentry.io/api/auth/) ·
[permissions and scopes](https://docs.sentry.io/api/permissions/).
