// console-search-indexer: Kafka to OpenSearch indexing of the console's resources, and the search
// API the BFF uses. Built with the console's convention plugin (Java 21, every lint an error,
// WebFlux + actuator, ArchUnit rules on the test classpath) and the shared libraries of console-libs.

plugins {
    id("console.spring-service") version "0.1.0-SNAPSHOT"
}

val libsVersion: String = providers.gradleProperty("console.libs.version").get()

description = "The console's search index: resources from Kafka into OpenSearch, and the search API."

dependencies {
    implementation("com.iaf.console:service-kit:$libsVersion")
    implementation("com.iaf.console:platform-client:$libsVersion")
    implementation("com.iaf.console:wfrn:$libsVersion")

    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("io.projectreactor.kafka:reactor-kafka")
    implementation("org.apache.kafka:kafka-clients")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // the tests start the fake platform in process for the resource types, and read the contracts as YAML
    testImplementation("com.iaf.console:platform-fake:$libsVersion")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.mockito:mockito-core")
}

springBoot {
    mainClass.set("com.iaf.console.search.SearchIndexerApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("console-search-indexer.jar")
}

// The OpenSearch the local script runs (scripts/opensearch.sh), with no Docker: the integ-test
// distribution from Maven Central plus the two modules it leaves out and the index needs,
// analysis-common (the edge n-grams and the lowercase normalizer) and reindex (_delete_by_query).
// Every full distribution and image has both. The tests use the node CONSOLE_TEST_OPENSEARCH_URL
// names (CI starts the image) and skip without it.
val opensearchVersion = "2.19.6"
val opensearchDist: Configuration by configurations.creating
val opensearchAnalysisModule: Configuration by configurations.creating { isTransitive = false }
val opensearchReindexModule: Configuration by configurations.creating { isTransitive = false }

dependencies {
    opensearchDist("org.opensearch.distribution.integ-test-zip:opensearch:$opensearchVersion@zip")
    opensearchAnalysisModule("org.opensearch.plugin:analysis-common:$opensearchVersion")
    // the module's jar is published as reindex-client; the rest is what the module bundles
    opensearchReindexModule("org.opensearch.plugin:reindex-client:$opensearchVersion")
    opensearchReindexModule("org.opensearch.client:opensearch-rest-client:$opensearchVersion")
    opensearchReindexModule("org.opensearch:opensearch-ssl-config:$opensearchVersion")
    opensearchReindexModule("org.apache.httpcomponents:httpclient:4.5.14")
    opensearchReindexModule("org.apache.httpcomponents:httpcore:4.4.16")
    opensearchReindexModule("org.apache.httpcomponents:httpasyncclient:4.1.5")
    opensearchReindexModule("org.apache.httpcomponents:httpcore-nio:4.4.16")
    opensearchReindexModule("commons-codec:commons-codec:1.18.0")
    opensearchReindexModule("commons-logging:commons-logging:1.2")
}

tasks.register<Sync>("opensearchDist") {
    description = "Assembles OpenSearch $opensearchVersion (integ-test distribution and two modules) into build/opensearch for scripts/opensearch.sh"
    group = "console"
    val home = "opensearch-$opensearchVersion"
    from(opensearchDist.map { zipTree(it) })
    into(layout.buildDirectory.dir("opensearch"))
    into("$home/modules/analysis-common") {
        from(opensearchAnalysisModule)
        from("src/opensearch/modules/analysis-common") { expand("version" to opensearchVersion) }
    }
    into("$home/modules/reindex") {
        from(opensearchReindexModule)
        from("src/opensearch/modules/reindex") { filesMatching("*.properties") { expand("version" to opensearchVersion) } }
    }
}
