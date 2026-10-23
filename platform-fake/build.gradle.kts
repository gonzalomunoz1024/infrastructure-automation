plugins {
    id("console.java-library")
    id("console.publishing")
}

description = "An in-memory platform that implements the target contract, as a Spring WebFlux library a demo profile embeds."

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(project(":service-kit"))
    api(project(":wfrn"))
    api(libs.spring.boot.starter.webflux)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.json.schema.validator)
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":platform-client"))
    testImplementation(testFixtures(project(":platform-client")))
    testImplementation(testFixtures(project(":service-kit")))
    testImplementation(libs.bundles.spring.test)
}
