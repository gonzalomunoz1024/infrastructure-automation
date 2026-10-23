// The console BFF builds with the convention plugins and libraries of console-libs
// (com.iaf.console:*). They come from GitHub Packages once console-libs publishes them, and from
// the local Maven repository until then (CI publishes console-libs there first; a developer runs
// `./gradlew publishToMavenLocal` on the console-libs branch). Both repositories only ever serve
// the console's own groups; everything else comes from Maven Central and the plugin portal.
// The repository block is spelled out twice because pluginManagement is evaluated before the
// rest of this script and cannot see a function declared here.

pluginManagement {
    val consoleGroups = "com\\.iaf\\.console|console\\..*"
    val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
    val gprToken = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
    repositories {
        mavenLocal {
            content { includeGroupByRegex(consoleGroups) }
        }
        if (gprToken.isNotBlank()) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
                credentials {
                    username = gprUser
                    password = gprToken
                }
                content { includeGroupByRegex(consoleGroups) }
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    // The convention plugins live in com.iaf.console:build-logic; mapping the ids here needs no
    // plugin marker artifacts, whichever repository served the module.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("console.")) {
                useModule("com.iaf.console:build-logic:${providers.gradleProperty("console.libs.version").get()}")
            }
        }
    }
    plugins {
        id("console.spring-service") version providers.gradleProperty("console.libs.version").get()
    }
}

dependencyResolutionManagement {
    val consoleGroups = "com\\.iaf\\.console|console\\..*"
    val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
    val gprToken = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
    repositories {
        mavenLocal {
            content { includeGroupByRegex(consoleGroups) }
        }
        if (gprToken.isNotBlank()) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
                credentials {
                    username = gprUser
                    password = gprToken
                }
                content { includeGroupByRegex(consoleGroups) }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "console-bff"
