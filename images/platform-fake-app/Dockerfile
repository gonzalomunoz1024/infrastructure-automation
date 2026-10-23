# platform-fake-app (console-libs) as an image; console-libs has no Dockerfile (F-8). Build context:
# the checked-out console-libs commit, after `./gradlew :platform-fake-app:bootJar` (scripts/build.sh).
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S console && adduser -S -G console console
WORKDIR /app
COPY platform-fake-app/build/libs/platform-fake-app-*.jar /app/platform-fake-app.jar
USER console
EXPOSE 18090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "/app/platform-fake-app.jar"]
