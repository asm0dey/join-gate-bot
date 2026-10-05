# Member check (`/remind`): design

Date: 2026-10-04, revised 2026-10-05 after grilling. Status: approved, awaiting plan.
Terms (check, passed member, non-responder, remove, decider) are defined in `CONTEXT.md`.

## Goal

Groups that adopted the bot already have members who never filled the form: they
joined before the bot, or before the group had a form. Admins want those members to
fill it, and to remove the ones who won't or whose answers don't pass.

Success: an admin sends `/remind` in the group; members tap a button and fill the form
in DM; deciders approve or remove each one with one click; at the deadline, deciders
get the non-responders, each with a Remove button. Passed members can update their
answers at any time.

### Decisions taken (with reasons)

| Decision | Choice | Rejected |
|---|---|---|
| Purpose | Re-vet: deciders review, rejecting removes | Collect answers only; collect + notify |
| Target | Members who haven't passed; passed members may update any time | Everyone re-fills |
| Passed | Per group, any form version; leaving the group forgets it | Per form version; kept after leaving |
| Entry | Group message with a deep-link URL button (`t.me/<bot>?start=r<chatId>`) | Callback button: the bot can't DM someone who never wrote to it |
| Update review | Saved as approved; deciders get an FYI copy without buttons; no rate cap | Full review (an update could get a vetted member removed); Mini App only |
| Remove | Ban then unban, so they may ask to join again | Permanent ban |
| Rights | Check decisions, `/remind`, Remove need Invite **and** Ban users | Invite only: lets an admin remove through the bot what they can't remove themselves |
| No-shows | Deadline; deciders get a list with per-person Remove buttons | Nothing; plain list; "Remove all" (blast radius) |
| Who is a member | Roster of user ids the bot has seen; names fetched live at the deadline | Stored encrypted names (PII on every active member, reseal on backfill and migration); responders-only list. The Bot API cannot list members |
| Removal trace | A Check row with status `REMOVED` and the deciding admin | Nothing recorded |
| Submissions view | Latest per person per group, then the status filter | Every row |
| Code shape | Extend `ApplicantFlow`/`ReviewService` with a submission `kind` | Parallel `RecheckFlow` (~400 duplicated lines that drift) |
| Delivery | One plan, tasks ordered so each leaves a working bot | Two slices |

## 1. Starting a check

`/remind [duration]` in the group.

- Only admins with both Invite users and Ban users rights; anyone else is ignored silently.
- Duration `<n>h` or `<n>d`, from 1 hour to 1 year; default 7 days. Out of range or
  unparseable → reply with the allowed range.
- Refused, with a reply naming the cause, when the group has no form or the bot lacks
  the Ban users right.
- Opens the group's check (`recheck` row) and posts: "Members who haven't filled the
  join form yet: please do it by <date>." with a URL button **Fill the form** →
  `t.me/<bot username>?start=r<chatId>`. The username comes from `getMe` at startup.
- The admin gets a DM: "The bot knows N of M members of <group>." N is the roster size,
  M is `getChatMemberCount`. A failed DM is ignored.
- If posting the group message fails, no check opens; the admin is told in DM if
  possible, otherwise it is logged.
- `/remind` while a check is open posts the message again. With a duration it also
  moves the deadline. Earlier messages keep their button: the link checks whether the
  check is open, not which message it came from.

## 2. Entering the form

Telegram shows a Start button for a `?start=` link even when the chat with the bot
already exists ([client spec](https://core.telegram.org/api/links)); pressing it sends
`/start r<chatId>`.

### From the check button: `/start r<chatId>`

Checked in order:

1. No open check for the chat → "This check has ended."
2. Not a member (`getChatMember` status not member/restricted/admin/owner) →
   "You're not in this group."
3. An admin → "Admins don't need to fill the form."
4. A Check or Update session for this chat already open → resume it (re-send the
   current question, as `/start` does).
5. A Check submission pending review → "Your answers are waiting for the deciders' review."
6. Passed member → "You've already filled the form for <group>. Want to update your
   answers?" with a **Yes, update** button (`u|<chatId>`).
7. Otherwise a Check session starts on the group's current form.

The tap adds the user to the roster (§4).

### Updating any time: plain `/start`

`/start` keeps today's order: resume an open form, else deliver pending review copies
(and undelivered deadline lists, §5). When neither applies and the user is a passed
member of at least one active group with a form, it replies "You can update your
answers for:" with one `u|<chatId>` button per such group, titled with the group name.
With none, today's "how to join" text.

### `u|<chatId>` (either path)

Re-checks: group active with a form, user still a member, still passed, no session for
that chat. Then starts an Update session; otherwise a short explanation.

### Session behaviour

A Check or Update session opens with "Form for <group title>" before the form's
welcome text. If another form is active, it queues (`WAITING`) as today, and the user
is told "You'll get <group>'s form after you finish the current one."

Idle 7 days → `Purge` deletes it and tells the user "Form closed; tap the button in
the group to start again." No submission, no join request to decline. A 403 mid-form
deletes the session; nothing goes to deciders.

## 3. Review

`submission.kind`: `JOIN` (today), `CHECK`, `UPDATE`. `form_session.kind` carries it
until submit. `Status` gains `REMOVED`.

**CHECK** — review copies to every decider with both rights.

- Header "Member check: <name> in <group>".
- Buttons **Approve** / **Remove** (`r|<id>|a`, `r|<id>|j`); the first click wins via
  the existing conditional update. Both re-check the clicker's rights fresh.
- Approve: no Telegram call; sets `passed_at`; DMs "You're all set in <group>."
- Remove: `banChatMember` then `unbanChatMember(only_if_banned)`; status `REJECTED`;
  DMs "The admins removed you from <group>. You can ask to join again."; deletes the
  roster row.
- Kick results map onto `Decision`: OK; user not in the chat → `WITHDRAWN`; bot lacks
  the ban right → revert, alert "The bot can't remove members: give it the Ban users
  right."; anything else → revert, "try again" alert.

**UPDATE** — saved as `APPROVED` at submit. Deciders (Invite right) get "Updated
answers: <name> in <group>" without buttons.

**JOIN** — unchanged, except approval sets `passed_at` (creating the roster row).

### Mini App and CSV

- Both show only the latest submission per person per group; the status filter applies
  after picking the latest. Older rows stay in the database until retention purges them.
- The list shows the kind as a label: Join / Check / Update.
- CSV gains `kind` and `form_version` columns.

## 4. Roster

`member` holds `(chat_id, user_id, passed_at)` per member the bot has seen. No names.
Sources:

1. `chat_member` updates: joined → upsert; left or kicked → delete (passed is forgotten).
2. Any message in the group from a non-bot user.
3. `message_reaction` updates with a `user` (anonymous reactions carry none). A reaction
   to the `/remind` message thus counts as a check-in.
4. An approved Join, a `/start r<chatId>` tap.
5. Flyway backfill (plain SQL, nothing sealed): a row with `passed_at = decided_at` for
   each distinct `(chat_id, user_id)` with an `APPROVED` submission.

`ALLOWED_UPDATES` gains `CHAT_MEMBER` and `MESSAGE_REACTION`; Telegram sends both only
when named.

Sources 2 and 3 skip the database when an in-memory set already holds `(chatId, userId)`.
`ponytail:` unbounded set; switch to a bounded LRU if memory shows up.

Known gap: members who joined before deploy and never post, react, or tap the button
are never learned. "Knows N of M" (§1, §5) shows the gap's size.

Group → supergroup migration moves `member` and `recheck` rows to the new chat id.

## 5. Deadline

An hourly ticker beside `Purge`:

1. For each open check past its deadline: set `closed_at`; edit every group message of
   the check to drop the button and say "The check closed on <date>."
2. Non-responders: roster rows with no `passed_at` and no pending Check submission.
   For each, `getChatMember`: not in the group → delete the row; admin → skip;
   otherwise the live name and username go on the list.
3. Each decider with both rights gets a DM: "Didn't fill the form for <group> by <date>.
   The bot knows N of M members; only those are listed." Then one **Remove <name>**
   button per person (`k|<chatId>|<userId>`), at most 50 per message, more messages as
   needed. None → "Everyone the bot knows in <group> has filled the form."
4. Remove click, after a fresh rights check on the clicker:
   - passed since → "<name> has filled the form since."; pending Check → "<name>'s
     answers are waiting for review."; no roster row → "Already removed."
   - otherwise kick as in §3, delete the roster row, insert a `CHECK` submission with
     status `REMOVED`, no answers, `decided_by` = clicker, profile from `getChatMember`;
     drop the button from the clicker's message. Other deciders' copies are not edited.
5. A decider the DM can't reach (403) is recorded in `recheck_notice` as undelivered;
   their next `/start` delivers the list, then marks it delivered. A new `/remind`
   clears the group's notices.

A pending Check stays reviewable after closing. The link then answers "This check has ended."

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
  user_id BIGINT NOT NULL, passed_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (chat_id, user_id));
INSERT INTO member (chat_id, user_id, passed_at)
  SELECT chat_id, user_id, MAX(decided_at) FROM submission WHERE status = 'APPROVED' GROUP BY chat_id, user_id;
```

A new `/remind` after a closed check overwrites the `recheck` row and clears its
messages and notices.

## 7. Failures

- Bot loses admin, or the group goes inactive: the check closes without a deadline list;
  open Check and Update sessions get the group-gone notice via `closeForChat`.
- Bot loses Ban users mid-check: Remove alerts as in §3; status reverts.
- Retention purges old submissions; `passed_at` on the roster keeps "passed" beyond it.

## 8. Testing

Kotest with `FakeTelegram` and `TestClock`, as the existing suites.

- `/start r-100123` reaches the start handler with the payload intact (pins vendeli's
  parsing under `restrictSpacesInCommands`).
- `/remind`: non-admin and invite-only admin ignored; no form / no ban right refused;
  duration bounds; second `/remind` re-posts and moves the deadline.
- Entry checks 1–7; update offer; plain `/start` update buttons for several groups;
  `u|` re-checks; queuing behind another group's form.
- CHECK approve / remove / already gone / no ban right / transient; UPDATE copy has no
  buttons; JOIN approval sets `passed_at`.
- Roster: chat_member join and leave, group message, reaction with and without user,
  backfill, migration.
- Deadline: closes and edits group messages; drops leavers and admins via
  `getChatMember`; 50-button chunking; Remove refusals; `REMOVED` row; undelivered list
  arrives on `/start`.
- Mini App / CSV: latest per person, then filter; `kind` and `form_version` columns.
