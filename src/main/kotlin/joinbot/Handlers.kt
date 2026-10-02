package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.types.User

// Stub that proves KSP; Task 11 fills it in.
@CommandHandler(["/start"])
suspend fun start(user: User, bot: TelegramBot) {}
