# Join-form bot: design

Date: 2026-10-02. Status: approved in brainstorming, awaiting spec review.

## Goal

A generic Telegram bot that any group can add. When someone requests to join a
guarded group, the bot DMs them a form defined by that group's admins. The
filled form goes to the group's admins in DM; when one approves, the join
request is approved.

Success: an admin adds the bot, builds a form in the Mini App, and from then on
every join request is answered by a form and decided with one click, without
the admin touching Telegram's join-request list.

### Decisions taken (with reasons)

| Decision | Choice | Rejected |
|---|---|---|
| Scope | Generic, multi-group; one form per group | Evolving the single-group Python `bot.py` (kept only as reference) |
| Stack | Kotlin + `eu.vendeli:telegram-bot`, mirroring `exchange-bot` | python-telegram-bot + FastAPI |
| Form editor | Telegram Mini App, admin-only | YAML upload, in-bot wizard |
| Applicant UI | Question-by-question in DM chat | Mini App for applicants; both |
| Updates | Long polling; HTTPS only via `MINIAPP_URL` (host-agnostic) | Webhook; baked-in reverse proxy |
| Reviewers | Admins of the guarded group who have started the bot | Separate admin group; invite-right-only subset |
| Retention | Keep answers (Tink-encrypted), list + CSV export + per-group auto-purge | Delete after decision; Google Sheets |
| Build orchestration | SDKMAN pins Java (`.sdkmanrc`); mise pins Bun and defines tasks; Gradle builds only the JVM side; Bun builds `web/` | gradle-bun (Gradle setup is hard); go-task (can't pin tools) |
| Frontend delivery | Served from the filesystem (`WEB_DIR`), not from the jar | Bundled into jar resources |

## 1. Architecture

One process, one Gradle module, flat package `joinbot`. Libraries follow
`exchange-bot` (Kotlin 2.4, vendeli 9.6, Ktor CIO, H2 file + Flyway + Exposed,
Tink, kotest, Svelte 5 + daisyUI), with two departures:

- **Toolchain:** `.sdkmanrc` pins Java (`27.0.0+36-librca`), read by SDKMAN
  auto-env and by mise (`idiomatic_version_file_enable_tools = ["java"]`);
  `mise.toml` pins Bun (`1.4.2`, Renovate bumps it) and is the single entry point: `mise run build`,
  `mise run test`. Gradle is plain Kotlin/JVM with no bun plugin; Bun builds
  `web/` into `web/dist` on its own. If Kotlin 2.4 cannot emit JVM 27
  bytecode, run on 27 with `jvmTarget` 25.
- **Static files:** Ktor serves the SPA from the filesystem,
  `staticFiles("/", File(WEB_DIR))`, `WEB_DIR` defaulting to `web/dist`. The jar
  carries no UI; the image is the only complete artifact.

```
Telegram long polling ──► bot handlers ─┐
                                        ├──► services ──► H2 (encrypted file, Tink rows)
Mini App (Ktor CIO, /api) ──────────────┘
```

| File | Job |
|---|---|
| `FormSchema.kt` | Sealed field types + `validate(field, input)`. Pure; shared by editor API and chat flow. |
| `GroupRegistry.kt` | `my_chat_member`: register group when bot is admin with `can_invite_users`; deactivate on removal/demotion. |
| `ApplicantFlow.kt` | `chat_join_request` → DM → data-driven question loop → submit. |
| `ReviewService.kt` | Fan-out to reviewers; approve/reject; first-click-wins; edit all copies. |
| `AdminCheck.kt` | `getChatAdministrators` with ~60 s cache; decisions and writes bypass the cache. |
| `MiniApp{Auth,Server,Api}.kt` | `tma <initData>` auth (copied `InitDataVerifier`), static SPA, JSON API. |
| `Purge.kt` | Daily coroutine: delete submissions past retention; expire idle sessions. |
| `web/` | Svelte admin UI, built by Bun to `web/dist`. |

### Data-driven chain (not vendeli `inputChain`/`Wizard`)

Vendeli chains and wizards declare steps at compile time and keep state in an
in-memory map by default. Our steps are per-group data. So:

- `form_session` row is the chain pointer (`step`, answers, pinned `form_version`).
- One handler takes every private text message and every `f|…` callback, loads
  the session, validates against `fields[step]`, stores, advances, asks next.
- Invalid input → error + same question again.
- Survives restarts; admin edits never shift questions under an in-progress applicant.

`exchange-bot` rulings carried over: R5/R13 (PG mode, TEXT/BYTEA), R6 (Tink
`RegistryConfiguration` API), R8 (redacted config `toString`), R57 (null
`message.from`), R60 (silence vendeli PII update logging).

Not used: db-scheduler (one daily loop does not need it).

## 2. Data model

### Form definition (JSON column, not encrypted, holds no PII)

```json
{ "welcome": "Hi! A few questions before you join…",
  "fields": [
    { "id": "a1", "type": "radio",   "prompt": "Where do you live?", "options": ["Limassol","Nicosia"], "other": true, "required": true },
    { "id": "a2", "type": "radio",   "prompt": "Agree to the rules?", "options": ["Yes","No"] },
    { "id": "a3", "type": "multi",   "prompt": "Interests?", "options": ["…"], "min": 1, "max": 3 },
    { "id": "a4", "type": "text",    "prompt": "About you", "maxLen": 300 },
    { "id": "a5", "type": "int",     "prompt": "Age?", "min": 18, "max": 120 },
    { "id": "a6", "type": "link",    "prompt": "LinkedIn?", "required": false },
    { "id": "a7", "type": "consent", "prompt": "Privacy terms… Do you agree?" } ] }
```

Field types:

| Type | Editor label | Rules |
|---|---|---|
| `radio` | Radio / Yes-No | One option. Yes/No is a preset with options `["Yes","No"]`. Optional `other: true` adds "Other" that asks for text. |
| `multi` | Multiple choice | Toggle buttons + Done. `min`/`max` selections (min defaults to 1 if required). |
| `text` | Free text / Bounded text | `maxLen` default 4096 (Telegram limit); bounded text is the same type with admin-set `maxLen`. |
| `int` | Number | Integer; optional `min`/`max`; reject overflow. |
| `link` | Link | Absolute `http(s)` URL only. |
| `consent` | Consent | Agree / Disagree. Disagree deletes the session, declines the join request and tells the applicant they can request again. |

Every field has `id` (stable across edits), `prompt`, `required` (default true;
optional fields get a Skip button).

### Tables (Flyway-owned, H2 `MODE=PostgreSQL`)

| Table | Columns | Notes |
|---|---|---|
| `group_chat` | `chat_id` PK, `title`, `active`, `retention_days` (default 90) | |
| `form` | (`chat_id`, `version`) PK, `schema_json`, `updated_by`, `updated_at` | Each save inserts a new version; current = max. Old versions kept for label resolution. |
| `form_session` | (`user_id`, `chat_id`) PK, `form_version`, `step`, `answers` 🔒, `started_at`, `touched_at` | Deleted on submit or expiry. |
| `submission` | `id` PK, `chat_id`, `user_id`, `form_version`, `profile` 🔒, `answers` 🔒, `status`, `decided_by`, `decided_at`, `created_at` | `status`: `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `EXPIRED`. |
| `review_message` | (`submission_id`, `admin_id`) PK, `message_id` | For editing every admin's copy. |
| `bot_user` | `user_id` PK, `dm_ok` | Has started the bot; false after a 403. |

🔒 = Tink AEAD (BYTEA) with associated data `table|row key`. `user_id` is
plaintext: approving needs the raw id. `profile` = Telegram name + username.

Not included: per-group admin table (Telegram is the source of truth), form
templates shared across groups.

## 3. Flows

Bot API constraint: after `chat_join_request` the bot may DM the applicant via
`user_chat_id` within 5 minutes or until the request is processed. The first
message is sent immediately; after the applicant replies it is a normal DM.

### A. Group setup

1. Admin adds the bot as admin with Invite users; enables "Approve new members"
   or uses an invite link that requires approval.
2. `my_chat_member` registers the group. If `can_invite_users` is missing, the
   bot posts one line in the group saying so.
3. Admin opens the Mini App (bot menu button), picks the group, builds the form.
4. No form yet → the bot ignores join requests for that group.

### B. Applicant

1. `chat_join_request` (active group with a form) → `form_session` → DM welcome + question 1.
2. Button fields use inline keyboards; callback `f|<chat>|<step>|<action>|<idx>` (< 64 bytes).
3. Typed fields: invalid → reason + same question.
4. After the last field: summary with **[Submit] [Start over]**.
5. Submit → `submission` `PENDING`, session deleted → review (C).
6. Requests to two groups at once: second session waits and starts after the first is submitted.
7. 7 days idle → session deleted, request declined, applicant told to request again; status `EXPIRED`.
8. DM fails → reviewers get "couldn't reach X" with [Approve] [Reject] and no answers.
9. `/start` from an applicant resumes their active session; otherwise explains how to join.

### C. Review

1. Reviewers = `getChatAdministrators(chat)` ∩ `bot_user.dm_ok`, minus bots. Each gets answers + **[✅ Approve] [❌ Reject]**.
2. On click:
   1. Fresh admin check (still admin with `can_invite_users`); otherwise alert.
   2. `UPDATE submission SET status=? WHERE id=? AND status='PENDING'`; 0 rows → alert "already decided by Y".
   3. `approveChatJoinRequest` / `declineChatJoinRequest`.
      - `HIDE_REQUESTER_MISSING` / `USER_ALREADY_PARTICIPANT` → `WITHDRAWN`.
      - Transient error → revert to `PENDING`, alert "try again".
   4. Edit every `review_message` copy ("✅ Approved by Y"); DM applicant a fixed approved/rejected text.
3. Admin who starts the bot later receives all `PENDING` submissions for their groups on `/start`.
4. No reachable reviewer → bot posts "N join requests waiting, admins please open @bot" in the group, at most once per hour per group.

Not included: reject reason, expiry reminders, per-question Back.

## 4. Mini App (admin-only)

Auth: `Authorization: tma <initData>`, verified by `InitDataVerifier` (24 h max
age). Every `/groups/{id}/…` call requires admin with `can_invite_users` in that
group, the same rule as reviewing. Reads use the cache; writes check fresh.

| Screen | Behaviour |
|---|---|
| Groups | Active groups where the user is admin (cached admin lists per group). ponytail: O(groups) Telegram calls per minute worst case; switch to tracking `chat_member` updates if it grows past a few hundred groups. |
| Form editor | Welcome text; add (type picker), edit props, reorder (↑↓), delete fields; Preview pane approximating the chat rendering. Save sends `baseVersion`; 409 if someone saved in between. Server validates with `FormSchema`. |
| Submissions | Filter by status; detail view; Delete (for "forget me" requests). |
| Settings | Retention days. |

CSV export: the bot sends the CSV as a document to the requesting admin's DM
(WebView downloads cannot carry the auth header). Columns are the union of field
ids across versions, labelled with each field's latest prompt.

API:

```
GET    /api/groups
GET    /api/groups/{id}/form
PUT    /api/groups/{id}/form                 body: { schema, baseVersion }
GET    /api/groups/{id}/submissions?status=
GET    /api/groups/{id}/submissions/{sid}
DELETE /api/groups/{id}/submissions/{sid}
POST   /api/groups/{id}/export               bot DMs the CSV
PUT    /api/groups/{id}/settings
```

Menu button set at startup via raw `setChatMenuButton` (as in `exchange-bot`).
UI and bot texts: en + ru by Telegram `language_code`; question text is whatever
the admin wrote.

Not included: drag-and-drop, approve/reject in the Mini App, "send me the form
as a test applicant".

## 5. Error handling

| Failure | Behaviour |
|---|---|
| 403 to a user | `bot_user.dm_ok=false`; applicant → reviewers told; reviewer → skipped. |
| 429 | vendeli retry; reviewer fan-out is sequential. |
| Transient error on decide | Revert to `PENDING`, alert to retry. |
| Bot removed/demoted | `active=false`; open sessions told the group no longer uses the form; pending submissions visible but not decidable. |
| Mini App / HTTPS down | Bot unaffected; only editing and export unavailable. |
| Wrong keyset | Startup self-check round-trips a ciphertext; refuse to start. |
| Logging | Silence vendeli update dumps; never log answers or profile. |

## 6. Testing

kotest `StringSpec`, in-memory H2 (PG mode), `recordingBot` Ktor MockEngine,
ktor-server-test-host — all as in `exchange-bot`.

1. `FormSchema`: table-driven validation for every type (bounds, `maxLen`, bad URLs, int overflow, multi min/max).
2. Applicant flow: fake join request + scripted answers; assert questions sent, re-asks, summary, encrypted submission.
3. Review: two simultaneous approvals → exactly one Telegram approve, both copies edited; demoted admin; withdrawn request.
4. Mini App API: bad initData → 401, non-admin → 403, stale `baseVersion` → 409.
5. Web: `bun test` for editor state logic. Playwright e2e deferred until the UI stabilises.

## 7. Ops

Dockerfile: a `oven/bun` stage builds `web/dist`, a Gradle stage builds the
jar, and the runtime stage (hardened distroless Liberica JRE 27 if that tag
exists, else 25; uid 10001; `/app/data` volume) copies the jar plus
`web/dist` → `/app/web` with `WEB_DIR=/app/web`.

CI (`ci.yml`): `jdx/mise-action` (Java from `.sdkmanrc`, Bun from `mise.toml`), then `mise run build` — the same command as
local. Release (`release.yml`): `vN` tags → tests, then GHCR image
`:N` + `:latest`; no jar on the GitHub Release, since the jar alone has no UI.
Renovate config copied from `exchange-bot`; its mise manager tracks `mise.toml`.

Env: `BOT_TOKEN`, `MINIAPP_URL`, `MINIAPP_PORT` (default 8080), `WEB_DIR`
(default `web/dist`),
`DATA_KEYSET`, `DB_FILE_KEY`, `DB_USER_PW`. A `keygen`
entry point generates the keyset.

The old Python files (`bot.py`, `compose.yml`, `Dockerfile`, `requirements.txt`)
are replaced; `bot.py` is reference for texts and UX only.
