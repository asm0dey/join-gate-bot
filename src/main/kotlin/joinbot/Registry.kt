package joinbot

import eu.vendeli.tgbot.TelegramBot

/** Set once in main(); the framework invokes top-level handler functions, so they reach their services here. */
object Registry {
    lateinit var flow: ApplicantFlow
    lateinit var review: ReviewService
    lateinit var registry: GroupRegistry
    lateinit var users: BotUserRepo
    lateinit var bot: TelegramBot
}
