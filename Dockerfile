# syntax=docker/dockerfile:1

# ---- build: compile, package, and index the sample corpus --------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# Download Gradle once, in its own cached layer.
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY . .
RUN ./gradlew --no-daemon :server:installDist :indexer:installDist :indexer:sampleIndex

# ---- runtime ----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 ase \
 && useradd --system --uid 10001 --gid ase --home-dir /app --shell /usr/sbin/nologin ase \
 && mkdir -p /data && chown ase:ase /data
COPY --from=build /src/server/build/install/ase-server /app/server
COPY --from=build /src/indexer/build/install/ase-indexer /app/indexer
COPY --from=build --chown=ase:ase /src/build/sample.idx /data/index.idx

ENV PATH="/app/server/bin:/app/indexer/bin:${PATH}" \
    ASE_INDEX=/data/index.idx \
    ASE_HOST=0.0.0.0 \
    ASE_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

USER ase
WORKDIR /data
EXPOSE 8080
ENTRYPOINT ["ase-server"]
