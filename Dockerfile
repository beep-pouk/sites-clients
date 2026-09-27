# Builds and runs the relay server (server/) as a standalone Java application.
# Used for deploying to Render (or any other Docker-based host).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/server/build/install/server /app

# Render (and most PaaS hosts) inject PORT at runtime; the server already reads it.
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
