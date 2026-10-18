# The platform's backend. Build from backend/:
#   docker build -t platform-backend backend
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# the tests also compile the Java provider SDK in ../sdk, outside this context; CI runs them
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home platform
WORKDIR /app
COPY --from=build /build/target/platform-*.jar app.jar
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
