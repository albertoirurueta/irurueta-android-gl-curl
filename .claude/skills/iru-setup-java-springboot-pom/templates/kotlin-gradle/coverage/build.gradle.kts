// No sources: aggregates every module's JaCoCo data into
// build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml (what Sonar reads).
plugins {
    base
    `jacoco-report-aggregation`
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencies {
    jacocoAggregation(project(":domain"))
    jacocoAggregation(project(":application"))
    jacocoAggregation(project(":boot"))
}

reporting {
    reports {
        register<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName = "test"
        }
    }
}

tasks.named<JacocoReport>("testCodeCoverageReport") {
    // The aggregation plugin only collects the `test` suite's data; add each module's integrationTest.exec when it
    // exists (after `./gradlew integrationTest`), without forcing the integration tests to run — the same
    // "whatever ran" semantics as the Maven report-aggregate over jacoco*.exec.
    val aggregated = configurations.jacocoAggregation.get().dependencies.withType<ProjectDependency>()
    val itData =
        aggregated.map { dep ->
            rootProject.layout.projectDirectory.file(
                dep.path.removePrefix(":").replace(':', '/') + "/build/jacoco/integrationTest.exec",
            )
        }
    executionData.from(files(itData).filter { it.exists() })
    mustRunAfter(aggregated.map { "${it.path}:integrationTest" })
    // Same exclusions as each module's own report: generated code and the Boot main file class.
    classDirectories.setFrom(
        classDirectories.files.map { dir -> fileTree(dir) { exclude("**/generated/**", "**/*ApplicationKt.class") } },
    )
    reports {
        xml.required = true
        html.required = true
    }
}
