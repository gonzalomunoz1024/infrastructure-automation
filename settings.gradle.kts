// The build of console-cost-service. The convention plugins and the shared libraries come from
// console-libs (com.iaf.console:*), published to GitHub Packages from merges to console-libs; a
// developer who builds console-libs with `publishToMavenLocal` gets them from mavenLocal first.
// The GitHub Packages repository is added only when credentials are at hand (gpr.user/gpr.token
// properties or GITHUB_ACTOR/GITHUB_TOKEN), so a local build without a token still resolves.

pluginManagement {
    val packagesUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("")
    val packagesToken = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        if (packagesToken.get().isNotBlank()) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
                credentials {
                    username = packagesUser.get()
                    password = packagesToken.get()
                }
            }
        }
    }
}

dependencyResolutionManagement {
    val packagesUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("")
    val packagesToken = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")
    repositories {
        mavenLocal()
        mavenCentral()
        if (packagesToken.get().isNotBlank()) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
                credentials {
                    username = packagesUser.get()
                    password = packagesToken.get()
                }
            }
        }
    }
}

rootProject.name = "console-cost-service"
