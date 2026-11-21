import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("iru.spring-boot-app")
}

dependencies {
    // Every other leaf module of this project except coverage.
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(libs.spring.boot.starter)
    testImplementation(libs.springmockk)
    testImplementation(libs.archunit.junit5)
}

springBoot {
    // A top-level `fun main` in <ServiceName>Application.kt compiles to <ServiceName>ApplicationKt.
    mainClass = "<base-package>.<ServiceName>ApplicationKt"
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName = "<image-registry>/<artifact-id>:${project.version}"
}

val buildInfoDir = layout.buildDirectory.dir("generated/build-info")
val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        val outputFile = buildInfoDir.map { it.file("<base-package-path>/build-info.properties") }

        fun env(name: String) = providers.environmentVariable(name)
        val values =
            mapOf(
                "GROUP_ID" to provider { project.group.toString() },
                "ARTIFACT_ID" to provider { "${rootProject.name}-${project.name}" },
                "VERSION" to provider { project.version.toString() },
                // GitHub Actions first, then Jenkins, then GitLab — as in the Maven template.
                "BUILD_NUMBER" to env("GITHUB_RUN_NUMBER").orElse(env("BUILD_NUMBER")).orElse(env("CI_JOB_ID")),
                "COMMIT" to env("GITHUB_SHA").orElse(env("GIT_COMMIT")).orElse(env("CI_COMMIT_SHA")),
                "BRANCH" to env("GITHUB_REF_NAME").orElse(env("GIT_BRANCH")).orElse(env("CI_COMMIT_REF_NAME")),
            )
        outputs.file(outputFile)
        outputs.upToDateWhen { false }
        doLast {
            val props = Properties()
            props.setProperty("BUILD_TIMESTAMP", SimpleDateFormat("yy-MM-dd HH:mm:ss").format(Date()))
            values.forEach { (key, value) -> value.orNull?.let { props.setProperty(key, it) } }
            val file = outputFile.get().asFile
            file.parentFile.mkdirs()
            file.writer().use { props.store(it, null) }
        }
    }

sourceSets.main {
    resources.srcDir(files(buildInfoDir).builtBy(generateBuildInfo))
}
