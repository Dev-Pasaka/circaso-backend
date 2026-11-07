# syntax=docker/dockerfile:1
# Packages the server and runs it as a non-root user. Compile first (the deploy workflow's image job does):
#   ./gradlew :app:installDist -x test
# so building the image is only a copy and takes seconds, while Gradle's cache does the compiling.
# Settings come from environment variables at run time; nothing secret is in the image.

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 circaso \
 && useradd --system --uid 10001 --gid circaso --home-dir /app --shell /usr/sbin/nologin circaso
WORKDIR /app
COPY --chown=circaso:circaso app/build/install/app/ /app/
USER circaso:circaso
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=4 \
  CMD curl -fsS http://localhost:8080/health/live || exit 1
# `bin/app` serves. The deploy workflow runs the migrations first, once, from this same image on the server:
#   java -cp "/app/lib/*" com.circaso.app.Migrate   (locally: ./gradlew :app:migrate)
# The ops gateway (the staff API, decision 2026-11-08) is this same image with another main class, on :8081:
#   java -cp "/app/lib/*" com.circaso.app.OpsMain   (locally: ./gradlew :app:runOps; compose service `ops`)
ENTRYPOINT ["/app/bin/app"]
