plugins {
    id("console.java-library")
    id("console.publishing")
    `java-test-fixtures`
}

description = "What every console service is made of: account scope, errors, outbox, idempotent consumer, events, paging, MongoDB and telemetry helpers."

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(project(":wfrn"))
    api(libs.reactor.core)
    api(libs.jackson.databind)
    api(libs.spring.webflux)
    api(libs.mongodb.driver.reactivestreams)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.spring.context)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.data.mongodb.reactive)
    implementation(libs.reactor.kafka)
    implementation(libs.kafka.clients)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation)
    implementation(libs.micrometer.tracing)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.micrometer.context.propagation)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.json.schema.validator)
    implementation("org.slf4j:slf4j-api")

    // the architecture rules services run in their own tests
    testFixturesApi(platform(libs.spring.boot.dependencies))
    testFixturesApi(libs.archunit.junit5)

    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.bundles.spring.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}
