# Join-form bot: follow-ups

Low-priority findings parked during the 2026-10-02 build (per-task reviews and the
final whole-project review). None blocks a release. Findings already fixed during
the final fix wave are omitted.

## Release blocker (manual)

- [ ] Real-token smoke run, which nothing automated covers. Exercise the real vendeli
      dispatch, KSP registry and Mini App inside Telegram:
  - [ ] All-button form taking over 5 minutes (does a button press keep the DM open?).
  - [ ] Submit and approve.
  - [ ] Mini App: save a form, export CSV.
  - [ ] If possible, upgrade the group to a supergroup and confirm the bot follows it.

## Correctness (worth doing next)

- Submit never pressed: if the applicant answered every field but the summary
  message hits Forbidden, the submission is not flagged partial (ruling 26). A
  clean fix needs an `unconfirmed` column.
- A crash between `decide` and the Telegram approve/decline leaves the row
  APPROVED/REJECTED with no Telegram call. Needs a startup reconciliation pass.
- `body()` in MiniAppApi uses `runCatching`, which swallows `CancellationException`.
  Harmless today, out of pattern.
- 409 Conflict from getUpdates (webhook set elsewhere, or a second instance) is
  retried forever with a warning every 5 s.
- A DB error inside `handOff` after the inactive upsert loses the handoff for good.
- An EXPIRED row is created before `decline`; a failure before the session delete
  gives a duplicate EXPIRED row the next day.
- `idleSince` snapshot is not re-checked under the user lock (tiny window).
- `SessionRepo.forChat` decrypts every session; one corrupt row aborts that
  chat's cleanup.
- Group migration: a brand-new session committed between `moveChat` and the
  old-row delete is cascaded away (tiny window). Buttons sent before a migration
  answer "stale" afterwards.
- `toDecision` maps permanent errors such as `CHAT_ADMIN_REQUIRED` to TRANSIENT.
- Nudge check-then-mark is not atomic (double nudge possible). It also nudges
  when every send failed transiently.
- `editText`/`answerCallback` return Unit, so ReviewService can't tell a copy
  edit failed.
- `suspendChat` may overwrite a just-decided copy's text (cosmetic; status correct).
- `pendingFor` relies on the user lock for "one PENDING per (user, chat)"; no DB
  uniqueness.

## Behaviour / UX

- ALREADY_DECIDED wording when the status is WITHDRAWN.
- "✅" missing from the DECIDED_BY_APPROVED text (spec shows "✅ Approved by Y").
- REVIEW_UNREACHABLE says "missing or incomplete" even when some answers are shown.
- A repeat join request for a WAITING chat moves it to the back of the queue.
- `onMessage`/`onStart` return false when the pinned form version is missing
  (treated as a stranger).
- Mini App: field deletion has no confirmation; Submissions `load()` has no guard
  against out-of-order responses; an export 502 shows a generic message.
- Mini App Preview button labels are not cross-checked against the bot's Texts.
- 404-before-403 lets any Telegram user probe which group ids the bot knows.
- compose.yaml uses `:latest`, which exists only after the first `vN` release.

## Tests

- No direct tests for: `createDataSource`, several repo methods (`list`,
  `pendingInChats`, `delete`, BotUserRepo/GroupRepo preservation),
  `AdminCheck.name()`, poll()'s network shell (status branches, `exitProcess`),
  startNext skipping a DM failure, the Other-mode re-ask buttons, multi Done
  with TOO_MANY.
- The `insertIgnore` form-save race is only exercised probabilistically.
- The race test can't force the loser's click during the winner's in-flight
  Telegram call.
- The export test doesn't assert CSV bytes or the 502 path.
- ConfigTest's `shouldNotContain("\"k\"")` is a weak assertion.
- The `failDeclineFor` path no longer reaches the services' own catch blocks
  (Telegram extensions never throw); some of those catches only guard DB reads now.
- ReposTest prints an uncaught NPE from a thread in one existing test (still passes).

## Hygiene

- Handlers.kt uses `return` inside expression-body functions (KTLC-288, an error in Kotlin 2.5).
- tinylog.properties comments and some KDoc reference exchange-bot files that
  don't exist here.
- Build noise: flyway-community 401 metadata warning, JDK Unsafe warning from KSP,
  the drift test prints an "Index ..." line.
- Never-evicted maps: Exposed connection memo (tests), AdminCheck cache, the
  per-user lock map.
- `verifyKeyset` check-then-insert races on a simultaneous first start (single
  instance is fine).
- `FormJson` has `encodeDefaults = false`: never change a field default without a
  data migration, or stored form versions silently change meaning.
- Static files: dotfiles are served; the CSV guard ignores formulas behind a
  leading space; `MiniAppDeps.users` is unused; an empty title gives a
  `-submissions.csv` file name.
- `answerCallback` relies on vendeli's internal `doRequestReturning` (pinned by a test).
- Fully qualified `SortOrder` in Repos.kt; the plan doc still describes the old
  `Tg`/`VendeliTg` port; the fake parses sendDocument with regexes.
