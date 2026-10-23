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

FROM bellsoft/liberica-runtime-container:jdk-27-glibc@sha256:e8b9487cc5582e374719f09708478c364806058099acdce4c3a624019a423354 AS build
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
FROM bellsoft/hardened-liberica-runtime-container:jre-27-cds-distroless-glibc@sha256:b0a9ab845968ef69685b780af9ca7f22daf7109ba1201d8fbda512c49081adaa
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
