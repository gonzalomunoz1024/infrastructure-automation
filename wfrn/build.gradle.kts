plugins {
    id("console.java-library")
    id("console.publishing")
}

description = "The wfrn resource name: parse, format, patterns, minted ids and ULIDs. No dependencies."

dependencies {
    // the fixtures file is JSON; the library itself depends on nothing
    testImplementation(libs.jackson.databind)
}
