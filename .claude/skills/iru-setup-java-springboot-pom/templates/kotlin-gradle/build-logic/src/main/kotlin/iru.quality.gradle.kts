// detekt + ktlint (both fail the build on any finding, like Checkstyle/PMD/SpotBugs on the Java services)
// and Dokka (KDoc HTML).
import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.dokka")
}

val libs = versionCatalogs.named("libs")

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = libs.findVersion("java").get().requiredVersion
    exclude("**/generated/**")
    reports {
        xml.required = true // build/reports/detekt/detekt.xml, imported by Sonar
        html.required = true
        sarif.required = false
        txt.required = false
        md.required = false
    }
}

ktlint {
    version = libs.findVersion("ktlint").get().requiredVersion
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE) // build/reports/ktlint/<task>/<task>.xml, imported by Sonar
    }
    filter {
        exclude { it.file.path.contains("/generated/") || it.file.path.contains("/build/") }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        documentedVisibilities(
            org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Public,
            org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Protected,
            org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Internal,
        )
        // Generated sources (registered on the source sets by the codegen plugins) are not documented.
        suppressedFiles.from(layout.buildDirectory.dir("generated"))
        perPackageOption {
            matchingRegex = ".*\\.generated(\\..*)?"
            suppress = true
        }
    }
}
