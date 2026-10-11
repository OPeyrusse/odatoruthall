# One image, one process, both applications.
#
# Three stages: Node builds the two front-end assets, a JDK builds the uberjar,
# and a JRE runs it. The domain needs nothing else at runtime — the snapshot is
# in memory and the worklog is a file — so the image is a JRE and a jar.
#
# Java 26. Collection and day scans run on virtual threads, which is why the
# server carries a page read as a plain blocking wait and needs no thread pool
# sized against the number of repositories being watched.

ARG JDK_VERSION=26

# ------------------------------------------------------------------ assets
FROM node:22-alpine AS assets
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
# Tailwind reads the classes out of the hiccup views, so the views are what it
# needs — not a template directory.
COPY components/ui/css ./components/ui/css
COPY components/ui/src ./components/ui/src
COPY bases/server/src ./bases/server/src
RUN npm run assets

# --------------------------------------------------------------------- jar
FROM eclipse-temurin:${JDK_VERSION}-jdk AS build
ARG CLOJURE_VERSION=1.12.6.1673
ARG ODATO_VERSION=0.1.0
ENV ODATO_VERSION=${ODATO_VERSION}
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl rlwrap \
 && rm -rf /var/lib/apt/lists/* \
 && curl -sSL -o /tmp/install.sh \
      "https://github.com/clojure/brew-install/releases/download/${CLOJURE_VERSION}/linux-install.sh" \
 && chmod +x /tmp/install.sh && /tmp/install.sh -p /usr/local && rm /tmp/install.sh

WORKDIR /src
COPY deps.edn ./deps.edn
COPY components/ ./components/
COPY bases/ ./bases/
COPY projects/ ./projects/
COPY --from=assets /src/bases/server/resources/public/ ./bases/server/resources/public/

# The Maven cache is mounted rather than copied, so a rebuild resolves the
# dependency graph once and not once per layer change.
RUN --mount=type=cache,target=/root/.m2 \
    cd projects/server && clojure -T:build uber

# ------------------------------------------------------------------ server
FROM eclipse-temurin:${JDK_VERSION}-jre
LABEL org.opencontainers.image.title="odatoruthall" \
      org.opencontainers.image.description="One dashboard to rule them all: an in-flight pull request dashboard and a Jira worklog." \
      org.opencontainers.image.source="https://github.com/OPeyrusse/odatoruthall"

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 odato \
 && mkdir -p /data && chown odato:odato /data

USER odato
WORKDIR /app
COPY --from=build /src/projects/server/target/odatoruthall.jar /app/odatoruthall.jar

# The worklog is the only thing that outlives the process, so it is the only
# thing that needs a volume. Everything gitdesk collects is rebuilt by the
# collection that starts with the process.
VOLUME ["/data"]
ENV PORT=7777 \
    ODATO_DB_FILE=/data/odatoruthall.edn \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 7777

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/gitdesk" -o /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/odatoruthall.jar"]
