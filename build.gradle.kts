// The console BFF: one Spring Boot application on WebFlux, built with console-libs' conventions
// (Java 21, -Xlint:all -Werror, JUnit 5, Jacoco) and made of the service kit and the platform client.

plugins {
    id("console.spring-service")
}

description = "The console's backend for frontend: sign-in and sessions, type descriptors, live updates, jobs."

val libsVersion: String = providers.gradleProperty("console.libs.version").get()

dependencies {
    implementation("com.iaf.console:service-kit:$libsVersion")
    implementation("com.iaf.console:platform-client:$libsVersion")
    // the demo profile embeds the fake platform in this process; only the demo adapters may touch it (ArchitectureTest)
    implementation("com.iaf.console:platform-fake:$libsVersion")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.projectreactor.kafka:reactor-kafka")
    // notification emails over SMTP (decision 32); the demo profile collects them in memory instead
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(testFixtures("com.iaf.console:platform-client:$libsVersion"))
}

springBoot {
    mainClass.set("com.iaf.console.bff.ConsoleBffApplication")
}

tasks.withType<Test>().configureEach {
    // the demo identity provider and the embedded fake sign with keys generated per run; nothing is read from the environment but the Mongo URI
    environment("CONSOLE_TEST_MONGO_URI", System.getenv("CONSOLE_TEST_MONGO_URI") ?: "")
}
