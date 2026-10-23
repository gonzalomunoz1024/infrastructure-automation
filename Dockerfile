# The console BFF image: the boot jar on a slim JRE, as a non-root user.
# Build the jar first: ./gradlew bootJar
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S console && adduser -S -G console console
WORKDIR /app
COPY build/libs/console-bff-*.jar /app/console-bff.jar
USER console
EXPOSE 18081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
ENTRYPOINT ["java", "-jar", "/app/console-bff.jar"]
