// Root project: no sources. Puts build-logic (and with it the Kotlin, Spring Boot, detekt, ktlint and Dokka
// Gradle plugins) on the root classpath once, aggregates Dokka into build/dokka/html, and configures Sonar.
plugins {
    id("iru.quality") apply false
    alias(libs.plugins.sonarqube)
}

apply(plugin = "org.jetbrains.dokka")

dependencies {
    // One entry per Kotlin module (every leaf module except coverage), so dokkaGeneratePublicationHtml
    // aggregates them into build/dokka/html.
    "dokka"(project(":domain"))
    "dokka"(project(":application"))
    "dokka"(project(":boot"))
}

sonar {
    properties {
        property("sonar.organization", "<sonar-organization>")
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.host.url", "<sonar-host-url>")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "$rootDir/coverage/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml",
        )
        // Relative paths: each module resolves them against its own directory.
        property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
        property(
            "sonar.kotlin.ktlint.reportPaths",
            "build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.xml," +
                "build/reports/ktlint/ktlintTestSourceSetCheck/ktlintTestSourceSetCheck.xml",
        )
        property("sonar.exclusions", "**/generated/**,**/build/generated/**")
    }
}
