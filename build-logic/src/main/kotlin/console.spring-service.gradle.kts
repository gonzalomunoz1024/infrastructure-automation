import console.ConsoleVersions

// A console service: a Spring Boot application on WebFlux with the actuator, built as a Java
// library of the console, with the kit's ArchUnit rules on its test classpath so one test class
// per service holds the whole code base to the platform's layout.

plugins {
    id("console.java-library")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Inside console-libs the kit is the project beside this one; in a service repository it is the
// published artifact of the version the service names in gradle.properties (console.libs.version).
val serviceKit: Any = rootProject.findProject(":service-kit")
    ?: "com.iaf.console:service-kit:${providers.gradleProperty("console.libs.version").getOrElse(version.toString())}"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:${ConsoleVersions.archunit}")
    testImplementation(testFixtures(serviceKit))
}
