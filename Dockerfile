FROM oven/bun:1.4.4@sha256:1d22548f177f4ff38e0c15baa3c2c46c6c2060ba2d1bc573bb4720b407548f1d AS web
WORKDIR /web

# Lockfile first so a source-only change doesn't re-run `bun install`.
COPY web/package.json web/bun.lock ./
RUN --mount=type=cache,target=/root/.bun/install/cache \
    bun install --frozen-lockfile

# `bun run build` runs svelte-check first, so it needs the whole web/ source.
# .dockerignore keeps web/node_modules out of the context, so this COPY can't
# clobber the install above.
COPY web ./
RUN bun run build

FROM bellsoft/liberica-runtime-container:jdk-27-glibc@sha256:1497cbc27c21eac64de336c7b3c13e1f4e4f4b59c00580e7b51b6a0e1088ec26 AS build
WORKDIR /src

# Wrapper and pom only, so a source-only change doesn't re-resolve dependencies.
# /root/.m2 is a BuildKit cache mount (not a layer); the image runs as root.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B package -DskipTests

# The distroless runtime has no shell, so seed the data dir here, owned by the
# hardened image's runtime user (appuser, 10001:10001).
RUN mkdir -p /data-seed && chown 10001:10001 /data-seed

# JRE 27 (bytecode targets 25), glibc, CDS archive for faster restarts.
FROM bellsoft/hardened-liberica-runtime-container:jre-27-cds-distroless-glibc@sha256:e2de06c087d9f64f28cb40ff7c959144abcb613f41746230f6b6d82d20dac476
WORKDIR /app

# Code is root-owned and read-only to the running user.
COPY --from=build /src/target/lib ./lib
COPY --from=build /src/target/join-gate-bot.jar ./lib/
COPY --from=web /web/dist ./web
# Mutable state (H2 files); matches the named volume in compose.yaml.
COPY --from=build --chown=10001:10001 /data-seed ./data

# Numeric so Kubernetes runAsNonRoot can verify it; same as the image default.
USER 10001:10001
VOLUME ["/app/data"]
ENV DB_PATH=/app/data/joinbot WEB_DIR=/app/web TZ=UTC
EXPOSE 8080
# No shell in distroless, so java is invoked directly; keep -Duser.timezone=UTC
# alongside ENV TZ. "/app/lib/*" is the JVM's own classpath wildcard.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-cp", "/app/lib/*", "joinbot.MainKt"]
