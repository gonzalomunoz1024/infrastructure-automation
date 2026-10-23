pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "console-libs"

include("wfrn", "service-kit", "platform-client", "platform-fake", "platform-fake-app")
