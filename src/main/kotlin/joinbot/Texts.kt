package joinbot

import java.util.Locale

// Keys with String.format arguments (all others take none):
//   NUDGE(count: Int), GROUP_HANDOFF(count: Int)
//   FORM_FOR(groupTitle), QUEUED(groupTitle)
//   DECIDED_BY_APPROVED(name), DECIDED_BY_REJECTED(name), ALREADY_DECIDED(name)
//   REVIEW_HEADER(applicantName, groupTitle), CHECK_HEADER(applicantName, groupTitle), UPDATE_HEADER(applicantName, groupTitle), REVIEW_UNREACHABLE(applicantName)
//   DECIDED_BY_REMOVED(name), CHECK_APPROVED_USER(groupTitle), CHECK_REMOVED_USER(groupTitle)
//   REMIND_POST(date), REMIND_KNOWS(known: Int, total: Int, groupTitle), REMIND_POST_FAILED(groupTitle), OFFER_UPDATE(groupTitle)
//   INVALID_TOO_FEW(min: Int), INVALID_TOO_MANY(max: Int), MULTI_EXACT(n: Int), MULTI_RANGE(min: Int, max: Int), MULTI_UP_TO(max: Int)
// AN_ADMIN is the decider name when they are no longer an admin.
// Always formatted, so a forgotten arg throws. Avoid a literal percent sign in any text.

enum class T {
    WELCOME_SKIP, SKIP, OTHER, DONE, AGREE, DISAGREE, SUBMIT, START_OVER, SUMMARY_HEADER, INVALID_REQUIRED, INVALID_TOO_LONG,
    INVALID_NOT_A_NUMBER, INVALID_TOO_SMALL, INVALID_TOO_LARGE, INVALID_NOT_A_LINK, INVALID_TOO_FEW, INVALID_TOO_MANY, INVALID_WRONG_KIND,
    TYPE_OTHER, SUBMITTED, DECLINED_CONSENT, EXPIRED, GROUP_GONE, STALE_BUTTON, HOW_TO_JOIN, APPROVED_USER, REJECTED_USER,
    REVIEW_HEADER, REVIEW_UNREACHABLE, APPROVE, REJECT, DECIDED_BY_APPROVED, DECIDED_BY_REJECTED, ALREADY_DECIDED, NOT_ADMIN_ANYMORE,
    TRY_AGAIN, WITHDRAWN, AN_ADMIN, NUDGE, NEEDS_INVITE_RIGHT, EXPORT_READY,
    MANUAL_REVIEW, REVIEW_SUSPENDED, GROUP_HANDOFF, MULTI_EXACT, MULTI_RANGE, MULTI_UP_TO,
    FORM_FOR, QUEUED, CHECK_FORM_CLOSED, UPDATE_SAVED, FORM_CLOSED,
    CHECK_HEADER, UPDATE_HEADER, REMOVE, DECIDED_BY_REMOVED, CHECK_APPROVED_USER, CHECK_REMOVED_USER, NO_BAN_RIGHT,
    REMIND_POST, FILL_FORM, REMIND_KNOWS, REMIND_BAD_DURATION, REMIND_NO_FORM, REMIND_NO_BAN, REMIND_ANONYMOUS, REMIND_POST_FAILED,
    CHECK_ENDED, NOT_IN_GROUP, ADMINS_EXEMPT, CHECK_PENDING, OFFER_UPDATE, YES_UPDATE, UPDATE_LIST,
}

private val en = mapOf(
    T.WELCOME_SKIP to "Optional questions have a Skip button.",
    T.SKIP to "Skip",
    T.OTHER to "Other",
    T.DONE to "Done",
    T.AGREE to "I agree",
    T.DISAGREE to "I disagree",
    T.SUBMIT to "Submit",
    T.START_OVER to "Start over",
    T.SUMMARY_HEADER to "Your answers:",
    T.INVALID_REQUIRED to "This answer is required.",
    T.INVALID_TOO_LONG to "That is too long. Please shorten it.",
    T.INVALID_NOT_A_NUMBER to "Please send a whole number.",
    T.INVALID_TOO_SMALL to "That number is too small.",
    T.INVALID_TOO_LARGE to "That number is too large.",
    T.INVALID_NOT_A_LINK to "Please send a full link starting with http:// or https://.",
    T.INVALID_TOO_FEW to "Choose at least %d.",
    T.INVALID_TOO_MANY to "Choose at most %d.",
    T.MULTI_EXACT to "Choose %d.",
    T.MULTI_RANGE to "Choose %d to %d.",
    T.MULTI_UP_TO to "Choose up to %d.",
    T.INVALID_WRONG_KIND to "Please use the buttons or send text, as the question asks.",
    T.TYPE_OTHER to "Type your answer.",
    T.SUBMITTED to "Thanks! Your answers were sent to the admins. You will get a message when they decide.",
    T.DECLINED_CONSENT to "Your join request was declined because you did not agree. You can request to join again.",
    T.EXPIRED to "This form timed out and your join request was declined. Request to join again to start over.",
    T.GROUP_GONE to "This form is closed. The group's admins will review your join request directly in Telegram.",
    T.STALE_BUTTON to "This button is out of date.",
    T.HOW_TO_JOIN to "Request to join a group, and I will send you its questions here.",
    T.APPROVED_USER to "You were approved. Welcome!",
    T.REJECTED_USER to "Sorry, your join request was rejected.",
    T.REVIEW_HEADER to "Join request from %s to %s",
    T.REVIEW_UNREACHABLE to "Could not message %s, so the answers are missing or incomplete. Decide without them?",
    T.APPROVE to "Approve",
    T.REJECT to "Reject",
    T.DECIDED_BY_APPROVED to "Approved by %s",
    T.DECIDED_BY_REJECTED to "Rejected by %s",
    T.ALREADY_DECIDED to "Already decided by %s.",
    T.NOT_ADMIN_ANYMORE to "You can no longer decide for this group.",
    T.TRY_AGAIN to "Something went wrong. Please try again.",
    T.WITHDRAWN to "The applicant withdrew or already joined.",
    T.AN_ADMIN to "an admin",
    T.NUDGE to "%d join requests are waiting. Admins, please open a chat with this bot and press Start.",
    T.NEEDS_INVITE_RIGHT to "I need the Invite users right to handle join requests.",
    T.EXPORT_READY to "Your export is ready.",
    T.MANUAL_REVIEW to "The group's admins will review your join request directly in Telegram.",
    T.REVIEW_SUSPENDED to "I can no longer approve requests here — use the group's Join requests list.",
    T.GROUP_HANDOFF to "I can't approve join requests any more. %d request(s) wait in this group's Join requests list.",
    T.FORM_FOR to "Form for %s",
    T.QUEUED to "You'll get %s's form after you finish the current one.",
    T.CHECK_FORM_CLOSED to "Form closed. Tap the button in the group to start again.",
    T.UPDATE_SAVED to "Thanks! Your updated answers were saved.",
    T.FORM_CLOSED to "This form is closed.",
    T.CHECK_HEADER to "Member check: %s in %s",
    T.UPDATE_HEADER to "Updated answers: %s in %s",
    T.REMOVE to "Remove",
    T.DECIDED_BY_REMOVED to "Removed by %s",
    T.CHECK_APPROVED_USER to "You're all set in %s.",
    T.CHECK_REMOVED_USER to "The admins removed you from %s. You can ask to join again.",
    T.NO_BAN_RIGHT to "The bot can't remove members: give it the Ban users right.",
    T.REMIND_POST to "Members who haven't filled the join form yet: please do it by %s.",
    T.FILL_FORM to "Fill the form",
    T.REMIND_KNOWS to "The bot knows %d of %d members of %s.",
    T.REMIND_BAD_DURATION to "Use a duration from 1h to 365d, like 7d or 48h.",
    T.REMIND_NO_FORM to "This group has no form yet.",
    T.REMIND_NO_BAN to "I need the Ban users right to run a check.",
    T.REMIND_ANONYMOUS to "Anonymous admins can't start a check; post as yourself.",
    T.REMIND_POST_FAILED to "I couldn't post the check message in %s.",
    T.CHECK_ENDED to "This check has ended.",
    T.NOT_IN_GROUP to "You're not in this group.",
    T.ADMINS_EXEMPT to "Admins don't need to fill the form.",
    T.CHECK_PENDING to "Your answers are waiting for the deciders' review.",
    T.OFFER_UPDATE to "You've already filled the form for %s. Want to update your answers?",
    T.YES_UPDATE to "Yes, update",
    T.UPDATE_LIST to "You can update your answers for:",
)

private val ru = mapOf(
    T.WELCOME_SKIP to "У необязательных вопросов есть кнопка «Пропустить».",
    T.SKIP to "Пропустить",
    T.OTHER to "Другое",
    T.DONE to "Готово",
    T.AGREE to "Согласен",
    T.DISAGREE to "Не согласен",
    T.SUBMIT to "Отправить",
    T.START_OVER to "Начать заново",
    T.SUMMARY_HEADER to "Ваши ответы:",
    T.INVALID_REQUIRED to "На этот вопрос нужно ответить.",
    T.INVALID_TOO_LONG to "Слишком длинно. Сократите, пожалуйста.",
    T.INVALID_NOT_A_NUMBER to "Пришлите целое число.",
    T.INVALID_TOO_SMALL to "Число слишком маленькое.",
    T.INVALID_TOO_LARGE to "Число слишком большое.",
    T.INVALID_NOT_A_LINK to "Пришлите полную ссылку, начинающуюся с http:// или https://.",
    T.INVALID_TOO_FEW to "Выберите не меньше %d.",
    T.INVALID_TOO_MANY to "Можно выбрать не больше %d.",
    T.MULTI_EXACT to "Выберите %d.",
    T.MULTI_RANGE to "Выберите от %d до %d.",
    T.MULTI_UP_TO to "Выберите до %d.",
    T.INVALID_WRONG_KIND to "Ответьте так, как просит вопрос: кнопкой или текстом.",
    T.TYPE_OTHER to "Напишите свой ответ.",
    T.SUBMITTED to "Спасибо! Ответы отправлены администраторам. Когда они решат, вам придёт сообщение.",
    T.DECLINED_CONSENT to "Заявка отклонена, потому что вы не согласились. Можно подать заявку снова.",
    T.EXPIRED to "Время на анкету вышло, заявка отклонена. Подайте заявку снова, чтобы начать заново.",
    T.GROUP_GONE to "Анкета закрыта. Администраторы группы рассмотрят вашу заявку прямо в Telegram.",
    T.STALE_BUTTON to "Эта кнопка устарела.",
    T.HOW_TO_JOIN to "Подайте заявку на вступление в группу, и я пришлю сюда её вопросы.",
    T.APPROVED_USER to "Вас приняли. Добро пожаловать!",
    T.REJECTED_USER to "К сожалению, заявку отклонили.",
    T.REVIEW_HEADER to "Заявка от %s в %s",
    T.REVIEW_UNREACHABLE to "Не удалось написать %s, поэтому ответов нет или они неполные. Решить без них?",
    T.APPROVE to "Принять",
    T.REJECT to "Отклонить",
    T.DECIDED_BY_APPROVED to "Принял(а): %s",
    T.DECIDED_BY_REJECTED to "Отклонил(а): %s",
    T.ALREADY_DECIDED to "Уже решил(а): %s.",
    T.NOT_ADMIN_ANYMORE to "Вы больше не можете решать по этой группе.",
    T.TRY_AGAIN to "Что-то пошло не так. Попробуйте ещё раз.",
    T.WITHDRAWN to "Заявитель отозвал заявку или уже вступил.",
    T.AN_ADMIN to "администратор",
    T.NUDGE to "Заявок на вступление в ожидании: %d. Администраторы, откройте чат с этим ботом и нажмите Start.",
    T.NEEDS_INVITE_RIGHT to "Мне нужно право «Приглашать пользователей», чтобы обрабатывать заявки.",
    T.EXPORT_READY to "Экспорт готов.",
    T.MANUAL_REVIEW to "Администраторы группы рассмотрят вашу заявку прямо в Telegram.",
    T.REVIEW_SUSPENDED to "Я больше не могу принимать заявки здесь — используйте список «Заявки на вступление» в группе.",
    T.GROUP_HANDOFF to "Я больше не могу принимать заявки на вступление. В списке «Заявки на вступление» этой группы ждут заявок: %d.",
    T.FORM_FOR to "Анкета для %s",
    T.QUEUED to "Анкета группы %s придёт после того, как вы закончите текущую.",
    T.CHECK_FORM_CLOSED to "Анкета закрыта. Нажмите кнопку в группе, чтобы начать заново.",
    T.UPDATE_SAVED to "Спасибо! Обновлённые ответы сохранены.",
    T.FORM_CLOSED to "Эта анкета закрыта.",
    T.CHECK_HEADER to "Проверка участника: %s в %s",
    T.UPDATE_HEADER to "Обновлённые ответы: %s в %s",
    T.REMOVE to "Исключить",
    T.DECIDED_BY_REMOVED to "Исключил(а): %s",
    T.CHECK_APPROVED_USER to "Всё в порядке, вы остаётесь в %s.",
    T.CHECK_REMOVED_USER to "Администраторы исключили вас из %s. Можно подать заявку снова.",
    T.NO_BAN_RIGHT to "Бот не может исключать участников: дайте ему право «Блокировать пользователей».",
    T.REMIND_POST to "Участники, которые ещё не заполнили анкету группы: пожалуйста, заполните её до %s.",
    T.FILL_FORM to "Заполнить анкету",
    T.REMIND_KNOWS to "Бот знает %d из %d участников %s.",
    T.REMIND_BAD_DURATION to "Укажите срок от 1h до 365d, например 7d или 48h.",
    T.REMIND_NO_FORM to "У этой группы пока нет анкеты.",
    T.REMIND_NO_BAN to "Чтобы провести проверку, мне нужно право «Блокировать пользователей».",
    T.REMIND_ANONYMOUS to "Анонимные администраторы не могут начать проверку; напишите от своего имени.",
    T.REMIND_POST_FAILED to "Не удалось опубликовать сообщение о проверке в %s.",
    T.CHECK_ENDED to "Эта проверка завершена.",
    T.NOT_IN_GROUP to "Вы не состоите в этой группе.",
    T.ADMINS_EXEMPT to "Администраторам не нужно заполнять анкету.",
    T.CHECK_PENDING to "Ваши ответы ждут решения администраторов.",
    T.OFFER_UPDATE to "Вы уже заполнили анкету для %s. Хотите обновить ответы?",
    T.YES_UPDATE to "Да, обновить",
    T.UPDATE_LIST to "Вы можете обновить ответы для:",
)

object Texts {
    /** `ru*` language codes get Russian; everything else, including null, gets English. */
    fun t(lang: String?, key: T, vararg args: Any): String {
        val template = (if (lang?.startsWith("ru") == true) ru else en).getValue(key)
        return template.format(Locale.ROOT, *args)
    }
}

/** BAD_OPTION has no text of its own: a bad option index only comes from a stale or forged button. */
fun Reason.text(): T = when (this) {
    Reason.REQUIRED -> T.INVALID_REQUIRED
    Reason.TOO_LONG -> T.INVALID_TOO_LONG
    Reason.NOT_A_NUMBER -> T.INVALID_NOT_A_NUMBER
    Reason.TOO_SMALL -> T.INVALID_TOO_SMALL
    Reason.TOO_LARGE -> T.INVALID_TOO_LARGE
    Reason.NOT_A_LINK -> T.INVALID_NOT_A_LINK
    Reason.TOO_FEW -> T.INVALID_TOO_FEW
    Reason.TOO_MANY -> T.INVALID_TOO_MANY
    Reason.BAD_OPTION -> T.STALE_BUTTON
    Reason.WRONG_KIND -> T.INVALID_WRONG_KIND
}
