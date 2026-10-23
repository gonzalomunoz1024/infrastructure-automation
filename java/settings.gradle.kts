// The console's Java SDK, com.iaf.console:console-sdk: the BFF's contract that ../contracts.lock
// pins, generated with the OpenAPI Generator (Java 21's HttpClient), and the hand-written
// ConsoleClient over it. Plugins come from the Gradle plugin portal, libraries from Maven Central.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "console-sdk"
