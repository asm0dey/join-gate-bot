# Member check (`/remind`): design

Date: 2026-10-04. Status: approved in brainstorming, awaiting spec review.

## Goal

Groups that adopted the bot already have members who never filled the join form:
they joined before the bot, or before the group had a form. Admins want those
members to fill it, and to remove the ones who won't or whose answers don't pass.

Success: an admin sends `/remind` in the group; members tap a button and fill the
form in DM; admins approve or remove each one with one click; at the deadline,
admins get the members the bot knows of who still haven't filled it, each with a
Remove button.

### Decisions taken (with reasons)

| Decision | Choice | Rejected |
|---|---|---|
| Purpose | Re-vet: admins review, Remove kicks | Collect answers only; collect + notify |
| Target | Members who never passed; passed members may opt in to update | Everyone re-fills |
| Entry | Group message with a deep-link URL button (`t.me/<bot>?start=r<chatId>`) | Callback button: the bot can't DM someone who never wrote to it |
| Update by a passed member | Stored as approved; admins get an FYI copy without buttons | Full review (an update could get a vetted member kicked); Mini App only |
| Remove | Kick: ban then unban, so they may ask to join again | Permanent ban |
| No-shows | Deadline; admins get a list with per-person Remove buttons | Nothing; plain list; "Remove all" (blast radius) |
| Who is a member | Roster of members the bot has seen, labelled as partial | Responders-only list. The Bot API cannot list members |
| Code shape | Extend `ApplicantFlow`/`ReviewService` with a submission `kind` | Parallel `RecheckFlow` (~400 duplicated lines that drift) |
| Delivery | One plan, tasks ordered so each leaves a working bot | Two slices |

## 1. Starting a check

`/remind [duration]` in the group.

- Only admins for whom `AdminCheck.canDecide` holds; anyone else is ignored silently.
- Duration `<n>d` or `<n>h`; default 7 days.
- Refused, with a reply naming the cause, when the group has no form or the bot
  lacks the **Ban users** right.
- Opens the group's campaign (`recheck` row) and posts: "Members who haven't filled
  the join form yet: please do it by <date>." with a URL button **Fill the form**
  → `t.me/<bot username>?start=r<chatId>`. The username comes from `getMe` at startup.
- The admin who sent it gets a DM: "The bot knows N of M members of <group>."
  M is `getChatMemberCount`. If the DM fails, nothing else happens.
- If posting the group message fails, no campaign is created; the admin is told in DM
  if possible, otherwise it is logged.
- `/remind` while a campaign is open posts the message again. With a duration it also
  moves the deadline; the earlier group messages keep their button (the link checks
  the campaign, not the message).

## 2. Entering the form

Telegram shows a Start button for a `?start=` link even when the chat with the bot
already exists ([client spec](https://core.telegram.org/api/links)); pressing it sends
`/start r<chatId>`. A plain `/start` keeps today's behaviour.

On `/start r<chatId>` the bot checks, in order:

1. No open campaign for the chat → "This check has ended."
2. Not a member (`getChatMember` status not member/restricted/admin/owner) →
   "You're not in this group."
3. An admin → "Admins don't need to fill the form."
4. A `RECHECK` or `UPDATE` session for this chat already open → resume it (re-send the
   current question, as `/start` does).
5. A `RECHECK` submission pending review → "Your answers are waiting for the admins' review."
6. `passed_at` set → "You've already filled the form for <group>. Want to update your
   answers?" with a **Yes, update** button (`u|<chatId>`). Tapping it starts an
   `UPDATE` session. Ignoring it does nothing.
7. Otherwise a `RECHECK` session starts on the group's current form.

The tap also adds the user to the roster (§4).

A re-pass session opens with "Form for <group title>" before the form's welcome text.
If another form is active, the new session queues (`WAITING`) as today, and the user
is told "You'll get <group>'s form after you finish the current one."

A re-pass session that idles 7 days is deleted by `Purge`; the user is told "Form
closed; tap the button in the group to start again." No submission, no join request
to decline. An unreachable user (403 mid-form) has their session deleted; no
submission goes to admins.

## 3. Review

`submission.kind`: `JOIN` (today), `RECHECK`, `UPDATE`. `form_session.kind` carries it
until submit.

**RECHECK** — review copies to every decider, as for joins.

- Header "Member check: <name> in <group>".
- Buttons **Approve** / **Remove** (`r|<id>|a`, `r|<id>|j`); the first click wins via
  the existing conditional update.
- Approve: no Telegram call; sets the member's `passed_at`; DMs "You're all set in <group>."
- Remove: `banChatMember` then `unbanChatMember(only_if_banned)`. DMs "The admins
  removed you from <group>. You can ask to join again." Deletes the roster row.
- Kick results map onto `Decision`: OK; user not in the chat → `WITHDRAWN`; missing
  ban right → revert, alert "The bot can't remove members: give it the Ban users
  right."; anything else → revert, "try again" alert.

**UPDATE** — saved as `APPROVED` at submit. Admins get "Updated answers: <name> in
<group>" without buttons.

**JOIN** — unchanged, except an approval sets `passed_at` (creating the roster row).

The Mini App submissions list shows the kind as a label: Join / Check / Update.

## 4. Roster

`member` holds one row per member the bot has seen in a group. Sources:

1. `chat_member` updates: joined → upsert; left or kicked → delete.
2. Any message in the group from a non-bot user.
3. `message_reaction` updates with a `user` (anonymous reactions carry none). A
   reaction to the `/remind` message thus counts as a check-in.
4. An approved `JOIN`, a `/start r<chatId>` tap.
5. Startup backfill: a row with `passed_at = decided_at` for each `APPROVED`
   submission that has none. Profiles are sealed with row-bound AAD, so this runs in
   Kotlin (reseal), not SQL. Idempotent.

`ALLOWED_UPDATES` gains `CHAT_MEMBER` and `MESSAGE_REACTION`; Telegram sends both only
when named.

Sources 2 and 3 skip the database when an in-memory set already holds `(chatId, userId)`.
The set is a cache only; a restart refills it from writes.
(`ponytail:` unbounded set; switch to a bounded LRU if memory shows up.)

Known gap: members who joined before deploy and never post, react, or tap the button
are never learned. The "knows N of M" count (§1, §5) makes the gap visible.

Group → supergroup migration moves `member` and `recheck` rows to the new id and
reseals member profiles under the new AAD, as `SessionRepo` already does for sessions.

## 5. Deadline

An hourly ticker beside `Purge`:

1. For each open campaign past its deadline: set `closed_at`, edit every group message
   of the campaign to drop the button and say "The check closed on <date>."
2. Non-responders: roster rows with no `passed_at`, no pending `RECHECK`, and not
   currently admins.
3. Each decider gets a DM: "Didn't fill the form for <group> by <date>. The bot knows
   N of M members; only those are listed." Then one **Remove <name>** button per person
   (`k|<chatId>|<userId>`), at most 50 per message, further messages as needed.
   None → "Everyone the bot knows in <group> has filled the form."
4. Remove: kick as in §3, delete the roster row, drop the button from the clicker's
   message. A click with no roster row left → "Already removed." Other admins' copies
   are not edited.
5. An admin the DM can't reach (403) is recorded in `recheck_notice` as not delivered;
   their next `/start` delivers the list, then marks it delivered. A new `/remind`
   clears the group's notices.

A pending `RECHECK` stays reviewable after closing. The link then answers "This check
has ended."

Group message ids for step 1 are kept in `recheck_message`.

## 6. Data (Flyway `V2`)

```sql
ALTER TABLE form_session ADD COLUMN kind TEXT NOT NULL DEFAULT 'JOIN';
ALTER TABLE submission ADD COLUMN kind TEXT NOT NULL DEFAULT 'JOIN';
CREATE TABLE recheck (chat_id BIGINT PRIMARY KEY REFERENCES group_chat(chat_id) ON DELETE CASCADE,
  deadline TIMESTAMP WITH TIME ZONE NOT NULL, started_by BIGINT NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL, closed_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE recheck_message (chat_id BIGINT NOT NULL REFERENCES recheck(chat_id) ON DELETE CASCADE,
  message_id BIGINT NOT NULL, PRIMARY KEY (chat_id, message_id));
CREATE TABLE recheck_notice (chat_id BIGINT NOT NULL REFERENCES recheck(chat_id) ON DELETE CASCADE,
  admin_id BIGINT NOT NULL, delivered BOOLEAN NOT NULL, PRIMARY KEY (chat_id, admin_id));
CREATE TABLE member (chat_id BIGINT NOT NULL REFERENCES group_chat(chat_id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL, profile BYTEA NOT NULL, passed_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (chat_id, user_id));
```

`member.profile` is the `Profile` JSON sealed with `aad(userId, chatId)`. A new `/remind`
after a closed campaign overwrites the `recheck` row and clears its messages and notices.

## 7. Failures

- Bot loses admin, or the group goes inactive: the campaign closes without a deadline
  list; open re-pass sessions get the group-gone notice via `closeForChat`.
- Bot loses Ban users mid-campaign: Remove alerts as in §3; status reverts.
- Retention purge deletes old submissions; `passed_at` on the roster keeps "already
  passed" beyond it.

## 8. Testing

Kotest with `FakeTelegram` and `TestClock`, as the existing suites.

- `/start r-100123` reaches the start handler with the payload intact (pins vendeli's
  parsing under `restrictSpacesInCommands`).
- `/remind`: non-admin ignored; no form / no ban right refused; duration parsing;
  second `/remind` re-posts and moves the deadline.
- Entry checks 1–7, the update offer, queuing behind another group's form.
- RECHECK approve / remove / already gone / no ban right / transient; UPDATE copy has
  no buttons; JOIN approval sets `passed_at`.
- Roster: chat_member join and leave, group message, reaction with and without user,
  backfill idempotence, migration reseal.
- Deadline: closes and edits group messages; lists only non-passed non-admins;
  50-button chunking; "Already removed"; undelivered list arrives on `/start`.
