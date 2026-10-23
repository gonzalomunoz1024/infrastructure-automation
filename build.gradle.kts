// console-drift-service: reads every managed resource's truth from its provider on its tier's
// interval, opens findings when it differs from the desired state the state service holds, and
// lets the account revert, accept or ignore them. Built with the console's convention plugin
// (Java 21, every lint an error, WebFlux + actuator, ArchUnit rules on the test classpath) and the
// shared libraries of console-libs.

plugins {
    id("console.spring-service") version "0.1.0-SNAPSHOT"
}

val libsVersion: String = providers.gradleProperty("console.libs.version").get()
val temporalVersion: String = providers.gradleProperty("console.temporal.version").get()

description = "Drift detection for the customer console: tiered provider reads, findings, one-click revert."

dependencies {
    implementation("com.iaf.console:service-kit:$libsVersion")
    implementation("com.iaf.console:platform-client:$libsVersion")
    implementation("com.iaf.console:wfrn:$libsVersion")
    // the demo profile embeds the fake platform in this JVM
    implementation("com.iaf.console:platform-fake:$libsVersion")

    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.projectreactor.kafka:reactor-kafka")
    implementation("org.apache.kafka:kafka-clients")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // the shard loops of the plan run as Temporal workflows under the temporal profile (the
    // platform's own Temporal version)
    implementation("io.temporal:temporal-sdk:$temporalVersion")

    testImplementation(testFixtures("com.iaf.console:platform-client:$libsVersion"))
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
    // the contract schemas the API answers and the events are checked against (the version platform-fake brings)
    testImplementation("com.networknt:json-schema-validator:1.5.8")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.mockito:mockito-core")
}

springBoot {
    mainClass.set("com.iaf.console.drift.DriftServiceApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("console-drift-service.jar")
}
