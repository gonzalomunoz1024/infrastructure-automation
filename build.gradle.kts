// console-cost-service: the cost ledger priced from the platform's price lists, the Cost Explorer's
// queries, budgets with forecasts and alerts, and the monthly chargeback (hub decision 25). Built
// with the console's convention plugin (Java 21, every lint an error, WebFlux + actuator, ArchUnit
// rules on the test classpath) and the shared libraries of console-libs.

plugins {
    id("console.spring-service") version "0.1.0-SNAPSHOT"
}

val libsVersion: String = providers.gradleProperty("console.libs.version").get()

description = "The console's cost: a ledger priced from the platform's price lists, budgets and chargeback."

dependencies {
    implementation("com.iaf.console:service-kit:$libsVersion")
    implementation("com.iaf.console:platform-client:$libsVersion")
    implementation("com.iaf.console:wfrn:$libsVersion")

    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("io.projectreactor.kafka:reactor-kafka")
    implementation("org.apache.kafka:kafka-clients")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    // the bundled price lists and bindings, and the contracts the tests read
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation("org.mockito:mockito-core")
}

springBoot {
    mainClass.set("com.iaf.console.cost.CostServiceApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("console-cost-service.jar")
}
