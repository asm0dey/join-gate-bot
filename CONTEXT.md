# Join gate

A Telegram bot that guards groups: people who want in, and members already in, fill the group's form, and the group's admins decide.

## Language

### People

**Applicant**:
Someone who asked to join a guarded group and is filling or has filled its form.
_Avoid_: candidate, requester

**Member**:
Someone already in a guarded group.
_Avoid_: participant, user

**Passed member**:
A member with an approved Join or Check submission in that group. Passing is per group, not per form version.
_Avoid_: verified, vetted

**Decider**:
An admin of the group who may decide its submissions: one who can invite users decides Join submissions; one who can also ban users decides Check submissions, starts checks and removes non-responders.
_Avoid_: reviewer, moderator

### Forms and answers

**Form**:
The questions a group's admins define; one per group, versioned on every save.
_Avoid_: questionnaire, survey

**Submission**:
One filled form sent to the deciders. Its kind is Join, Check or Update.
_Avoid_: application, response

**Join submission**:
A submission from an applicant; approving it lets them into the group.

**Check submission**:
A submission from a member who hadn't passed; approving it makes them a passed member, rejecting it removes them.

**Update submission**:
New answers from a passed member. Deciders see it but don't decide it.

### Checks

**Check**:
A period, started by an admin, in which members who haven't passed are asked to fill the form, ending at its deadline.
_Avoid_: re-pass, re-fill, recheck, campaign, reminder

**Non-responder**:
A member the bot knows of who hadn't passed when a check's deadline came.
_Avoid_: no-show

**Remove**:
Take a member out of the group so that they may ask to join again.
_Avoid_: kick, ban
