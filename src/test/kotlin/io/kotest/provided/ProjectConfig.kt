package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import joinbot.FakeTelegramFaults

/** Kotest loads this class by name. */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(FakeTelegramFaults)
}
