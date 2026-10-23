// com.iaf.console:console-sdk. The API classes (com.iaf.console.sdk.api), the models
// (com.iaf.console.sdk.model) and the HTTP plumbing (com.iaf.console.sdk.client) are generated
// at build time from ../openapi/console-bff.yaml, library `native` (java.net.http.HttpClient). The
// hand-written com.iaf.console.sdk adds the device sign-in, bearer auth with refresh, an
// Idempotency-Key on every write, retries of 429 and 503, paging and typed errors.

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import java.security.MessageDigest

plugins {
    `java-library`
    `maven-publish`
    id("org.openapi.generator") version "7.25.0"
}

group = "com.iaf.console"
description = "The console BFF's API for Java 21: generated from its contract, with sign-in, refresh, idempotency keys, retries and paging"

val contract = layout.projectDirectory.file("../openapi/console-bff.yaml")
val generated = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("java")
    library.set("native")
    inputSpec.set(contract.asFile.path)
    outputDir.set(generated)
    apiPackage.set("com.iaf.console.sdk.api")
    modelPackage.set("com.iaf.console.sdk.model")
    invokerPackage.set("com.iaf.console.sdk.client")
    groupId.set("com.iaf.console")
    id.set("console-sdk")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    // `monthly` is a number or a map of numbers (a oneOf the generator cannot write): a JsonNode holds either
    schemaMappings.set(mapOf("PriceList_prices_inner_monthly" to "com.fasterxml.jackson.databind.JsonNode"))
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "useJakartaEe" to "true",
            "openApiNullable" to "false",
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "hideGenerationTimestamp" to "true",
            // toUrlQueryString serves deepObject query parameters, and the contract has none
            "supportUrlQuery" to "false",
            "sourceFolder" to "src/main/java",
        ),
    )
}

// What the hand-written client needs to know of the contract, as Java: each operation's method,
// path and whether it takes an Idempotency-Key (such a write is answered once, so it may be
// retried), the contract's SHA-256 and the SDK's version. Read with the generator's own parser.
val contractInfo = layout.buildDirectory.dir("generated/contract-info")

val writeContractInfo by tasks.registering {
    description = "Writes com.iaf.console.sdk.ContractInfo from the contract"
    val source = contract.asFile
    val sdkVersion = project.version.toString()
    val target = contractInfo.map { it.file("src/main/java/com/iaf/console/sdk/ContractInfo.java") }
    inputs.file(source)
    inputs.property("version", sdkVersion)
    outputs.file(target)
    doLast {
        val parsed = OpenAPIV3Parser().readLocation(source.toURI().toString(), null, ParseOptions().apply { isResolveFully = true })
        val api = parsed.openAPI ?: throw GradleException("${source.name} does not parse: ${parsed.messages}")
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val rows = api.paths.flatMap { (path, item) ->
            item.readOperationsMap().map { (method, op) ->
                val params = item.parameters.orEmpty() + op.parameters.orEmpty()
                val keyed = params.any { it.`in` == "header" && it.name.equals("Idempotency-Key", ignoreCase = true) }
                val id = op.operationId ?: throw GradleException("$method $path has no operationId")
                "        new Operation(${quote(id)}, ${quote(method.name)}, ${quote(path)}, $keyed)"
            }
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.iaf.console.sdk;
            |
            |import java.util.List;
            |
            |// Written by the build (writeContractInfo) from openapi/console-bff.yaml. Do not edit.
            |final class ContractInfo {
            |    private ContractInfo() {}
            |
            |    static final String SDK_VERSION = ${quote(sdkVersion)};
            |
            |    /** The SHA-256 of the contract this SDK was generated from (contracts.lock pins it). */
            |    static final String CONTRACT_SHA256 = ${quote(sha256)};
            |
            |    /** Every operation of the contract, in its order. */
            |    static final List<Operation> OPERATIONS = List.of(
            |${rows.joinToString(",\n")});
            |}
            |""".trimMargin(),
        )
    }
}

sourceSets {
    main {
        java.srcDir(generated.map { it.dir("src/main/java") })
        java.srcDir(contractInfo.map { it.dir("src/main/java") })
    }
}

java {
    withSourcesJar()
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate, writeContractInfo)
}

tasks.named("sourcesJar") {
    dependsOn(tasks.openApiGenerate, writeContractInfo)
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "console-sdk", "Implementation-Version" to project.version)
    }
}

dependencies {
    api(platform("com.fasterxml.jackson:jackson-bom:2.21.7"))
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
    // the generated code writes form bodies (issueServiceToken's) with it
    implementation("org.apache.httpcomponents:httpmime:4.5.14")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // the contract and its lock are the repository's, one directory up
    workingDir = layout.projectDirectory.asFile
    inputs.file(contract)
    inputs.file(layout.projectDirectory.file("../contracts.lock"))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            artifactId = "console-sdk"
            from(components["java"])
        }
    }
}
