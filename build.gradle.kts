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

// The state side of the load test (work package 6.2): src/load holds the seeder that fills MongoDB
// with the synthetic estate through the service's own inventory; the Gatling simulation that reads
// it lives with the search indexer's (console-search-indexer, src/gatling, StateSimulation).
val load: SourceSet = sourceSets.create("load") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath + configurations.runtimeClasspath.get()
}

tasks.register<JavaExec>("seedEstate") {
    description = "Fills MongoDB with the load test's estate (-Pload.mongo, -Pload.size, -Pload.accounts, -Pload.concurrency, -Pload.from)"
    group = "console"
    classpath = load.runtimeClasspath
    mainClass.set("com.iaf.console.state.load.SeedStateEstate")
    systemProperties(providers.gradlePropertiesPrefixedBy("load.").get())
}
