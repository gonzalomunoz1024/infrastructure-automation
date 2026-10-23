// Publishing to GitHub Packages as com.iaf.console:<module>, with a sources jar, under the version
// of gradle.properties. CI signs in with GITHUB_ACTOR and GITHUB_TOKEN; a developer with a token
// of their own can publish the same way.

plugins {
    `java-library`
    `maven-publish`
}

group = "com.iaf.console"

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set(project.description ?: "A library of the customer console")
                url.set("https://github.com/gonzalomunoz1024/infrastructure-automation")
                scm {
                    url.set("https://github.com/gonzalomunoz1024/infrastructure-automation")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
