package joinbot

/** Prints a fresh DATA_KEYSET; run with `./mvnw -q exec:java -Dexec.mainClass=joinbot.KeygenMainKt`. */
fun main() {
    println("DATA_KEYSET=${KeysetGen.aead()}")
    println()
    println("# Also set, to values of your own choosing:")
    println("# DB_FILE_KEY  DB_USER_PW  BOT_TOKEN")
}
