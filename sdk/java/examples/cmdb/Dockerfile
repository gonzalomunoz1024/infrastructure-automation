# The example provider's worker. Build from sdk/java:
#   docker build -f examples/cmdb/Dockerfile -t cmdb-provider .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY provider-sdk ./provider-sdk
COPY examples/cmdb ./examples/cmdb
RUN mvn -B -q -pl examples/cmdb -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home provider
WORKDIR /app
COPY --from=build /build/examples/cmdb/target/cmdb-provider.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["run"]
