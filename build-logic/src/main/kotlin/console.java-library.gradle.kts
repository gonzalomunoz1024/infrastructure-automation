import console.ConsoleVersions

// A plain Java library of the console: Java 21, every lint an error, parameter names kept for
// Jackson and Spring, JUnit 5 on the JUnit Platform, readable test output and a Jacoco report.

plugins {
    `java-library`
    jacoco
}

group = "com.iaf.console"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    (options as StandardJavadocDocletOptions).addStringOption("Xmaxwarns", "1")
    isFailOnError = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "768m"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    systemProperty("junit.jupiter.execution.timeout.default", "5m")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

dependencies {
    // the Boot BOM aligns the test libraries; it reaches no published POM from here
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${ConsoleVersions.springBoot}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
}
