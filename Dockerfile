# The service's image: the boot jar on a current Temurin JRE, as a non-root user, with the
# container-aware JVM defaults. Build the jar first: ./gradlew bootJar
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S console && adduser -S -G console console
WORKDIR /app
COPY build/libs/console-drift-service.jar /app/console-drift-service.jar
USER console
EXPOSE 18084
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "/app/console-drift-service.jar"]
