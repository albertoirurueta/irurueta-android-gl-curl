// JaCoCo for every Test task (test.exec and integrationTest.exec under build/jacoco/), plus a per-module XML report.
plugins {
    jacoco
}

val libs = versionCatalogs.named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    // Generated code (OpenAPI/protobuf/AVRO/GraphQL output) never counts towards coverage.
    classDirectories.setFrom(
        classDirectories.files.map { dir -> fileTree(dir) { exclude("**/generated/**", "**/*ApplicationKt.class") } },
    )
    reports {
        xml.required = true
        html.required = true
    }
}
