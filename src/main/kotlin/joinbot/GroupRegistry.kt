package joinbot

/** Reacts to the bot being added, promoted, demoted or removed in a group. */
class GroupRegistry(private val groups: GroupRepo, private val sessions: SessionRepo, private val users: BotUserRepo, private val tg: Tg) {
    suspend fun onBotStatus(chatId: Long, title: String, isAdmin: Boolean, canInvite: Boolean) {
        val active = isAdmin && canInvite
        groups.upsert(chatId, title, active)
        if (active) return
        if (isAdmin) {
            // every my_chat_member update is a real admin action, so the reminder repeats
            tg.send(chatId, Texts.t(null, T.NEEDS_INVITE_RIGHT))
        } else {
            sessions.deleteForChat(chatId).forEach { tg.send(it.userId, Texts.t(it.lang, T.GROUP_GONE)) }
        }
    }
}
