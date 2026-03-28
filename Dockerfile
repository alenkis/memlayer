# Stage 1: Build uberjar
FROM clojure:temurin-22-tools-deps-bookworm-slim AS builder

WORKDIR /build

# Cache dependency resolution
COPY deps.edn ./
RUN clojure -P -M:server && clojure -P -M:build

# Git metadata passed from CI (no .git dir in Docker context)
ARG GIT_VERSION=dev
ARG GIT_SHA=unknown
ENV GIT_VERSION=${GIT_VERSION}
ENV GIT_SHA=${GIT_SHA}

# Copy source and build
COPY src/ src/
COPY resources/ resources/
COPY build.clj ./
RUN clojure -T:build uber

# Stage 2: Runtime
FROM eclipse-temurin:22-jre-jammy

RUN groupadd -g 1000 memlayer && \
    useradd -u 1000 -g memlayer -m memlayer

WORKDIR /app

COPY --from=builder /build/target/memlayer.jar /app/memlayer.jar
COPY config.edn /app/config.edn

RUN mkdir -p /data && \
    chown -R memlayer:memlayer /data

USER memlayer

EXPOSE 8080

ENTRYPOINT ["java", \
  "--add-modules", "jdk.incubator.vector", \
  "--enable-native-access=ALL-UNNAMED", \
  "-cp", "/app/memlayer.jar", \
  "memlayer.system"]
