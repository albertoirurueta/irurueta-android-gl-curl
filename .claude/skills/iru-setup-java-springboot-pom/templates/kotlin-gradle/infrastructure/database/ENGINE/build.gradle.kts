// infrastructure/database/<engine>/build.gradle.kts — same shape for every infrastructure/* and api/* module:
// project() lines per the table, then that module's Step 2 dependencies by alias.
plugins {
    id("iru.kotlin-library")
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.spring.boot.starter.data.mongodb) // whatever the reference build names
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
}
