# join-gate-bot

A Telegram bot that gates a group: whoever asks to join gets a short form in DM, and the group's admins approve or reject the answers with one click. Admins build the form in a Telegram Mini App.

## Install

You need Docker with Compose. The Mini App also needs a public HTTPS address; without one the bot still works, but nobody can build a form.

1. **Create the bot.** In [@BotFather](https://t.me/BotFather), `/newbot`, and keep the token.

2. **Get the files.** Copy [`compose.yaml`](compose.yaml) and [`.env.example`](.env.example) into a directory on the server, and rename `.env.example` to `.env`.

3. **Generate the data key:**

   ```sh
   docker run --rm --entrypoint java ghcr.io/asm0dey/join-gate-bot:latest -cp '/app/lib/*' joinbot.KeygenMainKt
   ```

   Put the printed `DATA_KEYSET=…` line into `.env`.

4. **Fill in `.env`:**

   | Variable | Required | Value |
   |---|---|---|
   | `BOT_TOKEN` | yes | The token from BotFather. |
   | `DATA_KEYSET` | yes | From step 3. Encrypts applicants' answers. |
   | `DB_FILE_KEY` | yes | Any secret without spaces. Encrypts the database file. |
   | `DB_USER_PW` | yes | Any secret. The database user's password. |
   | `MINIAPP_URL` | no | Public HTTPS URL of the Mini App, e.g. `https://bot.example.com` or `https://example.com/joinbot`. Unset: no Mini App. |
   | `MINIAPP_PORT` | no | Port the Mini App listens on inside the container. Default `8080`. |

   **Back up `.env`.** Lose `DATA_KEYSET`, `DB_FILE_KEY` or `DB_USER_PW` and the stored data can't be read again. The bot refuses to start with a keyset that doesn't match the database.

5. **Pin a version** (optional; recommended). In `compose.yaml`, change `:latest` to a [release](https://github.com/asm0dey/join-gate-bot/pkgs/container/join-gate-bot) number, e.g. `ghcr.io/asm0dey/join-gate-bot:1`, so a restart never upgrades by surprise.

6. **Start it:**

   ```sh
   docker compose up -d
   docker compose logs -f   # expect "join-gate-bot: listening"
   ```

7. **Put HTTPS in front of the Mini App.** The container listens on `127.0.0.1:8080` only. Point any reverse proxy at it, passing the path through unchanged. With [Caddy](https://caddyserver.com):

   ```
   bot.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```

   For `MINIAPP_URL=https://example.com/joinbot`, proxy `/joinbot*` without stripping the prefix. The bot sets its menu button to the Mini App on start; the log says `menu button set`.

## Guard a group

1. Add the bot to the group as an admin with the **Invite users** right. To check existing members, also give it **Ban users**.
2. Turn on **Approve new members** for the group, or hand out an invite link that requires approval.
3. Each admin who should review opens a DM with the bot and sends `/start`. Admins who haven't can't be messaged by the bot.
4. Open the bot's menu button (**Forms**), pick the group and build the form. Until a group has a form, the bot ignores its join requests.

## Check existing members

An admin with **Invite users** and **Ban users** sends `/remind` in the group (`/remind 3d` or `/remind 48h` sets the deadline; the default is 7 days, the range 1h to 365d). The bot posts a **Fill the form** button; members who tap it fill the form in a DM, and admins review the answers as they review join requests. Members who already passed can update their answers instead.

At the deadline each such admin gets a DM listing who hasn't filled the form, with a **Remove** button per person. The bot only knows members it has seen join, post or react, so the list says "knows N of M" and leaves the rest out. `/remind` without a duration re-posts the button and keeps the deadline.

## Operate

- **Data** lives in the `data` volume (an encrypted H2 database). Back it up together with `.env`.
- **Upgrade:** change the image tag, then `docker compose pull && docker compose up -d`.
- **Stop or restart:** `docker compose stop` lets the updates being handled finish (up to 25 s) and tells Telegram they were handled, so nothing is lost or handled twice.
- **Retention:** submissions are deleted after 90 days by default; admins change it per group in the Mini App. Forms left idle for 7 days are declined.

## Build from source

Tools come from [mise](https://mise.jdx.dev) (Bun) and `.sdkmanrc` (Java).

```sh
mise run test           # backend and web tests
mise run build          # web bundle, then jar + target/lib
docker build -t join-gate-bot .
```

Pushing a tag `vN` publishes `ghcr.io/asm0dey/join-gate-bot:N` and `:latest`.
