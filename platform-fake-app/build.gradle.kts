plugins {
    id("console.spring-service")
}

description = "The fake platform as a runnable application."

dependencies {
    implementation(project(":platform-fake"))
    // the Kafka half of the P-01 feed, under the kafka profile
    implementation(libs.kafka.clients)
}

springBoot {
    mainClass.set("com.iaf.console.platformfake.app.PlatformFakeApplication")
}
