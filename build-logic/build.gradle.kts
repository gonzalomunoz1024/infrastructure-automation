import java.util.Properties

// The convention plugins every console module and service builds with. An included build of the
// root, and published on its own as com.iaf.console:build-logic (plus the plugin markers) so a
// service in another repository applies the same plugins by id.

plugins {
    `kotlin-dsl`
    `maven-publish`
}

val rootProperties = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}

group = "com.iaf.console"
version = rootProperties.getProperty("version")

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
}

// The versions the plugins add to a service's classpath, read from the catalog here at build time
// so the plugins carry no second copy of a version.
val generatedVersions = layout.buildDirectory.dir("generated/versions")
val writeVersions by tasks.registering {
    val bootVersion = libs.versions.springBoot.get()
    val archunitVersion = libs.versions.archunit.get()
    val outDir = generatedVersions
    inputs.property("springBoot", bootVersion)
    inputs.property("archunit", archunitVersion)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("console-versions.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("springBoot=$bootVersion\narchunit=$archunitVersion\n")
    }
}
sourceSets.main {
    resources.srcDir(writeVersions)
}

publishing {
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
