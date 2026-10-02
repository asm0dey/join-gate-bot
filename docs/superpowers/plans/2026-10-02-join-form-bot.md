# Join-form bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A multi-group Telegram bot that answers every join request with an admin-defined form in DM and lets any group admin approve or reject it with one click.

**Architecture:** One Kotlin process: vendeli long polling plus a Ktor CIO Mini App server, both calling plain service classes that talk to Telegram only through a small `Tg` port and to H2 through Exposed repositories. Bun builds the Svelte admin UI into `web/dist` outside Maven; Ktor serves it from the filesystem. SDKMAN pins Java, mise pins Bun and is the single entry point (`mise run build`).

**Tech Stack:** Maven 3.10 (wrapper) + `me.kpavlov.ksp.maven:ksp-maven-plugin` 0.4.7 for KSP, Kotlin 2.4.20, Java 27 (Liberica, via SDKMAN `.sdkmanrc`), `eu.vendeli:telegram-bot` 9.6.0 + ktnip KSP, Ktor 3.6 (CIO server and client), H2 2.5 (`MODE=PostgreSQL`, file cipher) + Flyway 13.8 + Exposed 1.5, Google Tink 1.23, tinylog 2.8, kotest 6.2; web: Bun 1.4.2, Svelte 5, Vite 8, Tailwind 4 + daisyUI 5.

**Spec:** `docs/superpowers/specs/2026-10-02-join-form-bot-design.md`

**Reference repo:** `../exchange-bot` (sibling checkout). Tasks that say "copy from exchange-bot" mean copy the file, rename package `fxbot` → `joinbot`, and remove what the task says to remove.

## Global Constraints

- Package `joinbot`, one Maven module (`pom.xml`, artifact `join-gate-bot`), sources in `src/main/kotlin/joinbot/`, tests in `src/test/kotlin/joinbot/`.
- `.sdkmanrc` pins `java=27.0.0+36-librca`; `mise.toml` pins `bun = "1.4.2"`; mise reads `.sdkmanrc` too (`idiomatic_version_file_enable_tools = ["java"]`), so `mise-action` alone sets up CI; `mise run build` is what CI runs and must stay green after every task.
- Maven is an experiment (recorded in precedent): no fat jar; `package` writes `target/join-gate-bot.jar` plus `target/lib/*.jar` (dependency plugin), which is the image's input. Versions are `<properties>` in `pom.xml`, copied from exchange-bot's `gradle/libs.versions.toml`.
- H2 URL `jdbc:h2:file:<DB_PATH>;CIPHER=AES;MODE=PostgreSQL`; Flyway owns the schema; tests use in-memory H2 in PG mode.
- 🔒 columns are Tink AEAD (`BYTEA`) with associated data `"<table>|<row key>"`, e.g. `"submission|42"`, `"form_session|<user>|<chat>"`.
- Never log user ids, chat ids, names, usernames, answers, or exception messages; only counts, outcome labels and exception class names. Copy `tinylog.properties` from exchange-bot.
- Bot messages are sent **without** `parse_mode` (plain text), so admin- and user-written text never needs escaping.
- Texts in en + ru, picked by Telegram `language_code` (`ru*` → ru, everything else → en).
- Env: `BOT_TOKEN`, `MINIAPP_URL`, `MINIAPP_PORT` (default 8080), `WEB_DIR` (default `web/dist`), `DATA_KEYSET`, `DB_FILE_KEY`, `DB_USER_PW`, `DB_PATH` (default `data/joinbot`).
- Callback data must stay under 64 bytes: applicant `f|<chat>|<step>|<action>|<idx>`, review `r|<submissionId>|<a|j>`.
- Limits enforced by `validateForm`: at most 50 fields, at most 20 options per field, option label at most 64 chars, prompt at most 1000 chars, welcome at most 2000 chars.
- Work on branch `feat/v1`; one commit per task (message given in the task).

## Review Focus

1. **Non-text message during a typed question** (photo, sticker, voice): the applicant expects the same question again with "please answer with text", not silence or a crash. Test in Task 8.
2. **Stale button press** (an old message's keyboard after the session advanced, restarted, or was deleted): the press must change nothing and answer the callback with "this question is no longer active". Test in Task 8.
3. **Summary longer than 4096 chars** (many long text answers): the summary must still arrive; split it into several messages with the buttons on the last one. Test in Task 8.
4. **Second join request to the same group** while a session or `PENDING` submission exists (user cancelled and re-requested): no duplicate-key crash. An existing session restarts from step 0, and an existing `PENDING` submission is left as it is with no new form. Test in Task 8.
5. **Text containing `<b>`, `_`, `*`** in a prompt or an answer: it must arrive literally, never as formatting and never as a Telegram 400. Test in Task 5 (adapter sends no `parse_mode`).

---

### Task 1: Toolchain and Maven skeleton

**Files:**
- Create: `mise.toml` (`.sdkmanrc` already exists), `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*`, `.gitignore`
- Create: `src/main/kotlin/joinbot/Config.kt`, `src/main/kotlin/joinbot/Main.kt`, `src/main/kotlin/joinbot/Handlers.kt` (one stub handler, proves KSP), `src/main/resources/tinylog.properties`
- Test: `src/test/kotlin/joinbot/ConfigTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Config(
      val botToken: String, val dbPath: String, val dbFileKey: String, val dbUserPw: String,
      val dataKeyset: String, val miniAppUrl: String?, val miniAppPort: Int, val webDir: String,
  )  // toString() redacts botToken, dbFileKey, dbUserPw, dataKeyset as "***" (exchange-bot R8)
  fun loadConfig(env: (String) -> String?): Config  // throws IllegalArgumentException naming the missing var
  ```

- [ ] **Step 1: Write `mise.toml`**

```toml
[settings]
idiomatic_version_file_enable_tools = ["java"]   # take Java from .sdkmanrc

[tools]
bun = "1.4.2"

[tasks."backend:test"]
run = "./mvnw -B test"

[tasks."backend:build"]
run = "./mvnw -B verify"        # compile, KSP, tests, jar + target/lib

[tasks.test]
depends = ["backend:test"]

[tasks.build]
depends = ["backend:build"]
```

Task 12 adds the `web:*` tasks to both `depends` lists.

- [ ] **Step 2: Generate the wrapper**

Run: `mise install && mise ls --current java && mise x maven@3.10.0 -- mvn -N -q wrapper:wrapper -Dmaven=3.10.0 && ./mvnw -v`
Expected: `java liberica-27.0.0+36 …/.sdkmanrc`, then `Apache Maven 3.10.0` and `Java version: 27`.

- [ ] **Step 3: Write `pom.xml`**

- Coordinates `joinbot:join-gate-bot:0-SNAPSHOT`, `<build><finalName>join-gate-bot</finalName>`, `sourceDirectory` `src/main/kotlin`, `testSourceDirectory` `src/test/kotlin`.
- Dependencies: exchange-bot's list minus db-scheduler and ktnip; kotest runner/assertions/property, ktor-client-mock and ktor-server-test-host in `test` scope.
- `kotlin-maven-plugin` `${kotlin.version}`: `jvmTarget` 27, `compilerPlugins` `kotlinx-serialization` (plugin dependency `org.jetbrains.kotlin:kotlin-maven-serialization`). If Kotlin 2.4.20 rejects target 27, set `jvmTarget` 25 and keep running on 27.
- `me.kpavlov.ksp.maven:ksp-maven-plugin` 0.4.7, `process` goal, with plugin dependency `eu.vendeli:ktnip:${telegrambot.version}` and option `package=joinbot` (vendeli's KSP arg, see its "Activities and Processors" wiki).
- `maven-surefire-plugin` 3.x (JUnit Platform runs kotest).
- `maven-dependency-plugin` `copy-dependencies` at `package` → `target/lib` (runtime scope).
- `exec-maven-plugin` with default `mainClass` `joinbot.MainKt`, for `./mvnw -q exec:java` (run) and `-Dexec.mainClass=joinbot.KeygenMainKt` (keygen, Task 2).
- `.gitignore`: `target/`, `.kotlin/`, `data/`, `web/node_modules/`, `web/dist/`.
- `Handlers.kt`: `@CommandHandler(["/start"]) suspend fun start(user: User, bot: TelegramBot) {}`, a stub that Task 11 fills in.
- `tinylog.properties`: copy from exchange-bot unchanged.

- [ ] **Step 4: Write the failing test `ConfigTest`**

```kotlin
class ConfigTest : StringSpec({
    val full = mapOf("BOT_TOKEN" to "1:x", "DATA_KEYSET" to "{}", "DB_FILE_KEY" to "k", "DB_USER_PW" to "p")
    "defaults" { loadConfig(full::get).run {
        miniAppPort shouldBe 8080; webDir shouldBe "web/dist"; dbPath shouldBe "data/joinbot"; miniAppUrl shouldBe null } }
    "missing required var names it" { listOf("BOT_TOKEN", "DATA_KEYSET", "DB_FILE_KEY", "DB_USER_PW").forEach { k ->
        shouldThrow<IllegalArgumentException> { loadConfig((full - k)::get) }.message shouldContain k } }
    "toString redacts secrets" { loadConfig(full::get).toString().run {
        shouldNotContain("1:x"); shouldNotContain("\"k\""); shouldContain("***") } }
    "bad MINIAPP_PORT rejected" { shouldThrow<IllegalArgumentException> { loadConfig((full + ("MINIAPP_PORT" to "x"))::get) } }
})
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./mvnw -q test -Dtest='ConfigTest'`
Expected: compilation FAIL, `Unresolved reference: loadConfig`.

- [ ] **Step 6: Implement `Config`/`loadConfig` in `Config.kt`, and a `Main.kt` whose `main()` calls `loadConfig(System::getenv)` and returns**

- [ ] **Step 7: Run `mise run build`, then check that KSP ran**

Run: `mise run build && find target -name ActivitiesData.kt`
Expected: `BUILD SUCCESS`, `ConfigTest` 4 passed, and one path printed (ktnip's generated registry, `eu/vendeli/tgbot/generated/ActivitiesData.kt`).
If ktnip can't run under the Maven plugin (no file, or a KSP error), stop the experiment's KSP half: remove `ksp-maven-plugin`, delete the stub annotation, and do Task 11 with vendeli's functional DSL (`bot.setFunctionality { onCommand("/start") {…}; onUpdate(UpdateType.CHAT_JOIN_REQUEST) {…}; onUpdate(UpdateType.MY_CHAT_MEMBER) {…}; whenNotHandled {…} }`). Note the outcome in the commit message.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "build: mise toolchain, Maven skeleton with KSP, Config"
```

---

### Task 2: Crypto, keygen, database and schema

**Files:**
- Create: `src/main/kotlin/joinbot/Crypto.kt`, `KeygenMain.kt`, `Db.kt`, `Tables.kt`, `src/main/resources/db/migration/V1__initial.sql`
- Test: `src/test/kotlin/joinbot/CryptoTest.kt`, `DbTest.kt`, `TestDb.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class Crypto(dataKeysetJson: String) {      // exchange-bot Crypto minus the MAC keyset, ref(), newRefToken()
      fun seal(plaintext: String, aad: String): ByteArray
      fun open(ciphertext: ByteArray, aad: String): String
  }
  object KeysetGen { fun aead(): String }
  fun createDataSource(cfg: Config): HikariDataSource   // copy from exchange-bot
  fun migrate(ds: DataSource)                          // copy
  fun connectExposed(ds: DataSource): Database         // copy, keep preserveKeywordCasing=false, defaultMaxAttempts=1
  fun verifyKeyset(db: Database, crypto: Crypto)       // canary: seal on first run, open on later runs; throws IllegalStateException on mismatch
  // Tables.kt: Exposed `object`s GroupChats, Forms, FormSessions, Submissions, ReviewMessages, BotUsers, Canary mirroring V1
  // test: fun memDataSource(name: String): HikariDataSource; fun testCrypto(): Crypto; fun testDb(name: String): Database (migrated)
  ```

- [ ] **Step 1: Write `V1__initial.sql`**

```sql
CREATE TABLE group_chat (chat_id BIGINT PRIMARY KEY, title TEXT NOT NULL, active BOOLEAN NOT NULL,
  retention_days INT NOT NULL DEFAULT 90, nudged_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE form (chat_id BIGINT NOT NULL REFERENCES group_chat(chat_id) ON DELETE CASCADE, version INT NOT NULL,
  schema_json TEXT NOT NULL, updated_by BIGINT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (chat_id, version));
CREATE TABLE form_session (user_id BIGINT NOT NULL, chat_id BIGINT NOT NULL REFERENCES group_chat(chat_id) ON DELETE CASCADE,
  form_version INT NOT NULL, step INT NOT NULL, answers BYTEA NOT NULL, lang TEXT,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL, touched_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (user_id, chat_id));
CREATE TABLE submission (id BIGINT AUTO_INCREMENT PRIMARY KEY, chat_id BIGINT NOT NULL REFERENCES group_chat(chat_id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL, form_version INT, profile BYTEA NOT NULL, answers BYTEA, status TEXT NOT NULL,
  decided_by BIGINT, decided_at TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX submission_chat_status ON submission(chat_id, status);
CREATE TABLE review_message (submission_id BIGINT NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
  admin_id BIGINT NOT NULL, message_id BIGINT NOT NULL, PRIMARY KEY (submission_id, admin_id));
CREATE TABLE bot_user (user_id BIGINT PRIMARY KEY, dm_ok BOOLEAN NOT NULL, lang TEXT);
CREATE TABLE canary (id INT PRIMARY KEY, value BYTEA NOT NULL);
```

Additions to the spec's tables, all needed by later tasks: `nudged_at` (hourly nudge, Task 9), `lang` (texts sent outside a live update, Tasks 8–10), and `answers`/`form_version` nullable on `submission` (the "couldn't reach" submission has no answers, Task 9). Also the `canary` table: a fresh ciphertext that round-trips proves nothing about a wrong keyset, so `verifyKeyset` opens a value sealed on the first start.

- [ ] **Step 2: Write the failing tests**

```kotlin
class CryptoTest : StringSpec({
    "round trip" { testCrypto().run { open(seal("hi", "t|1"), "t|1") shouldBe "hi" } }
    "aad mismatch fails" { testCrypto().run { shouldThrow<GeneralSecurityException> { open(seal("hi", "t|1"), "t|2") } } }
    "non-AES-256-GCM keyset rejected" { /* keyset from PredefinedAeadParameters.AES128_GCM */
        shouldThrow<GeneralSecurityException> { Crypto(aes128Keyset()) } }
})
class DbTest : StringSpec({
    "migrates and Exposed tables match" { /* same check as exchange-bot SchemaDriftTest, over
        GroupChats, Forms, FormSessions, Submissions, ReviewMessages, BotUsers, Canary: no drift statements */ }
    "canary accepts same keyset, rejects another" { val db = testDb("canary"); val c = testCrypto()
        verifyKeyset(db, c); verifyKeyset(db, c); shouldThrow<IllegalStateException> { verifyKeyset(db, testCrypto()) } }
})
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='CryptoTest,DbTest'`
Expected: compilation FAIL.

- [ ] **Step 4: Implement**

- `Crypto`: exchange-bot's AEAD half.
- `KeygenMain.kt`: `main()` prints `DATA_KEYSET=<json>`, then a comment line listing `DB_FILE_KEY DB_USER_PW BOT_TOKEN`.
- `Db.kt`, `Tables.kt`, `verifyKeyset`: canary row `id=1` holds `seal("canary", "canary|1")`. Its absence means a fresh database, so insert it. If `open` throws, throw `IllegalStateException("DATA_KEYSET does not match the database")`.
- `Main.kt` now wires `createDataSource` → `migrate` → `connectExposed` → `verifyKeyset` and exits 1 on failure (log the class name only).

- [ ] **Step 5: Run `mise run build`**: PASS.

- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: Tink crypto, H2 schema, keyset canary"`

---

### Task 3: FormSchema

**Files:**
- Create: `src/main/kotlin/joinbot/FormSchema.kt`
- Test: `src/test/kotlin/joinbot/FormSchemaTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class Form(val welcome: String, val fields: List<Field>)
  @Serializable sealed interface Field { val id: String; val prompt: String; val required: Boolean }
  @Serializable @SerialName("radio")   data class Radio(id, prompt, val options: List<String>, val other: Boolean = false, required = true) : Field
  @Serializable @SerialName("multi")   data class Multi(id, prompt, val options: List<String>, val min: Int? = null, val max: Int? = null, required = true) : Field
  @Serializable @SerialName("text")    data class Text(id, prompt, val maxLen: Int = 4096, required = true) : Field
  @Serializable @SerialName("int")     data class IntField(id, prompt, val min: Long? = null, val max: Long? = null, required = true) : Field
  @Serializable @SerialName("link")    data class Link(id, prompt, required = true) : Field
  @Serializable @SerialName("consent") data class Consent(id, prompt) : Field { override val required get() = true }
  val FormJson: Json   // classDiscriminator = "type", ignoreUnknownKeys = false, encodeDefaults = false

  sealed interface Input { data class Typed(val text: String); data class Picked(val idx: Int); data class PickedMany(val idxs: Set<Int>); data object Skip }
  enum class Reason { REQUIRED, TOO_LONG, NOT_A_NUMBER, TOO_SMALL, TOO_LARGE, NOT_A_LINK, TOO_FEW, TOO_MANY, BAD_OPTION, WRONG_KIND }
  sealed interface Check { data class Ok(val value: String) : Check; data class Invalid(val reason: Reason) : Check }
  fun validate(field: Field, input: Input): Check
  fun validateForm(form: Form): List<String>   // empty = valid; each entry "<fieldId or 'welcome'>: <problem>"
  ```
  `Ok.value` is the display string stored as the answer: the option label for `Picked`, labels joined with `", "` for `PickedMany`, the trimmed text, the decimal number, the URL, `"✓"` for consent agree, and `""` for `Skip`. Consent disagree is not validated here; the flow handles it (Task 8).

- [ ] **Step 1: Write the failing table-driven test**

```kotlin
class FormSchemaTest : StringSpec({
    val radio = Radio("a1", "Where?", listOf("Limassol", "Nicosia"), other = true)
    val multi = Multi("a3", "Interests?", listOf("x", "y", "z"), min = 1, max = 2)
    val text = Text("a4", "About", maxLen = 5)
    val num = IntField("a5", "Age?", min = 18, max = 120)
    val link = Link("a6", "LinkedIn?", required = false)
    "validate" { forAll(
        row(radio, Input.Picked(1), Check.Ok("Nicosia")),
        row(radio, Input.Picked(2), Check.Invalid(Reason.BAD_OPTION)),
        row(radio, Input.Typed("Paphos"), Check.Ok("Paphos")),            // other=true accepts text
        row(radio.copy(other = false), Input.Typed("Paphos"), Check.Invalid(Reason.WRONG_KIND)),
        row(multi, Input.PickedMany(setOf(0, 2)), Check.Ok("x, z")),
        row(multi, Input.PickedMany(emptySet()), Check.Invalid(Reason.TOO_FEW)),
        row(multi, Input.PickedMany(setOf(0, 1, 2)), Check.Invalid(Reason.TOO_MANY)),
        row(text, Input.Typed("  abc  "), Check.Ok("abc")),
        row(text, Input.Typed("abcdef"), Check.Invalid(Reason.TOO_LONG)),
        row(text, Input.Typed("   "), Check.Invalid(Reason.REQUIRED)),
        row(text, Input.Skip, Check.Invalid(Reason.REQUIRED)),
        row(num, Input.Typed("42"), Check.Ok("42")),
        row(num, Input.Typed("17"), Check.Invalid(Reason.TOO_SMALL)),
        row(num, Input.Typed("121"), Check.Invalid(Reason.TOO_LARGE)),
        row(num, Input.Typed("99999999999999999999"), Check.Invalid(Reason.NOT_A_NUMBER)),
        row(num, Input.Typed("4.2"), Check.Invalid(Reason.NOT_A_NUMBER)),
        row(link, Input.Typed("https://x.io/p"), Check.Ok("https://x.io/p")),
        row(link, Input.Typed("x.io"), Check.Invalid(Reason.NOT_A_LINK)),
        row(link, Input.Typed("javascript:alert(1)"), Check.Invalid(Reason.NOT_A_LINK)),
        row(link, Input.Typed("ftp://x.io"), Check.Invalid(Reason.NOT_A_LINK)),
        row(link, Input.Skip, Check.Ok("")),
    ) { f, i, r -> validate(f, i) shouldBe r } }
    "multi min defaults to 1 when required" { validate(Multi("m", "?", listOf("a")), Input.PickedMany(emptySet())) shouldBe Check.Invalid(Reason.TOO_FEW) }
    "spec example parses and round-trips" { val f = FormJson.decodeFromString<Form>(SPEC_EXAMPLE_JSON)  // the JSON block from spec §2
        f.fields.map { it::class } shouldBe listOf(Radio::class, Radio::class, Multi::class, Text::class, IntField::class, Link::class, Consent::class)
        FormJson.decodeFromString<Form>(FormJson.encodeToString(f)) shouldBe f }
    "validateForm" { forAll(
        row(Form("hi", listOf(text, text)), "a4: duplicate id"),
        row(Form("hi", listOf(Radio("r", "?", emptyList()))), "r: needs options"),
        row(Form("hi", listOf(num.copy(min = 5, max = 1))), "a5: min > max"),
        row(Form("hi", listOf(text.copy(maxLen = 5000))), "a4: maxLen must be 1..4096"),
        row(Form("hi", listOf(text.copy(prompt = " "))), "a4: empty prompt"),
        row(Form("hi", List(51) { Text("t$it", "?") }), "form: more than 50 fields"),
    ) { f, msg -> validateForm(f) shouldContain msg } }
    "valid spec example has no errors" { validateForm(FormJson.decodeFromString(SPEC_EXAMPLE_JSON)) shouldBe emptyList() }
})
```

Replace the `"…"` option in the spec example with `"Music"` so the example is concrete.

- [ ] **Step 2: Run it to verify it fails**: `./mvnw -q test -Dtest='FormSchemaTest'` → compilation FAIL.
- [ ] **Step 3: Implement `FormSchema.kt`.** Parse links with `java.net.URI`: scheme `http`/`https`, non-null host. Parse ints with `toLongOrNull()` on the trimmed text.
- [ ] **Step 4: Run it to verify it passes**: `./mvnw -q test -Dtest='FormSchemaTest'` → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: form schema and validation"`

---

### Task 4: Repositories

**Files:**
- Create: `src/main/kotlin/joinbot/Repos.kt`
- Test: `src/test/kotlin/joinbot/ReposTest.kt`

**Interfaces:**
- Consumes: `Crypto`, `Form`/`FormJson`, Task 2 tables.
- Produces (every method runs its own `transaction(db)`):
  ```kotlin
  @Serializable data class Profile(val name: String, val username: String?)
  enum class Status { PENDING, APPROVED, REJECTED, WITHDRAWN, EXPIRED }
  data class Group(val chatId: Long, val title: String, val active: Boolean, val retentionDays: Int, val nudgedAt: Instant?)
  @Serializable data class SessionState(val profile: Profile, val answers: Map<String, String> = emptyMap(), val picks: Set<Int> = emptySet(), val otherMode: Boolean = false)   // profile: submission.profile is NOT NULL, and submit/expiry happen long after the join request
  data class Session(val userId: Long, val chatId: Long, val formVersion: Int, val step: Int, val state: SessionState, val lang: String?, val touchedAt: Instant)
  data class Submission(val id: Long, val chatId: Long, val userId: Long, val formVersion: Int?, val profile: Profile,
                        val answers: Map<String, String>?, val status: Status, val decidedBy: Long?, val decidedAt: Instant?, val createdAt: Instant)

  class GroupRepo(db: Database) {
      fun upsert(chatId: Long, title: String, active: Boolean); fun get(chatId: Long): Group?
      fun active(): List<Group>; fun setRetention(chatId: Long, days: Int); fun markNudged(chatId: Long, at: Instant)
  }
  class FormRepo(db: Database) {
      fun current(chatId: Long): Pair<Int, Form>?; fun version(chatId: Long, v: Int): Form?; fun all(chatId: Long): List<Pair<Int, Form>>
      fun save(chatId: Long, form: Form, baseVersion: Int, by: Long, at: Instant): Int?   // new version, or null when baseVersion != current (0 = none)
  }
  class SessionRepo(db: Database, crypto: Crypto) {   // aad "form_session|<user>|<chat>"
      fun get(userId: Long, chatId: Long): Session?; fun forUser(userId: Long): List<Session>
      fun active(userId: Long): Session?               // the one with step >= 0
      fun put(s: Session)                               // upsert
      fun delete(userId: Long, chatId: Long); fun deleteForChat(chatId: Long): List<Session>
      fun idleSince(cutoff: Instant): List<Session>
  }
  const val WAITING = -1                                // step of a queued session (spec B6)
  class SubmissionRepo(db: Database, crypto: Crypto) {  // aad "submission|<id>"; insert, then seal with the generated id and update in the same transaction
      fun create(chatId: Long, userId: Long, formVersion: Int?, profile: Profile, answers: Map<String, String>?, status: Status, at: Instant): Long
      fun get(id: Long): Submission?; fun list(chatId: Long, status: Status?): List<Submission>
      fun pendingFor(chatId: Long, userId: Long): Submission?; fun pendingInChats(chatIds: Collection<Long>): List<Submission>
      fun decide(id: Long, status: Status, by: Long, at: Instant): Boolean   // UPDATE … WHERE id=? AND status='PENDING'
      fun revert(id: Long)                                                    // back to PENDING, decided_* null
      fun delete(id: Long); fun deleteOlderThan(chatId: Long, cutoff: Instant): Int
      fun addReviewMessage(id: Long, adminId: Long, messageId: Long); fun reviewMessages(id: Long): List<Pair<Long, Long>>  // (adminId, messageId)
  }
  class BotUserRepo(db: Database) { fun started(userId: Long, lang: String?); fun forbidden(userId: Long); fun dmOk(userId: Long): Boolean; fun lang(userId: Long): String? }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
class ReposTest : StringSpec({
    "form save is optimistic" { /* save(base=0) → 1; save(base=0) → null; save(base=1) → 2; current → (2, …); version(1) still readable */ }
    "session answers are sealed" { /* put(s with answers {"a4":"secret"}); raw SELECT answers bytes do not contain "secret"; get() returns it */ }
    "session aad binds the row" { /* copy the answers bytes of (u1,c1) onto (u2,c1) via raw UPDATE; get(u2,c1) throws GeneralSecurityException */ }
    "decide is first-wins" { val id = subs.create(...PENDING...); subs.decide(id, APPROVED, 7, now) shouldBe true
        subs.decide(id, REJECTED, 8, now) shouldBe false; subs.get(id)!!.decidedBy shouldBe 7 }
    "revert makes it decidable again" { /* decide → revert → decide by other admin true */ }
    "unreachable submission has null answers" { subs.get(subs.create(c, u, null, p, null, PENDING, now))!!.answers shouldBe null }
    "deleteOlderThan only touches that chat and cascades review messages" { /* … */ }
    "active() returns the non-waiting session" { /* put step=WAITING for c2, step=0 for c1 → active(u).chatId == c1 */ }
})
```

Each test body asserts exactly what its name and comment say. Use `testDb("<test name>")`.

- [ ] **Step 2: Run it to verify it fails**: `./mvnw -q test -Dtest='ReposTest'` → compilation FAIL.
- [ ] **Step 3: Implement `Repos.kt`**: Exposed DSL, set-based statements (`deleteWhere`, conditional `update`), no per-row loops.
- [ ] **Step 4: Run it to verify it passes**: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: repositories with sealed answers and first-wins decide"`

---

### Task 5: Telegram port, vendeli adapter, texts

**Files:**
- Create: `src/main/kotlin/joinbot/Tg.kt`, `VendeliTg.kt`, `Texts.kt`
- Test: `src/test/kotlin/joinbot/VendeliTgTest.kt`, `TestBot.kt`, `FakeTg.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Button(val text: String, val data: String)
  sealed interface Sent { data class Ok(val messageId: Long) : Sent; data object Forbidden : Sent; data object Failed : Sent }
  enum class Decision { OK, GONE, TRANSIENT }       // GONE = HIDE_REQUESTER_MISSING or USER_ALREADY_PARTICIPANT in the error description
  data class Admin(val userId: Long, val name: String, val isBot: Boolean, val canInvite: Boolean)   // owner → canInvite = true; name = user's first name, for "decided by Y"
  interface Tg {
      suspend fun send(chatId: Long, text: String, buttons: List<List<Button>> = emptyList()): Sent
      suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<Button>> = emptyList())
      suspend fun answer(callbackId: String, text: String? = null, alert: Boolean = false)
      suspend fun approve(chatId: Long, userId: Long): Decision
      suspend fun decline(chatId: Long, userId: Long): Decision
      suspend fun admins(chatId: Long): List<Admin>?   // null on failure
      suspend fun sendDocument(chatId: Long, fileName: String, bytes: ByteArray): Sent
  }
  class VendeliTg(private val bot: TelegramBot) : Tg   // 403 → Sent.Forbidden; never sets parse_mode
  enum class T { WELCOME_SKIP, SKIP, OTHER, DONE, AGREE, DISAGREE, SUBMIT, START_OVER, SUMMARY_HEADER, INVALID_REQUIRED, INVALID_TOO_LONG,
      INVALID_NOT_A_NUMBER, INVALID_TOO_SMALL, INVALID_TOO_LARGE, INVALID_NOT_A_LINK, INVALID_TOO_FEW, INVALID_TOO_MANY, INVALID_WRONG_KIND,
      TYPE_OTHER, SUBMITTED, DECLINED_CONSENT, EXPIRED, GROUP_GONE, STALE_BUTTON, HOW_TO_JOIN, APPROVED_USER, REJECTED_USER,
      REVIEW_HEADER, REVIEW_UNREACHABLE, APPROVE, REJECT, DECIDED_BY_APPROVED, DECIDED_BY_REJECTED, ALREADY_DECIDED, NOT_ADMIN_ANYMORE,
      TRY_AGAIN, WITHDRAWN, NUDGE, NEEDS_INVITE_RIGHT, EXPORT_READY }
  object Texts { fun t(lang: String?, key: T, vararg args: Any): String }   // en + ru tables, String.format args
  fun Reason.text(): T                                                          // Reason → INVALID_*
  // test: class FakeTg : Tg — records calls in `calls: MutableList<String>`; scriptable `sendResult`, `decideResult`, `adminsOf: MutableMap<Long, List<Admin>>`, `throwOnDeclineFor: MutableSet<Long>`
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
class VendeliTgTest : StringSpec({
    "send is plain text with inline keyboard" { val calls = mutableListOf<Call>(); val tg = VendeliTg(recordingBot(calls))
        tg.send(5, "<b>a_b*</b>", listOf(listOf(Button("Yes", "f|-100|0|p|0")))) shouldBe Sent.Ok(1)
        calls.single().run { path shouldBe "sendMessage"; body shouldContain "\"<b>a_b*</b>\""; body shouldNotContain "parse_mode"; body shouldContain "f|-100|0|p|0" } }
    "403 maps to Forbidden" { VendeliTg(failingBot(403, "Forbidden: bot was blocked by the user")).send(5, "x") shouldBe Sent.Forbidden }
    "approve maps errors" { forAll(
        row("Bad Request: HIDE_REQUESTER_MISSING", Decision.GONE), row("Bad Request: USER_ALREADY_PARTICIPANT", Decision.GONE),
        row("Internal Server Error", Decision.TRANSIENT)) { d, r -> VendeliTg(failingBot(400, d)).approve(-100, 5) shouldBe r } }
    "every T has en and ru" { T.entries.forEach { Texts.t("ru", it).shouldNotBeBlank(); Texts.t(null, it).shouldNotBeBlank() } }
})
```

`TestBot.kt` copies exchange-bot's `recordingBot`/`Call` and adds `failingBot(code: Int, description: String): TelegramBot`, which answers every call with `{"ok":false,"error_code":code,"description":description}`.

- [ ] **Step 2: Run it to verify it fails**: `./mvnw -q test -Dtest='VendeliTgTest'` → compilation FAIL.
- [ ] **Step 3: Implement.** Use vendeli's typed actions (`message`, `editMessageText`, `answerCallbackQuery`, `approveChatJoinRequest`, `declineChatJoinRequest`, `getChatAdministrators`, `sendDocument`) through `sendReturning(bot).getOrNull()`/`Response.Failure`. Write the en copy in a plain, short register, and translate ru from it.
- [ ] **Step 4: Run it to verify it passes**: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: Telegram port, vendeli adapter, en/ru texts"`

---

### Task 6: AdminCheck and GroupRegistry

**Files:**
- Create: `src/main/kotlin/joinbot/AdminCheck.kt`, `GroupRegistry.kt`
- Test: `src/test/kotlin/joinbot/AdminCheckTest.kt`, `GroupRegistryTest.kt`

**Interfaces:**
- Consumes: `Tg`, `GroupRepo`, `SessionRepo`, `BotUserRepo`, `Texts`.
- Produces:
  ```kotlin
  class AdminCheck(private val tg: Tg, private val clock: Clock, private val ttl: Duration = Duration.ofSeconds(60)) {
      suspend fun canDecide(chatId: Long, userId: Long, fresh: Boolean = false): Boolean   // non-bot admin with canInvite
      suspend fun deciders(chatId: Long): List<Long>                                         // cached; non-bot admins with canInvite
  }
  class GroupRegistry(groups: GroupRepo, sessions: SessionRepo, users: BotUserRepo, tg: Tg) {
      suspend fun onBotStatus(chatId: Long, title: String, isAdmin: Boolean, canInvite: Boolean)
  }
  ```
  `onBotStatus` upserts with `active = isAdmin && canInvite`. Admin without invite → `tg.send(chatId, NEEDS_INVITE_RIGHT)`, once per transition (only when the group was not already in that state). Transition to inactive → `deleteForChat`, then send `GROUP_GONE` to each user, in the user's stored `lang`.

- [ ] **Step 1: Write the failing tests**

```kotlin
"cache serves within ttl, fresh bypasses" { /* FakeTg admins call count: two canDecide within 60 s → 1 call; fresh=true → 2; clock +61 s → 3 */ }
"bots and admins without invite right are not deciders" { /* adminsOf[-100] = [Admin(1,false,true), Admin(2,true,true), Admin(3,false,false)] → deciders == [1] */ }
"admins() failure means nobody can decide" { /* adminsOf missing → canDecide false, deciders empty */ }
"admin without invite: registered inactive, told once" { /* onBotStatus(…, true, false) twice → group inactive, NEEDS_INVITE_RIGHT sent once */ }
"removal deactivates and tells open sessions" { /* active group + 2 sessions → onBotStatus(…, false, false) → inactive, sessions gone, 2 GROUP_GONE sends */ }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** (cache: `ConcurrentHashMap<Long, Pair<Instant, List<Admin>>>`) → **Step 4: Run to pass**: `./mvnw -q test -Dtest='AdminCheckTest,GroupRegistryTest'`
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: admin check cache and group registration"`

---

### Task 7: ReviewService

Built before ApplicantFlow because submitting hands off to it.

**Files:**
- Create: `src/main/kotlin/joinbot/ReviewService.kt`
- Test: `src/test/kotlin/joinbot/ReviewServiceTest.kt`

**Interfaces:**
- Consumes: `SubmissionRepo`, `FormRepo`, `GroupRepo`, `BotUserRepo`, `AdminCheck`, `Tg`, `Texts`, `Clock`.
- Produces:
  ```kotlin
  class ReviewService(subs: SubmissionRepo, forms: FormRepo, groups: GroupRepo, users: BotUserRepo, admins: AdminCheck, tg: Tg, clock: Clock) {
      suspend fun submit(submissionId: Long)                                        // fan-out to deciders ∩ dmOk; record review_message per copy
      suspend fun onDecision(adminId: Long, callbackId: String, data: String)       // data = "r|<id>|a" or "r|<id>|j"
      suspend fun deliverPending(adminId: Long)                                     // on /start: every PENDING in active groups where adminId can decide
      fun renderReview(s: Submission, form: Form?, lang: String?): String           // header, profile, "prompt: answer" lines in form order; unreachable → REVIEW_UNREACHABLE
  }
  ```
- Fan-out is sequential. A `Sent.Forbidden` calls `users.forbidden(id)` and skips that admin. If nobody receives a copy, nudge: if `nudgedAt` is null or more than 1 h old, `tg.send(chatId, NUDGE(count of PENDING))` and `markNudged`.
- Decision order (spec C2): `canDecide(fresh=true)`, else answer `NOT_ADMIN_ANYMORE` as an alert → `decide()`; false → alert `ALREADY_DECIDED` naming the first decider → `tg.approve/decline` → `TRANSIENT`: `revert`, alert `TRY_AGAIN`; `GONE`: `revert` then `decide(id, WITHDRAWN, adminId, now)` → edit every review copy (`DECIDED_BY_*` or `WITHDRAWN`, no buttons) → DM the applicant `APPROVED_USER`/`REJECTED_USER` (not on `WITHDRAWN`). The submission must belong to an active group, otherwise alert `GROUP_GONE`.

- [ ] **Step 1: Write the failing tests**

```kotlin
"fan-out reaches deciders who started the bot" { /* deciders [1,2,3], dmOk 1,2 → 2 sends with buttons r|id|a, r|id|j; 2 review_message rows */ }
"two simultaneous approvals → one Telegram approve, both copies edited" {
    /* coroutineScope { launch { onDecision(1, "c1", "r|$id|a") }; launch { onDecision(2, "c2", "r|$id|a") } }
       fake.calls.count { it.startsWith("approve") } shouldBe 1; edits of both message ids == 2; one ALREADY_DECIDED alert */ }
"demoted admin gets alert, nothing decided" { /* canDecide false → status still PENDING, no approve call */ }
"withdrawn request" { /* decideResult GONE → status WITHDRAWN, copies edited WITHDRAWN, applicant not messaged */ }
"transient error reverts" { /* decideResult TRANSIENT → status PENDING, TRY_AGAIN alert; second click succeeds */ }
"no reachable decider nudges at most hourly" { /* submit twice within 1 h → one NUDGE; clock +61 min → second NUDGE */ }
"deliverPending on /start" { /* 2 PENDING in groups where admin can decide, 1 in a group where not → 2 sends */ }
"unreachable submission renders without answers" { renderReview(subWithNullAnswers, null, "en") shouldContain Texts.t("en", T.REVIEW_UNREACHABLE, "Ann") }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** → **Step 4: Run to pass**: `./mvnw -q test -Dtest='ReviewServiceTest'`
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: review fan-out and first-click-wins decisions"`

---

### Task 8: ApplicantFlow

**Files:**
- Create: `src/main/kotlin/joinbot/ApplicantFlow.kt`
- Test: `src/test/kotlin/joinbot/ApplicantFlowTest.kt`

**Interfaces:**
- Consumes: `GroupRepo`, `FormRepo`, `SessionRepo`, `SubmissionRepo`, `BotUserRepo`, `ReviewService.submit`, `Tg`, `Texts`, `validate`, `Clock`.
- Produces:
  ```kotlin
  class ApplicantFlow(groups: GroupRepo, forms: FormRepo, sessions: SessionRepo, subs: SubmissionRepo, users: BotUserRepo,
                      review: ReviewService, tg: Tg, clock: Clock) {
      suspend fun onJoinRequest(chatId: Long, userId: Long, userChatId: Long, profile: Profile, lang: String?)
      suspend fun onMessage(userId: Long, text: String?, lang: String?): Boolean      // text null = non-text message; false = no active session
      suspend fun onCallback(userId: Long, callbackId: String, data: String, lang: String?)
      suspend fun onStart(userId: Long, lang: String?): Boolean                       // true = resumed (re-asked current question)
      suspend fun decline(s: Session, key: T)                                         // used by Purge: delete session, tg.decline, send key
  }
  const val SUMMARY_CHUNK = 3500   // split the summary text at line boundaries into chunks ≤ this
  ```
- Callback actions in `f|<chat>|<step>|<action>|<idx>`: `p` pick radio option, `o` "Other" (sets `otherMode`, asks for text), `t` toggle multi option (edits the keyboard to show ✓), `d` multi done, `s` skip, `y` consent agree, `n` consent disagree, `S` submit, `R` start over. Any mismatch is stale and gets answered with `STALE_BUTTON`, changing nothing: no session, a waiting session, a different `step`, or for `S`/`R` a step that isn't `fields.size`.
- A join request with no active group or no form is ignored. Profile comes from the request's `from`. If `from` is null, ignore the request (exchange-bot R57).
- The first message goes to `userChatId`. `Sent.Forbidden`/`Failed` → `users.forbidden`, delete the session, create an unreachable `PENDING` submission (`answers = null`), and call `review.submit`.
- If the user already has an active session elsewhere, the new session is stored with `step = WAITING` and sends nothing. After a submit, the oldest waiting session starts (welcome + question 0).
- Consent disagree → `decline(s, DECLINED_CONSENT)`.
- Submit → `subs.create(PENDING)`, delete the session, send `SUBMITTED`, `review.submit(id)`, start the next waiting session.

- [ ] **Step 1: Write the failing tests** (FakeTg, `testDb`, fixed `Clock`; the form is the spec §2 example)

```kotlin
"join request DMs welcome and question 1" { /* sends to userChatId: welcome, then "Where do you live?" with buttons Limassol, Nicosia, Other */ }
"scripted run to submit stores a sealed submission" {
    /* p0 → p0 (Yes) → t0,d → "I like cats" → "42" → s (skip link) → y → S
       subs.list(chat, PENDING).single().answers shouldBe mapOf("a1" to "Limassol", "a2" to "Yes", "a3" to "Music", "a4" to "I like cats", "a5" to "42", "a6" to "", "a7" to "✓")
       session gone; review fan-out happened (FakeTg saw r|<id>|a) */ }
"invalid typed input re-asks with reason" { /* at a5 send "17" → INVALID_TOO_SMALL, then the a5 prompt again; step unchanged */ }
"other asks for text and stores it" { /* o → TYPE_OTHER; "Paphos" → answers a1 = Paphos */ }
"consent disagree declines and forgets" { /* … n → tg decline call, session deleted, no submission, DECLINED_CONSENT sent */ }
"start over resets to step 0" { /* at summary R → question 1 again, answers empty */ }
"admin edit mid-flow does not shift questions" { /* after a1, FormRepo.save a new version with a different 2nd field → next question still the pinned version's a2 */ }
"second group waits, then starts after submit" { /* join c1, join c2 → only c1 messages; finish c1 → c2 welcome sent */ }
"DM forbidden → unreachable submission to reviewers" { /* sendResult Forbidden → PENDING submission with answers null; review copies sent */ }
"/start resumes" { onStart(u, "en") shouldBe true /* current question re-sent */; onStart(stranger, "en") shouldBe false }
// Review Focus 1–4:
"non-text message re-asks" { onMessage(u, null, "en") shouldBe true /* INVALID_WRONG_KIND then same question; step unchanged */ }
"stale button changes nothing" { /* answer a1 via p0, then press the old a1 button again (step 0) → STALE_BUTTON, answers unchanged; same after R and after session deleted */ }
"long summary is split" { /* text answers of 4000 chars × 3 → summary sent as ≥3 messages, each ≤ 4096, buttons S/R only on the last */ }
"repeat join request" { /* mid-session join again → session restarted at step 0; with a PENDING submission → no new session, no message */ }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** → **Step 4: Run to pass**: `./mvnw -q test -Dtest='ApplicantFlowTest'`
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: data-driven applicant form flow"`

---

### Task 9: Purge

**Files:**
- Create: `src/main/kotlin/joinbot/Purge.kt`
- Test: `src/test/kotlin/joinbot/PurgeTest.kt`

**Interfaces:**
- Consumes: `SessionRepo.idleSince`, `ApplicantFlow.decline`, `SubmissionRepo`, `GroupRepo`.
- Produces:
  ```kotlin
  class Purge(sessions: SessionRepo, flow: ApplicantFlow, subs: SubmissionRepo, groups: GroupRepo, clock: Clock) {
      suspend fun runOnce()                                    // idle ≥ 7 days → EXPIRED; submissions past retention deleted
      fun start(scope: CoroutineScope): Job                    // runOnce at start, then every 24 h; exceptions logged by class name, loop survives
  }
  ```
  Idle expiry: create a submission with status `EXPIRED` and `answers = null` (so admins see it in the list), then `flow.decline(s, T.EXPIRED)`. Waiting sessions expire the same way. Retention: one `deleteOlderThan(chatId, now - retentionDays)` per active group.
  `# ponytail:` comment: a daily loop means expiry lands up to 8 days after the last activity; run it hourly if that matters.

- [ ] **Step 1: Write the failing test**

```kotlin
"idle session expires" { /* touchedAt now-7d-1s → decline call, EXPIRED sent, EXPIRED submission, session gone; touchedAt now-6d → untouched */ }
"retention deletes only old submissions of that group" { /* retention 30: rows at now-31d and now-29d in c1, now-31d in c2 with retention 90 → only the first deleted */ }
"one failing decline does not stop the rest" { /* FakeTg decline throws for user 1 → user 2 still expired */ }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** → **Step 4: Run to pass**: `./mvnw -q test -Dtest='PurgeTest'`
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: daily purge of idle sessions and old submissions"`

---

### Task 9a: Privilege-loss handoff

Added on the user's request: when the bot loses the right to approve (removed, or demoted / invite right taken away), nobody is left waiting without knowing where their request went; when the right comes back, review resumes.

**Files:**
- Modify: `src/main/kotlin/joinbot/GroupRegistry.kt`, `ReviewService.kt`, `Texts.kt`
- Test: `src/test/kotlin/joinbot/GroupRegistryTest.kt`, `ReviewServiceTest.kt`

**Interfaces:**
- Consumes: `GroupRegistry.onBotStatus` (Task 6), `ReviewService` (Task 7), `SubmissionRepo.list(chatId, PENDING)`, `reviewMessages`.
- Produces:
  ```kotlin
  class GroupRegistry(groups: GroupRepo, sessions: SessionRepo, subs: SubmissionRepo, users: BotUserRepo, review: ReviewService, tg: Tg)  // gains subs + review
  // ReviewService:
  suspend fun suspendChat(chatId: Long)   // edit every review copy of every PENDING submission in chatId: text = original + "\n\n" + REVIEW_SUSPENDED, no buttons
  suspend fun resumeChat(chatId: Long)    // re-run fan-out (same as submit) for every PENDING submission in chatId; addReviewMessage overwrites the (submission, admin) row
  // Texts: GROUP_GONE reworded (below); new keys
  T.MANUAL_REVIEW            // to applicants: "The group's admins will review your join request directly in Telegram."
  T.REVIEW_SUSPENDED         // appended to review copies: "I can no longer approve requests here — use the group's Join requests list."
  T.GROUP_HANDOFF            // to the group, arg %d = PENDING count: "I can't approve join requests any more. %d request(s) wait in this group's Join requests list."
  ```
- `GROUP_GONE` (mid-form applicants) reworded to say the form is closed and admins will review the request directly in Telegram.

Behaviour in `onBotStatus`, on a transition **from active to inactive** (stored `active == true`, new state inactive):
1. Mid-form sessions: as today (delete + `GROUP_GONE`).
2. Every `PENDING` submission's applicant gets `MANUAL_REVIEW` once (in `users.lang`); per-user failures caught as in Task 6.
3. `review.suspendChat(chatId)`.
4. `tg.send(chatId, GROUP_HANDOFF(count))` when count > 0. A removed bot's send fails silently (`Sent.Failed`), which is fine.

On a transition **from inactive to active** (stored `active == false`, new state active, group existed): `review.resumeChat(chatId)`. Submissions stay `PENDING` throughout; nothing is auto-approved or declined.

- [ ] **Step 1: Write the failing tests**

```kotlin
"losing the right hands pending requests to manual review" { /* active group, 2 PENDING (users 5, 6) each with a review copy, 1 session (user 7)
   → onBotStatus(…, isAdmin=true, canInvite=false):
   users 5 and 6 got MANUAL_REVIEW; user 7 got GROUP_GONE; both review copies edited with REVIEW_SUSPENDED and no buttons;
   group got GROUP_HANDOFF with "2"; NEEDS_INVITE_RIGHT also sent; statuses still PENDING */ }
"no pending → no handoff line" { /* active group, nothing pending → removed → no GROUP_HANDOFF */ }
"already inactive → nothing re-sent" { /* inactive group with PENDING → onBotStatus(false,false) → no MANUAL_REVIEW, no edits */ }
"right restored → copies re-sent with buttons" { /* inactive group, 1 PENDING, decider 1 dmOk → onBotStatus(true,true) → one new send to admin 1 with r|id|a, r|id|j; reviewMessages(id) holds the new message id */ }
"decide after restore uses the new copy" { /* … restore, then onDecision approve → the new message id is edited */ }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** → **Step 4: Run to pass**: `./mvnw -q test -Dtest='GroupRegistryTest,ReviewServiceTest'`, then `mise run build`
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: hand pending requests to manual review when the bot loses the invite right"`

---

### Task 10: Mini App API and static files

**Files:**
- Create: `src/main/kotlin/joinbot/MiniAppAuth.kt` (copy `InitDataVerifier`/`Viewer` from exchange-bot), `MiniAppServer.kt`, `MiniAppApi.kt`, `Csv.kt`
- Test: `src/test/kotlin/joinbot/MiniAppAuthTest.kt` (copy from exchange-bot), `MiniAppApiTest.kt`, `CsvTest.kt`

**Interfaces:**
- Consumes: repos, `AdminCheck`, `validateForm`, `FormJson`, `Tg.sendDocument`, `Texts`.
- Produces:
  ```kotlin
  class MiniAppDeps(val verify: (String) -> Viewer?, val groups: GroupRepo, val forms: FormRepo, val subs: SubmissionRepo,
                    val admins: AdminCheck, val tg: Tg, val users: BotUserRepo, val clock: Clock)
  fun Application.miniApp(deps: MiniAppDeps, webDir: File, pathPrefix: String = "")
  fun startMiniApp(cfg: Config, deps: MiniAppDeps): EmbeddedServer<*, *>
  internal fun miniAppPathPrefix(url: String): String; internal fun isValidMiniAppUrl(url: String): Boolean   // copy from exchange-bot
  fun toCsv(forms: List<Pair<Int, Form>>, subs: List<Submission>): String
  // DTOs (@Serializable):
  GroupDto(id: Long, title: String, hasForm: Boolean, retentionDays: Int)
  FormDto(version: Int, schema: Form?)                       // version 0 = no form yet
  SaveFormBody(schema: Form, baseVersion: Int); SavedDto(version: Int); ErrorsDto(errors: List<String>)
  SubmissionRow(id: Long, userId: Long, name: String, username: String?, status: Status, createdAt: String, decidedBy: Long?)
  SubmissionDetail(row: SubmissionRow, answers: List<AnswerDto>?); AnswerDto(fieldId: String, prompt: String, value: String)
  SettingsBody(retentionDays: Int)                           // 1..3650, else 400
  ```
- Routes (spec §4). `GET /api/groups` lists active groups where `admins.canDecide(id, viewer)` (cached). Every `/api/groups/{id}/…` route: unknown or inactive group → 404, then not a decider → 403, using `fresh = true` on PUT/DELETE/POST. `PUT form`: `validateForm` errors → 400 `ErrorsDto`, `FormRepo.save` null → 409 `FormDto(current)`. `POST export` → `tg.sendDocument(viewer, "<title>-submissions.csv", toCsv(…))`, then 202; `Forbidden` → 409 (user must start the bot first).
- Static files: `staticFiles(pathPrefix.ifEmpty { "/" }, webDir)` (https://ktor.io/docs/server-static-content.html#folders; directory requests get `index.html` by default). No `default("index.html")` SPA fallback: screens switch in memory, only `/` is ever loaded, and a fallback would answer unknown `/api/…` paths with 200 + HTML. A missing `webDir` logs a warning and serves only `/api`.
- CSV columns: `id,user_id,name,username,status,created_at,decided_at`, then field ids. Field order: the latest version's order, then ids that only exist in older versions, newest version first. Header labels are each id's latest prompt. RFC 4180 quoting. A cell starting with `=`, `+`, `-`, `@`, tab, or CR gets a `'` prefix.

- [ ] **Step 1: Write the failing tests** (`testApplication`, a `verify` lambda mapping `"tma u1"` → `Viewer(1, …)`; FakeTg admins)

```kotlin
"bad initData → 401" { client.get("/api/groups") { header("Authorization", "tma junk") }.status shouldBe Unauthorized }
"non-admin → 403" { get("/api/groups/-100/form" as user 9).status shouldBe Forbidden }
"groups lists only where viewer decides" { /* 2 active groups, viewer decider in one → 1 GroupDto */ }
"save then stale save → 409 with current version" { /* PUT base 0 → 200 {version:1}; PUT base 0 → 409 {version:1,…} */ }
"invalid schema → 400 with errors" { /* duplicate ids → errors contains "a4: duplicate id" */ }
"submission detail labels by pinned form version" { /* answers listed with prompts from the submission's formVersion */ }
"delete removes it" { /* DELETE → 204; GET → 404 */ }
"export DMs a CSV" { /* POST → 202; FakeTg saw sendDocument to viewer */ }
"settings bounds" { /* 0 → 400; 30 → 204 and GroupDto.retentionDays 30 */ }
"static index served, api not shadowed" { /* tmp webDir with index.html → GET / 200 contains marker; GET /api/groups without auth → 401; GET /api/nope → 404 */ }
// CsvTest
"union of field ids labelled with latest prompt" { /* v1 fields a,b; v2 fields b(renamed),c → header …,b,c,a with b's v2 prompt */ }
"formula injection neutralised and quotes escaped" { toCsv(…answer "=1+1"…) shouldContain "'=1+1"; answer "a\"b,c" → "\"a\"\"b,c\"" }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** → **Step 4: Run to pass**: `./mvnw -q test -Dtest='MiniApp*Test,CsvTest'`
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: Mini App API, CSV export, filesystem static files"`

---

### Task 11: Bot wiring and Main

**Files:**
- Modify: `src/main/kotlin/joinbot/Handlers.kt` (stub from Task 1)
- Create: `src/main/kotlin/joinbot/Registry.kt`
- Modify: `src/main/kotlin/joinbot/Main.kt`
- Test: `src/test/kotlin/joinbot/HandlersTest.kt`, `MenuButtonTest.kt` (copy from exchange-bot)

**Interfaces:**
- Consumes: everything above.
- Produces:
  ```kotlin
  object Registry { lateinit var flow: ApplicantFlow; lateinit var review: ReviewService; lateinit var registry: GroupRegistry; lateinit var users: BotUserRepo }
  @CommandHandler(["/start"]) suspend fun start(user: User, update: ProcessedUpdate)                 // private only: users.started; flow.onStart || review.deliverPending; else HOW_TO_JOIN
  @UpdateHandler([UpdateType.CHAT_JOIN_REQUEST]) suspend fun joinRequest(update: ChatJoinRequestUpdate)
  @UpdateHandler([UpdateType.MY_CHAT_MEMBER]) suspend fun botStatus(update: MyChatMemberUpdate)
  @UnprocessedHandler suspend fun fallback(update: ProcessedUpdate)   // private message → flow.onMessage; callback "f|…" → flow.onCallback, "r|…" → review.onDecision, else answer silently
  internal suspend fun setDefaultMenuButton(client: HttpClient, token: String, url: String?): Boolean   // copy; button text "Forms"
  ```
- `Main`: config → db + `verifyKeyset` → repos → `TelegramBot` (exchange-bot's `updatesListener`/`httpClient` retry block, `throwExOnActionsFailure = false`) → `VendeliTg` → services → `Registry` → `validateBotToken` (copy) → menu button if `isValidMiniAppUrl` → `startMiniApp` → `Purge.start` → `bot.handleUpdates()`. A group message in `fallback` is ignored. Never log update content.

- [ ] **Step 1: Write the failing test**: route through the handler functions with `recordingBot` and fake updates decoded from JSON fixtures

```kotlin
"join request update reaches the flow" { /* chat_join_request JSON for an active group with a form → sendMessage to user_chat_id */ }
"private text and f| callback route to the flow, r| to review" { /* … */ }
"group text is ignored" { /* message in -100 chat → no calls */ }
"/start from a stranger explains how to join" { /* … HOW_TO_JOIN */ }
```

- [ ] **Step 2: Run to fail** → **Step 3: Implement** → **Step 4: Run to pass**: `./mvnw -q test -Dtest='HandlersTest,MenuButtonTest'`
- [ ] **Step 5: Smoke run** with a real test bot token: `BOT_TOKEN=… DATA_KEYSET="$(./mvnw -q exec:java -Dexec.mainClass=joinbot.KeygenMainKt | sed -n 's/^DATA_KEYSET=//p')" DB_FILE_KEY=a DB_USER_PW=b ./mvnw -q exec:java`. Expected: no exception for 30 s; `Ctrl-C` exits.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: wire handlers, menu button, startup"`

---

### Task 12: Web admin UI

**Files:**
- Create: `web/package.json`, `web/bun.lock`, `web/index.html`, `web/vite.config.ts`, `web/tsconfig.json`, `web/src/main.ts`, `web/src/style.css`, `web/src/App.svelte`, `web/src/api.ts`, `web/src/tg.ts`, `web/src/i18n.ts`, `web/src/editor.ts`, `web/src/editor.test.ts`, `web/src/views/{Groups,Editor,Preview,Submissions,Settings}.svelte`
- Modify: `mise.toml`

**Interfaces:**
- Consumes: Task 10 routes and DTOs (mirror them as TS types in `api.ts`).
- Produces (`editor.ts`, pure, no Svelte imports):
  ```ts
  export type Field = Radio | Multi | Text | IntField | Link | Consent   // same JSON shape as FormSchema.kt
  export interface Form { welcome: string; fields: Field[] }
  export function addField(f: Form, type: Field['type']): Form          // new id: "f" + base36(Date.now()), unique within form; defaults per type; radio "yesno" preset → options ["Yes","No"]
  export function moveField(f: Form, i: number, dir: -1 | 1): Form      // no-op at edges
  export function removeField(f: Form, i: number): Form
  export function updateField(f: Form, i: number, patch: Partial<Field>): Form
  export function validate(f: Form): string[]                            // same rules and messages as validateForm
  ```

- [ ] **Step 1: Scaffold.** Copy `package.json` from exchange-bot without `@playwright/test` and the `e2e`/`preview` scripts, and rename it to `join-gate-miniapp`. Copy `vite.config.ts` with `build.outDir: 'dist'`. Copy `tg.ts` (Telegram WebApp init + `initData`). Run `cd web && bun install`.
- [ ] **Step 2: Write the failing `editor.test.ts`**

```ts
test('add gives unique ids and defaults', () => { let f = { welcome: '', fields: [] }; f = addField(addField(f, 'text'), 'text')
  expect(new Set(f.fields.map(x => x.id)).size).toBe(2); expect((f.fields[0] as Text).maxLen).toBe(4096) })
test('move is a no-op at edges', () => { /* move(0,-1) and move(last,+1) return equal forms */ })
test('remove and update', () => { /* … */ })
test('validate mirrors server messages', () => { /* duplicate id → 'a4: duplicate id'; min>max → 'a5: min > max' */ })
```

- [ ] **Step 3: Run to fail**: `cd web && bun test src` → FAIL (module not found).
- [ ] **Step 4: Implement `editor.ts`** → `bun test src` PASS.
- [ ] **Step 5: Build the views.**
  - Groups list.
  - Editor: welcome textarea; type picker; per-field props; ↑↓ and delete buttons; the Preview pane renders the prompt and a button grid as the chat shows them. Save sends `baseVersion`. A 409 shows "someone saved in between — reload". A 400 lists the errors.
  - Submissions: status filter, detail, delete with confirm, and an "Export CSV" button that shows "sent to your DM".
  - Settings: retention days.
  - Texts in en/ru from `Telegram.WebApp.initDataUnsafe.user.language_code`.
- [ ] **Step 6: Wire into mise**

```toml
[tasks."web:install"]
dir = "web"
run = "bun install --frozen-lockfile"

[tasks."web:test"]
depends = ["web:install"]
dir = "web"
run = "bun test src"

[tasks."web:build"]
depends = ["web:install"]
dir = "web"
run = "bun run build"

[tasks."backend:test"]
run = "./mvnw -B test"

[tasks."backend:build"]
run = "./mvnw -B verify"        # compile, KSP, tests, jar + target/lib

[tasks.test]
depends = ["web:test", "backend:test"]

[tasks.build]
depends = ["web:test", "web:build", "backend:build"]
```

`build` and `test` have no `run` of their own: they are the list of what gets built, and mise runs the web and backend parts in parallel.

- [ ] **Step 7: Run `mise run build`**: Expected: svelte-check 0 errors, `web/dist/index.html` exists, Maven `BUILD SUCCESS`.
- [ ] **Step 8: Manual check**: `MINIAPP_URL=https://<tunnel> … ./mvnw -q exec:java`, open the bot menu button in Telegram, create a form, then send a join request from a second account. Expected: the form arrives in DM and the approve copy arrives for the admin.
- [ ] **Step 9: Commit** — `git add -A && git commit -m "feat: Svelte admin Mini App"`

---

### Task 13: Container image

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `compose.yaml`
- Modify: `.github/workflows/release.yml` only if the Dockerfile's cache mounts differ from the comment there

- [ ] **Step 1: Check base image tags**

Run: `docker manifest inspect bellsoft/hardened-liberica-runtime-container:jre-27-cds-distroless-glibc >/dev/null && echo 27 || echo 25`
Expected: `27`; on `25`, use `jre-25-cds-distroless-glibc` (spec §7 fallback). Do the same check for the builder `bellsoft/liberica-runtime-container:jdk-27-glibc`.

- [ ] **Step 2: Write the Dockerfile**
  1. `FROM oven/bun:1.4.2 AS web`: copy `web/package.json web/bun.lock`, `bun install --frozen-lockfile` with `--mount=type=cache,target=/root/.bun/install/cache`, copy `web/`, `bun run build`.
  2. `FROM <jdk builder> AS build`: copy `mvnw`, `.mvn`, `pom.xml`, then `./mvnw -B dependency:go-offline` with a `/root/.m2` cache mount. Copy `src`, run `./mvnw -B package -DskipTests` (same mount), then `mkdir /data-seed && chown 10001:10001 /data-seed`.
  3. Runtime (from Step 1, digest-pinned): copy `target/lib/*` and `target/join-gate-bot.jar` → `/app/lib`, `/web/dist` → `/app/web`, `/data-seed` → `/app/data` (chown 10001). Then `USER 10001:10001`, `VOLUME ["/app/data"]`, `ENV DB_PATH=/app/data/joinbot WEB_DIR=/app/web TZ=UTC`, `EXPOSE 8080`, and `ENTRYPOINT ["java","-Duser.timezone=UTC","-cp","/app/lib/*","joinbot.MainKt"]`.

  `.dockerignore`: `target`, `web/node_modules`, `web/dist`, `data`, `.git`. `compose.yaml`: one service with the image, an `env_file: .env`, a named volume on `/app/data`, and port `8080`.
- [ ] **Step 3: Build and run it**

Run: `docker build -t join-gate-bot:dev . && docker run --rm --entrypoint java join-gate-bot:dev -cp '/app/lib/*' joinbot.KeygenMainKt`
Expected: prints a `DATA_KEYSET=` line. `docker run --rm --entrypoint ls join-gate-bot:dev /app/web` lists `index.html`.
- [ ] **Step 4: Commit** — `git add -A && git commit -m "build: container image with filesystem web assets"`, then push the branch and open a PR. CI (`mise run build`) must be green.
