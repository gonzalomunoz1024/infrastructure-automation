# console-identity-service has no Dockerfile on its branch yet, so console-e2e carries one in the
# shape of its siblings' (the boot jar on a Temurin 21 JRE, as a non-root user). Build context:
# the checked-out identity commit, after `./gradlew bootJar` there (scripts/build.sh).
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S console && adduser -S -G console console
WORKDIR /app
COPY build/libs/console-identity-service-*.jar /app/console-identity-service.jar
USER console
EXPOSE 18083
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "/app/console-identity-service.jar"]
