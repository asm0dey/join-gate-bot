# Member Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/remind` starts a check that asks members who haven't passed to fill the group's form, deciders approve or remove them, and at the deadline deciders get the non-responders with Remove buttons; passed members can update their answers any time.

**Architecture:** The existing form engine (`ApplicantFlow`) and review fan-out (`ReviewService`) gain a `Kind` (`JOIN`/`CHECK`/`UPDATE`) on sessions and submissions. A roster table records which members the bot has seen and whether they passed. A new `CheckService` owns `/remind`, check entry, update offers, the hourly deadline and non-responder removal.

**Tech Stack:** Kotlin 2.4, vendeli telegram-bot 9.6, Exposed + Flyway on H2, Tink, Kotest, Svelte 5 Mini App (Bun).

**Spec:** `docs/superpowers/specs/2026-10-04-member-check-design.md`. Glossary: `CONTEXT.md`. Read both before any task.

## Global Constraints

- Every user-facing string goes through `Texts.t` with an `en` and a `ru` entry; never a literal percent sign; args documented in the comment block at the top of `Texts.kt`.
- Plain text only: never set `parse_mode`.
- Telegram calls go through `Telegram.kt` wrappers that return a result type and never throw (except cancellation).
- Log only class names / Telegram's fixed reasons, never user data.
- Callback data: `u|<chatId>` (start an update), `k|<chatId>|<userId>` (remove a non-responder). Existing `f|`, `r|` unchanged. Deep-link payload: `r<chatId>` (e.g. `r-100123`).
- Dates shown to people: `yyyy-MM-dd HH:mm 'UTC'`.
- `/remind` duration: `<n>h` or `<n>d`, 1 hour to 365 days inclusive; default 7 days.
- Deadline list: at most 50 Remove buttons per message.
- Check deciders: non-bot admins with Invite users **and** Ban users rights (owner has both). Join deciders unchanged (Invite only).
- Tests: Kotest `StringSpec`, `FakeTelegram`, `TestClock`, `testDb(name)` with a unique name per test. Run one spec: `./mvnw -B -q test -Dtest=<SpecName>`; all: `mise run test`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Anonymous admin sends `/remind`** (message `from` is GroupAnonymousBot, `sender_chat` is the group): expected a reply "Anonymous admins can't start a check; post as yourself." not silence. Test in Task 6.
2. **`/remind@<botname> 3d`** (Telegram autocompletes the bot name in groups with several bots): expected to work like `/remind 3d`. Test in Task 6.
3. **"I disagree" on a consent question inside a Check form**: there is no join request to decline. Expected: session ends, user told the form closed and they can tap the button again; no `declineChatJoinRequest`, no submission. Test in Task 4.
4. **`getChatMember` fails for one non-responder at the deadline**: expected that person stays on the list, labelled `id <userId>`, rather than vanishing. Test in Task 7.
5. **Deadline tick runs twice** (overlapping loop, or restart right after closing): expected lists sent once. Closing is a conditional update on `closed_at IS NULL`. Test in Task 7.

---

### Task 1: Schema, kinds and repositories

**Files:**
- Create: `src/main/resources/db/migration/V2__member_check.sql`
- Create: `src/main/kotlin/joinbot/CheckRepos.kt`
- Modify: `src/main/kotlin/joinbot/Tables.kt`, `src/main/kotlin/joinbot/Repos.kt`
- Test: `src/test/kotlin/joinbot/DbTest.kt`, `src/test/kotlin/joinbot/ReposTest.kt`, create `src/test/kotlin/joinbot/CheckReposTest.kt`

**Interfaces:**
- Produces:
  - `enum class Kind { JOIN, CHECK, UPDATE }` in `Repos.kt`; `Status` gains `REMOVED`.
  - `Session.kind: Kind = Kind.JOIN` (last constructor param); `Submission.kind: Kind` (after `createdAt`).
  - `SubmissionRepo.create(..., at: Instant, kind: Kind = Kind.JOIN, decidedBy: Long? = null, decidedAt: Instant? = null): Long`
  - `SubmissionRepo.latest(chatId: Long, status: Status?): List<Submission>` — latest row per `user_id` in the chat (highest `id`), then filtered by `status`, newest first.
  - `SubmissionRepo.pendingCheck(chatId: Long, userId: Long): Submission?`
  - `data class Check(val chatId: Long, val deadline: Instant, val startedBy: Long, val startedAt: Instant, val closedAt: Instant?)`
  - `class MemberRepo(db)`: `seen(chatId, userId)` (insert if absent), `pass(chatId, userId, at: Instant)` (upsert `passed_at`), `remove(chatId, userId)`, `passedAt(chatId, userId): Instant?`, `known(chatId, userId): Boolean`, `count(chatId): Int`, `passedGroups(userId): List<Long>`, `unpassed(chatId): List<Long>`.
  - `class CheckRepo(db)`: `open(chatId, deadline, by, at)` (upsert row with `closed_at = null`, deletes its messages and notices), `get(chatId): Check?`, `openCheck(chatId): Check?` (null when closed), `setDeadline(chatId, deadline)`, `addMessage(chatId, messageId)`, `messages(chatId): List<Long>`, `due(now): List<Check>` (open, deadline ≤ now), `close(chatId, at): Boolean` (only when `closed_at IS NULL`), `notice(chatId, adminId, delivered: Boolean)`, `undelivered(adminId): List<Long>` (chat ids).

- [ ] **Step 1: Write failing tests**

`DbTest."migrates and Exposed tables match"`: add `Members, Rechecks, RecheckMessages, RecheckNotices` to the checked tables.

`CheckReposTest`:
```kotlin
"backfill marks every approved applicant passed" // run V1 only via Flyway target "1", insert APPROVED (decided_at T1, T2 for same user) + REJECTED rows, migrate to latest; member row for the approved user with passed_at == T2, none for the rejected one
"latest picks newest per person, then filters" // user 5: REJECTED id1, PENDING CHECK id2; user 6: APPROVED id3
  // latest(CHAT, null).map { it.id } shouldBe listOf(3L, 2L); latest(CHAT, REJECTED) shouldBe empty; latest(CHAT, PENDING).map { it.id } shouldBe listOf(2L)
"close is conditional" // open, close(at) true, close(at) false
"reopen clears messages and notices" // open, addMessage, notice; open again → messages() and undelivered() empty
"due returns only open checks past deadline"
"member pass, unpassed, passedGroups"
```
`ReposTest`: `"kind round-trips on sessions and submissions"`; `"migrate moves members and the check"` — group -100 with member rows, an open check with one message and one notice; `groups.migrate(-100, -200, sessions)`; all of them now under -200, nothing under -100.

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='DbTest,ReposTest,CheckReposTest'`: compile errors on `Kind`, `MemberRepo`, `CheckRepo`.

- [ ] **Step 3: Implement**

`V2__member_check.sql` is the spec §6 block verbatim. Add the Exposed mirrors in `Tables.kt` (`Members` → `member`, `Rechecks` → `recheck`, `RecheckMessages` → `recheck_message`, `RecheckNotices` → `recheck_notice`; `kind` text columns on `FormSessions`/`Submissions` with `.default("JOIN")`). Wire `kind` through `SessionRepo.put/session` and `SubmissionRepo.create/submission`. `latest` uses a subquery `MAX(id) GROUP BY user_id` for the chat.

`GroupRepo.migrate`: after sessions, `Members.update { chatId = newId }`; for the check, insert a copy of the old `recheck` row under `newId` (skip if one exists), re-point `recheck_message` and `recheck_notice` rows, delete the old `recheck` row — children reference it without `ON UPDATE CASCADE`, so the parent PK can't be updated in place.

- [ ] **Step 4: Run, expect PASS** — same command.

- [ ] **Step 5: Run everything** — `./mvnw -B -q test`; existing suites still pass (default `kind = JOIN`).

- [ ] **Step 6: Commit** — `feat: schema and repositories for member checks`

---

### Task 2: Telegram wrappers and rights

**Files:**
- Modify: `src/main/kotlin/joinbot/Telegram.kt`, `src/main/kotlin/joinbot/AdminCheck.kt`, `src/main/kotlin/joinbot/Main.kt`
- Modify: `src/test/kotlin/joinbot/FakeTelegram.kt`
- Test: `src/test/kotlin/joinbot/TelegramTest.kt`, `src/test/kotlin/joinbot/AdminCheckTest.kt`

**Interfaces:**
- Produces:
  - `Admin(userId, name, isBot, canInvite, canBan: Boolean = false)`; owner → `canBan = true`; administrator → `canRestrictMembers`.
  - `enum class Kick { OK, GONE, NO_RIGHT, TRANSIENT }`
  - `enum class Membership { MEMBER, ADMIN, GONE }`; `data class MemberInfo(val membership: Membership, val profile: Profile)`
  - `suspend fun TelegramBot.memberInfo(chatId: Long, userId: Long): MemberInfo?` — null on failure. `member`/`restricted` → MEMBER, `administrator`/`creator` → ADMIN, `left`/`kicked` → GONE.
  - `suspend fun TelegramBot.memberCount(chatId: Long): Int?`
  - `suspend fun TelegramBot.removeMember(chatId: Long, userId: Long): Kick` — `memberInfo` first: GONE → `Kick.GONE`, null → TRANSIENT; then `banChatMember`, then `unbanChatMember(onlyIfBanned = true)`. A ban failure whose description contains `not enough rights` → NO_RIGHT; any other failure → TRANSIENT (`ponytail:` an admin promoted mid-check also lands here). Unban failure is logged, result stays OK.
  - `suspend fun TelegramBot.botUsername(): String?` (one `getMe`).
  - `AdminCheck(bot, clock, botId: Long, ttl = 60s)`: `canDecideCheck(chatId, userId, fresh = false): Boolean`, `checkDeciders(chatId): List<Long>`, `botCanBan(chatId): Boolean` (the admin entry whose `userId == botId` has `canBan`). Main passes `cfg.botToken.substringBefore(':').toLong()`; tests pass `0` (the fake token is `000:...`).
- `FakeTelegram` gains: `members: MutableMap<Pair<Long, Long>, String>` (status per chat+user, default `"left"`), `memberCount: Int?`, `banResult: Kick` (OK/NO_RIGHT/TRANSIENT), handlers for `getChatMember`, `getChatMemberCount`, `banChatMember`, `unbanChatMember`, `getMe`, recording calls `"ban <chat> <user>"`, `"unban <chat> <user>"`; `Admin.json()` writes `can_restrict_members` from `canBan`.

- [ ] **Step 1: Write failing tests**

```kotlin
// TelegramTest
"removeMember bans then unbans a member"       // members[CHAT to 5]="member" → Kick.OK; calls contain "ban -100 5" then "unban -100 5"
"removeMember on someone who left is GONE"     // no ban call
"removeMember without the right is NO_RIGHT"   // banResult = NO_RIGHT
"memberInfo maps statuses"                     // restricted→MEMBER, creator→ADMIN, kicked→GONE, profile name/username carried
// AdminCheckTest
"check deciders need invite and ban"           // admins: (1, invite+ban), (2, invite only), bot(0, ban) → checkDeciders == [1]
"botCanBan reads the bot's own entry"
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='TelegramTest,AdminCheckTest'`.
- [ ] **Step 3: Implement** the signatures above; update every `AdminCheck(...)` construction in tests to pass `0`.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -B -q test`.
- [ ] **Step 5: Commit** — `feat: Telegram wrappers for membership and removal`

---

### Task 3: Roster

**Files:**
- Create: `src/main/kotlin/joinbot/Roster.kt`
- Modify: `src/main/kotlin/joinbot/Handlers.kt`, `src/main/kotlin/joinbot/Polling.kt`, `src/main/kotlin/joinbot/Registry.kt`, `src/main/kotlin/joinbot/Main.kt`, `src/main/kotlin/joinbot/ReviewService.kt`
- Test: create `src/test/kotlin/joinbot/RosterTest.kt`; modify `HandlersTest.kt`, `PollTest.kt`, `ReviewServiceTest.kt`

**Interfaces:**
- Consumes: `MemberRepo` (Task 1).
- Produces: `class Roster(members: MemberRepo)` with `fun seen(chatId: Long, userId: Long)` (skips the DB when an in-memory `ConcurrentHashMap.newKeySet<Pair<Long, Long>>()` holds the pair; `ponytail:` unbounded, bounded LRU if memory shows up) and `fun left(chatId: Long, userId: Long)` (deletes the row and the cache entry). `Registry.roster`. `ReviewService` gains a `members: MemberRepo` constructor param.

- [ ] **Step 1: Write failing tests**

```kotlin
// RosterTest
"seen writes once"                      // seen twice → one row; delete row behind its back, seen again → still no row (cache)
"left forgets the member and the cache" // seen, left, seen → row exists
// HandlersTest (feed raw update JSON through the existing upd() helper)
"chat_member join adds, leave removes"  // new_chat_member status member → known; status left → not known
"group message from a person is seen; from a bot is not"
"reaction with a user is seen; anonymous reaction (actor_chat) is not"
// PollTest
"allowed updates include chat_member and message_reaction"
// ReviewServiceTest
"approving a join marks the applicant passed" // members.passedAt(CHAT, APPLICANT) == clock.instant()
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='RosterTest,HandlersTest,PollTest,ReviewServiceTest'`.

- [ ] **Step 3: Implement**
  - `ALLOWED_UPDATES` += `UpdateType.CHAT_MEMBER`, `UpdateType.MESSAGE_REACTION`; extend its comment.
  - `@UpdateHandler([UpdateType.CHAT_MEMBER]) suspend fun memberChanged(update: ChatMemberUpdate)`: non-bot user; new status member/restricted/administrator/creator → `seen`, left/kicked → `left`.
  - `@UpdateHandler([UpdateType.MESSAGE_REACTION]) suspend fun reacted(update: MessageReactionUpdate)`: `user` non-null and not a bot → `seen(chat.id, user.id)`.
  - `fallback`, `MessageUpdate` in a group or supergroup: `from` non-bot and `sender_chat` null → `seen`. Group messages still get no reply.
  - `ReviewService.onDecision`: after a `JOIN` reaches `APPROVED`, `members.pass(chatId, userId, now)`.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -B -q test`.
- [ ] **Step 5: Commit** — `feat: roster of seen members`

---

### Task 4: Check and Update sessions in the form flow

**Files:**
- Modify: `src/main/kotlin/joinbot/ApplicantFlow.kt`, `src/main/kotlin/joinbot/Purge.kt`, `src/main/kotlin/joinbot/Texts.kt`
- Test: `src/test/kotlin/joinbot/ApplicantFlowTest.kt`, `src/test/kotlin/joinbot/PurgeTest.kt`

**Interfaces:**
- Consumes: `Kind`, `Session.kind`, `SubmissionRepo.create(..., kind)`.
- Produces:
  - `suspend fun ApplicantFlow.startMember(chatId: Long, userId: Long, profile: Profile, lang: String?, kind: Kind)` — `kind` is CHECK or UPDATE. If a session for this chat exists, re-ask it (as `onStart`). Else if another session is active, store this one as `WAITING` and send `T.QUEUED(groupTitle)`. Else begin it.
  - `ReviewService.notifyUpdate(submissionId: Long)` is called by submit for UPDATE (implemented in Task 5; in this task add it as the call site and a stub that Task 5 fills — keep the stub a no-op returning Unit).
- Texts added: `FORM_FOR(groupTitle)` "Form for %s", `QUEUED(groupTitle)` "You'll get %s's form after you finish the current one.", `CHECK_FORM_CLOSED` "Form closed. Tap the button in the group to start again.", `UPDATE_SAVED` "Thanks! Your updated answers were saved.", `FORM_CLOSED` "This form is closed."

Behaviour per kind (JOIN unchanged everywhere):
- `begin`: non-JOIN prepends `FORM_FOR(title)` as its own first message, sent to `s.userId`.
- `submit`: CHECK → `PENDING` with kind CHECK, `T.SUBMITTED`, `review.submit(id)`; UPDATE → `APPROVED` with kind UPDATE, `decidedAt = now`, `T.UPDATE_SAVED`, `review.notifyUpdate(id)`.
- `unreachable`: non-JOIN deletes the session only; no submission.
- `declineUnlocked`: non-JOIN deletes the session and sends `CHECK_FORM_CLOSED`; no `declineJoin`. Used by consent "I disagree" and by `Purge` expiry.
- `Purge.runOnce`: creates the `EXPIRED` submission only for JOIN sessions.
- `closeForChat`: non-JOIN sessions get `FORM_CLOSED` instead of `GROUP_GONE` (which talks about join requests).

- [ ] **Step 1: Write failing tests** (ApplicantFlowTest; a member session is started with `flow.startMember(CHAT, U, ann, "en", Kind.CHECK)`)

```kotlin
"a check form opens with the group name, then the welcome"   // sent texts: "Form for Club", "Hi", "Why?"
"submitting a check makes a pending CHECK submission"        // subs.list(CHAT, PENDING).single().kind == CHECK
"submitting an update saves it approved, without review buttons" // status APPROVED, kind UPDATE, decidedAt set
"a check queues behind another group's form and says so"     // active JOIN for -200 → session WAITING, last text QUEUED("Club")
"starting the same check again re-asks the current question"
"disagreeing on consent in a check closes it without declining" // tg.calls none startsWith "decline"; no submission; text CHECK_FORM_CLOSED  (Review Focus 3)
"a 403 mid-check drops the session and sends nothing to deciders"
"closing a group tells check users the form is closed, not about join requests"
// PurgeTest
"an idle check session is closed without an EXPIRED submission or a decline"
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='ApplicantFlowTest,PurgeTest'`.
- [ ] **Step 3: Implement** as specified above. `Session` carries `kind` through `fresh`, `copy`, `put`.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -B -q test`.
- [ ] **Step 5: Commit** — `feat: check and update sessions in the form flow`

---

### Task 5: Reviewing Check and Update submissions

**Files:**
- Modify: `src/main/kotlin/joinbot/ReviewService.kt`, `src/main/kotlin/joinbot/Texts.kt`
- Test: `src/test/kotlin/joinbot/ReviewServiceTest.kt`

**Interfaces:**
- Consumes: `AdminCheck.checkDeciders/canDecideCheck`, `TelegramBot.removeMember: Kick`, `MemberRepo.pass/remove`.
- Produces: `suspend fun ReviewService.notifyUpdate(submissionId: Long)`.
- Texts added: `CHECK_HEADER(name, group)` "Member check: %s in %s", `UPDATE_HEADER(name, group)` "Updated answers: %s in %s", `REMOVE` "Remove", `DECIDED_BY_REMOVED(name)` "Removed by %s", `CHECK_APPROVED_USER(group)` "You're all set in %s.", `CHECK_REMOVED_USER(group)` "The admins removed you from %s. You can ask to join again.", `NO_BAN_RIGHT` "The bot can't remove members: give it the Ban users right."

Rules:
- `submit` for CHECK: copies go to `checkDeciders`; header `CHECK_HEADER`; buttons `APPROVE` (`r|id|a`) / `REMOVE` (`r|id|j`).
- `onDecision` for CHECK: rights via `canDecideCheck(fresh = true)`. Approve: `decide` → no Telegram call → `members.pass`; copies end with `DECIDED_BY_APPROVED`; DM `CHECK_APPROVED_USER`. Remove: `decide(REJECTED)` → `removeMember`: OK → `members.remove`, copies end with `DECIDED_BY_REMOVED`, DM `CHECK_REMOVED_USER`; GONE → `transition` to `WITHDRAWN` (as today); NO_RIGHT → `revert`, alert `NO_BAN_RIGHT`; TRANSIENT → `revert`, alert `TRY_AGAIN`.
- `notifyUpdate`: one copy per Join decider (`deciders`), header `UPDATE_HEADER`, no buttons, not recorded in `review_message`.
- `deliverPending`: a CHECK submission goes only to admins passing `canDecideCheck`.
- `renderReview` picks the header by kind.

- [ ] **Step 1: Write failing tests**

```kotlin
"check copies go only to admins who can ban, with Approve and Remove"
"approving a check passes the member without a Telegram call" // no "approve"/"ban" calls; passedAt set; DM CHECK_APPROVED_USER
"removing a check bans, unbans and forgets the member"        // calls ban+unban; status REJECTED; members.known false; copies end "Removed by A1"
"removing someone who left marks the check withdrawn"
"removing without the ban right reverts and says why"         // status PENDING again; alert NO_BAN_RIGHT
"an invite-only admin can't decide a check"                   // alert NOT_ADMIN_ANYMORE, status PENDING
"an update goes to deciders as a copy without buttons"
"pending checks reach only check deciders on /start"
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest=ReviewServiceTest`.
- [ ] **Step 3: Implement** the rules above.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -B -q test`.
- [ ] **Step 5: Commit** — `feat: review for check and update submissions`

---

### Task 6: `/remind`, entering a check, updating any time

**Files:**
- Create: `src/main/kotlin/joinbot/CheckService.kt`
- Modify: `src/main/kotlin/joinbot/Handlers.kt`, `src/main/kotlin/joinbot/Registry.kt`, `src/main/kotlin/joinbot/Main.kt`, `src/main/kotlin/joinbot/Texts.kt`
- Test: create `src/test/kotlin/joinbot/CheckServiceTest.kt`; modify `HandlersTest.kt`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces `class CheckService(groups, forms, subs, members: MemberRepo, checks: CheckRepo, sessions, users, admins: AdminCheck, flow: ApplicantFlow, roster: Roster, bot, clock)`:
  - `suspend fun remind(chatId: Long, fromId: Long, anonymous: Boolean, arg: String?)`
  - `suspend fun enter(chatId: Long, user: Profile, userId: Long, lang: String?)` — the `/start r<chatId>` path.
  - `suspend fun offerUpdates(userId: Long, lang: String?): Boolean` — false when the user is a passed member of no active group with a form.
  - `suspend fun onUpdateButton(userId: Long, callbackId: String, data: String, profile: Profile, lang: String?)` — `u|<chatId>`.
  - `fun parseDuration(arg: String?): Duration?` — null when invalid or out of range; `null` arg → 7 days.
- Bot username: `CheckService` caches `bot.botUsername()` on first use; when unavailable, `/remind` replies `TRY_AGAIN`.
- Texts added: `REMIND_POST(date)` "Members who haven't filled the join form yet: please do it by %s.", `FILL_FORM` "Fill the form", `REMIND_KNOWS(n, m, group)` "The bot knows %d of %d members of %s.", `REMIND_BAD_DURATION` "Use a duration from 1h to 365d, like 7d or 48h.", `REMIND_NO_FORM` "This group has no form yet.", `REMIND_NO_BAN` "I need the Ban users right to run a check.", `REMIND_ANONYMOUS` "Anonymous admins can't start a check; post as yourself.", `REMIND_POST_FAILED` "I couldn't post the check message in %s.", `CHECK_ENDED` "This check has ended.", `NOT_IN_GROUP` "You're not in this group.", `ADMINS_EXEMPT` "Admins don't need to fill the form.", `CHECK_PENDING` "Your answers are waiting for the deciders' review.", `OFFER_UPDATE(group)` "You've already filled the form for %s. Want to update your answers?", `YES_UPDATE` "Yes, update", `UPDATE_LIST` "You can update your answers for:".

`remind` order: anonymous → `REMIND_ANONYMOUS` in the group; not `canDecideCheck(fresh = true)` → silence; bad duration → `REMIND_BAD_DURATION`; no form → `REMIND_NO_FORM`; `!botCanBan` → `REMIND_NO_BAN`. Then post `REMIND_POST(deadline)` with one URL button `FILL_FORM` → `https://t.me/<username>?start=r<chatId>`. Post failed → `REMIND_POST_FAILED` in DM to `fromId`, nothing stored. Posted → `checks.open` if no open check (else `setDeadline` only when `arg != null`), `addMessage`, then DM `REMIND_KNOWS(members.count, memberCount ?: 0, title)` (DM result ignored).

URL buttons: `Button` gains `url: String? = null`; `rows()` in `Telegram.kt` emits `text url` when set; `FakeTelegram.buttons()` reads `url` into `data` when `callback_data` is absent.

`enter` order: spec §2 checks 1–7 (`openCheck`, `memberInfo`, session for chat → `flow.startMember` re-asks, `pendingCheck`, `passedAt` → `OFFER_UPDATE` with `YES_UPDATE` = `u|<chatId>`, else `flow.startMember(..., Kind.CHECK)`). Always `roster.seen` once membership is confirmed.

`onUpdateButton`: re-checks group active with form, `memberInfo` MEMBER, `passedAt != null`; then `answerCallback` and `flow.startMember(..., Kind.UPDATE)`; otherwise `STALE_BUTTON` alert.

`offerUpdates`: `members.passedGroups(userId)` ∩ active groups with a form → `UPDATE_LIST` with one `u|<chatId>` button per group titled with the group title.

Handlers:
- `start`: read the raw text from `(update as MessageUpdate).message.text`; payload `^/start r(-?\d+)$` → `checks.enter`. Plain `/start`: today's order, then `if (checks.offerUpdates(...)) return` before `HOW_TO_JOIN`.
- `fallback`, group message whose text matches `^/remind(@\w+)?(?:\s+(\S+))?\s*$` → `checks.remind(chat, from, anonymous = senderChat?.id == chat.id, arg)`. No `@CommandHandler` for `/remind`, so the `@botname` form and arguments are parsed in one place.
- `fallback`, callback `u|` → `checks.onUpdateButton`.

- [ ] **Step 1: Write failing tests**

```kotlin
// HandlersTest
"/start with a check payload reaches enter"            // text "/start r-100" in private → CheckService entered (Check session started: "Form for Club" sent)
"/remind@joinbot 3d in the group starts a check"       // Review Focus 2; checks.openCheck(CHAT)!!.deadline == now + 3d
"/remind from an anonymous admin gets an explanation"  // Review Focus 1; sent to CHAT: REMIND_ANONYMOUS
// CheckServiceTest
"parseDuration bounds"            // "1h"→1h, "365d"→365d, null→7d; "0h","366d","8761h","7w","" → null
"only check deciders can remind"  // invite-only admin → no calls at all
"remind refuses without a form or without the ban right"
"remind posts the deep link and tells the admin the coverage" // button url "https://t.me/joinbot?start=r-100"; DM REMIND_KNOWS(2, 10, "Club")
"a second remind re-posts and moves the deadline only with a duration"
"remind posts nothing stored when the post fails"
"enter: ended, not a member, admin, pending, passed → update offer, else check session" // one test per branch
"update button starts an update session for a passed member; stale otherwise"
"plain /start lists update buttons per passed group"   // two groups → two u| buttons in title order
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='CheckServiceTest,HandlersTest'`.
- [ ] **Step 3: Implement** as above; wire `CheckService` in `Main.kt` and `Registry.checks`.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -B -q test`.
- [ ] **Step 5: Commit** — `feat: /remind, check entry and updates any time`

---

### Task 7: Deadline and non-responder removal

**Files:**
- Modify: `src/main/kotlin/joinbot/CheckService.kt`, `src/main/kotlin/joinbot/Handlers.kt`, `src/main/kotlin/joinbot/GroupRegistry.kt`, `src/main/kotlin/joinbot/Main.kt`, `src/main/kotlin/joinbot/Texts.kt`, `README.md`
- Test: `src/test/kotlin/joinbot/CheckServiceTest.kt`, `src/test/kotlin/joinbot/GroupRegistryTest.kt`

**Interfaces:**
- Produces on `CheckService`:
  - `suspend fun tick()` — one deadline pass.
  - `fun start(scope: CoroutineScope): Job` — `tick()` every hour, errors logged by class name, like `Purge.start`.
  - `suspend fun onRemoveButton(adminId: Long, callbackId: String, data: String, messageId: Long)` — `k|<chatId>|<userId>`.
  - `suspend fun deliverNotices(adminId: Long): Int` — sends undelivered deadline lists, returns how many.
  - `suspend fun closeForChat(chatId: Long)` — closes without a list, drops buttons from the check messages.
- Texts added: `CHECK_CLOSED(date)` "The check closed on %s.", `NONRESPONDERS(group, date, n, m)` "Didn't fill the form for %s by %s. The bot knows %d of %d members; only those are listed.", `REMOVE_NAME(name)` "Remove %s", `ALL_PASSED(group)` "Everyone the bot knows in %s has filled the form.", `PASSED_SINCE(name)` "%s has filled the form since.", `PENDING_SINCE(name)` "%s's answers are waiting for review.", `ALREADY_REMOVED` "Already removed.", `ID_ONLY(userId)` "id %d".

`tick`, per `checks.due(now)`:
1. `checks.close(chatId, now)`; false → skip (Review Focus 5).
2. Edit each `checks.messages(chatId)` to `REMIND_POST(deadline)` + "\n\n" + `CHECK_CLOSED(date)` with no buttons.
3. Candidates: `members.unpassed(chatId)` minus users with `pendingCheck`. Per candidate `memberInfo`: GONE → `members.remove`; ADMIN → skip; MEMBER → `profile.name` (+ ` @username`); null → `ID_ONLY(userId)` (Review Focus 4).
4. Per `checkDeciders(chatId)`: send `NONRESPONDERS(...)` (or `ALL_PASSED`) and the buttons, `REMOVE_NAME(label)` → `k|<chatId>|<userId>`, one button per row, chunks of 50 (each chunk its own message; the first carries the header text, later ones repeat it). A 403 on the first message → `checks.notice(chatId, admin, delivered = false)` and `users.forbidden`; otherwise `notice(..., true)`.

`deliverNotices` rebuilds the list for each undelivered chat (same steps 3–4 for that one admin) and marks it delivered. `start` handler calls it after `review.deliverPending`, and returns if either sent anything.

`onRemoveButton`: fresh `canDecideCheck` else `NOT_ADMIN_ANYMORE` alert; `passedAt != null` → `PASSED_SINCE` alert; `pendingCheck` → `PENDING_SINCE` alert; `!members.known` → `ALREADY_REMOVED` alert. Else `memberInfo` for the profile, `removeMember`: OK or GONE → `members.remove`, `subs.create(chatId, userId, formVersion = null, profile, answers = null, Status.REMOVED, now, Kind.CHECK, decidedBy = adminId, decidedAt = now)`, edit the clicker's message dropping that button; NO_RIGHT → `NO_BAN_RIGHT` alert; TRANSIENT → `TRY_AGAIN` alert. Names in alerts come from `memberInfo` or `ID_ONLY`.

`GroupRegistry.onBotStatus`: when the group becomes inactive, call `checks.closeForChat(chatId)` beside `flow.closeForChat`. `handOff` sends `MANUAL_REVIEW` (a join-request text) only to users with a pending JOIN submission.

README: under "Guard a group", the bot also needs **Ban users** for checks; new "Check existing members" section: `/remind [7d]`, what members see, the deadline list, the "knows N of M" caveat.

- [ ] **Step 1: Write failing tests**

```kotlin
"the deadline closes the check, edits every post and lists non-responders" // two posts edited without buttons; admin 1 gets one message with "Remove Bob @bob"
"leavers and admins are left off; a failed lookup is listed by id" // Review Focus 4: "Remove id 7"
"a second tick sends nothing"                                      // Review Focus 5
"more than 50 non-responders split into messages of 50 buttons"   // 120 → 3 messages: 50, 50, 20
"nobody left to list says everyone filled it"
"Remove kicks, records REMOVED and drops the button"               // subs row status REMOVED kind CHECK decidedBy 1; edit lacks k|-100|5
"Remove refuses passed, pending and already removed"
"an admin who blocked the bot gets the list on /start"
"losing admin closes the check without a list"                     // GroupRegistryTest
"losing admin sends the manual-review notice only to join applicants" // GroupRegistryTest
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='CheckServiceTest,GroupRegistryTest'`.
- [ ] **Step 3: Implement** as above; `Main.kt` starts `checks.start(this)` next to `Purge`; route `k|` in `fallback`.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -B -q test`.
- [ ] **Step 5: Commit** — `feat: check deadline and non-responder removal`

---

### Task 8: Mini App and CSV

**Files:**
- Modify: `src/main/kotlin/joinbot/MiniAppApi.kt`, `src/main/kotlin/joinbot/Csv.kt`
- Modify: `web/src/api.ts`, `web/src/i18n.ts`, `web/src/views/Submissions.svelte`, `web/dev/mock-api.ts`
- Test: `src/test/kotlin/joinbot/MiniAppApiTest.kt`, `src/test/kotlin/joinbot/CsvTest.kt`

**Interfaces:**
- Consumes: `SubmissionRepo.latest`, `Submission.kind`, `Status.REMOVED`.
- Produces: `SubmissionRow.kind: Kind`; CSV fixed columns become `id, user_id, name, username, kind, status, form_version, created_at, decided_at`.

- [ ] **Step 1: Write failing tests**

```kotlin
// MiniAppApiTest
"submissions list shows each person's latest, then filters" // as in CheckReposTest, through GET /groups/{id}/submissions?status=REJECTED → []
"rows carry their kind"
// CsvTest
"csv has kind and form_version columns"  // header starts "id,user_id,name,username,kind,status,form_version,created_at,decided_at"; a REMOVED row with null form_version → empty cell
```

- [ ] **Step 2: Run, expect FAIL** — `./mvnw -B -q test -Dtest='MiniAppApiTest,CsvTest'`.
- [ ] **Step 3: Implement** — `/submissions` and `/export` use `latest(chatId, status)`. Web: `Status` and `STATUSES` add `'REMOVED'`; `SubmissionRow.kind: 'JOIN' | 'CHECK' | 'UPDATE'`; i18n `REMOVED` ("Removed" / "Удалён(а)"), kind labels `JOIN` "Join"/"Вступление", `CHECK` "Check"/"Проверка", `UPDATE` "Update"/"Обновление"; `Submissions.svelte` shows the kind label beside the status pill; mock rows get kinds.
- [ ] **Step 4: Run, expect PASS** — `./mvnw -B -q test -Dtest='MiniAppApiTest,CsvTest'` and `mise run web:test`.
- [ ] **Step 5: Full check** — `mise run build` succeeds.
- [ ] **Step 6: Commit** — `feat: Mini App and CSV show kinds and latest submissions`
