// console-state-service: the console's event-sourced resource inventory, named by wfrn.
// Built with the console's convention plugin (Java 21, every lint an error, WebFlux + actuator,
// ArchUnit rules on the test classpath) and the shared libraries of console-libs.

plugins {
    id("console.spring-service") version "0.1.0-SNAPSHOT"
}

val libsVersion: String = providers.gradleProperty("console.libs.version").get()

description = "The console's own record of every resource, from the first request to the tombstone."

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

    testImplementation(testFixtures("com.iaf.console:platform-client:$libsVersion"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.mockito:mockito-core")
}

springBoot {
    mainClass.set("com.iaf.console.state.StateServiceApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("console-state-service.jar")
}
