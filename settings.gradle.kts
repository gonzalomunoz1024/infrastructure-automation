// The shared code comes from console-libs as com.iaf.console:* (the convention plugins by id, the
// libraries by coordinates). Until the libraries are on GitHub Packages they come from the local
// Maven repository (`./gradlew publishToMavenLocal :build-logic:publishToMavenLocal` on
// console-libs, which CI runs first); the content filter keeps everything else on Maven Central.

pluginManagement {
    repositories {
        mavenLocal {
            // the convention plugins' markers are in the console.* groups, their code in com.iaf.console
            content {
                includeGroup("com.iaf.console")
                includeGroupByRegex("console\\..*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
                password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
            }
            content {
                includeGroup("com.iaf.console")
                includeGroupByRegex("console\\..*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content { includeGroup("com.iaf.console") }
        }
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
                password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
            }
            content { includeGroup("com.iaf.console") }
        }
    }
}

rootProject.name = "console-identity-service"
