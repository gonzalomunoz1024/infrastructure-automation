plugins {
    id("console.java-library")
    id("console.publishing")
    `java-test-fixtures`
}

description = "A reactive client of the platform's target API, typed against the hub's contract and contract tested."

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(project(":wfrn"))
    api(libs.reactor.core)
    api(libs.jackson.databind)
    api(libs.spring.webflux)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.reactor.netty.http)
    implementation(libs.nimbus.jose.jwt)
    implementation("org.slf4j:slf4j-api")

    // the contract test every platform (the fake and the real one) is run through
    testFixturesApi(platform(libs.spring.boot.dependencies))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
    testFixturesApi(libs.reactor.test)
    testFixturesImplementation(libs.nimbus.jose.jwt)

    testImplementation(libs.jackson.dataformat.yaml)
    testImplementation(libs.reactor.test)
}
