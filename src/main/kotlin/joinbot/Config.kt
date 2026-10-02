package joinbot

data class Config(
    val botToken: String,
    val dbPath: String,
    val dbFileKey: String,
    val dbUserPw: String,
    val dataKeyset: String,
    /** The public HTTPS origin the reverse proxy serves the mini app on. Null: no server. */
    val miniAppUrl: String?,
    val miniAppPort: Int,
    val webDir: String,
) {
    /** Redacts the secrets; the generated one would print them verbatim. */
    override fun toString(): String =
        "Config(botToken=***, dbPath=$dbPath, dbFileKey=***, dbUserPw=***, dataKeyset=***, " +
            "miniAppUrl=$miniAppUrl, miniAppPort=$miniAppPort, webDir=$webDir)"
}

/** Reads configuration from [env]. Fails naming the offending variable, never quoting a value. */
fun loadConfig(env: (String) -> String?): Config {
    fun optional(name: String) = env(name)?.takeIf { it.isNotBlank() }
    fun required(name: String) = requireNotNull(optional(name)) { "$name environment variable is required" }
    return Config(
        botToken = required("BOT_TOKEN"),
        dbPath = optional("DB_PATH") ?: "data/joinbot",
        dbFileKey = required("DB_FILE_KEY"),
        dbUserPw = required("DB_USER_PW"),
        dataKeyset = required("DATA_KEYSET"),
        miniAppUrl = optional("MINIAPP_URL"),
        miniAppPort = optional("MINIAPP_PORT")?.let {
            requireNotNull(it.toIntOrNull()) { "MINIAPP_PORT must be an integer" }
        } ?: 8080,
        webDir = optional("WEB_DIR") ?: "web/dist",
    )
}
