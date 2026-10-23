// One Spring WebFlux service, built with the console's convention plugin: Java 21, every lint an
// error, the service kit's ArchUnit rules on the test classpath, Spring Boot's BOM for every version.

plugins {
    id("console.spring-service") version "0.1.0-SNAPSHOT"
}

description = "The customer console's identity service: accounts, users, groups, roles, policies, access keys, quotas and authorization."

val consoleLibs: String = providers.gradleProperty("console.libs.version").get()

dependencies {
    implementation("com.iaf.console:service-kit:$consoleLibs")
    implementation("com.iaf.console:platform-client:$consoleLibs")
    implementation("com.iaf.console:wfrn:$consoleLibs")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("com.nimbusds:nimbus-jose-jwt")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // the demo profile embeds the fake platform (console.platform-fake.enabled); tests start one in process
    runtimeOnly("com.iaf.console:platform-fake:$consoleLibs")
    testImplementation("com.iaf.console:platform-fake:$consoleLibs")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.mockito:mockito-core")
}

springBoot {
    mainClass.set("com.iaf.console.identity.IdentityServiceApplication")
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}
