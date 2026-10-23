plugins {
    id("console.spring-service")
}

description = "The fake platform as a runnable application."

dependencies {
    implementation(project(":platform-fake"))
}

springBoot {
    mainClass.set("com.iaf.console.platformfake.app.PlatformFakeApplication")
}
