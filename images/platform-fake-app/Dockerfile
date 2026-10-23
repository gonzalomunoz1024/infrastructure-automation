# platform-fake-app (console-libs) as an image; console-libs has no Dockerfile. Build context: the
# checked-out console-libs commit, after `./gradlew :platform-fake-app:bootJar` (scripts/build.sh).
#
# The base is the Temurin 21 JDK, not the JRE the console services use: PlatformFake calls
# RandomGenerator.getDefault() (L32X64MixRandom), which lives in the jdk.random module, and the
# Temurin JRE images leave that module out, so the fake fails at boot on them (finding F-1 in
# README.md).
FROM eclipse-temurin:21-jdk-alpine

RUN addgroup -S console && adduser -S -G console console
WORKDIR /app
COPY platform-fake-app/build/libs/platform-fake-app-*.jar /app/platform-fake-app.jar
USER console
EXPOSE 18090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "/app/platform-fake-app.jar"]
