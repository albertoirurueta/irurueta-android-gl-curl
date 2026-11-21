# Gradle (Kotlin DSL) post-processing (single-module)

What Step 8 of the skill applies to the `build.gradle.kts` that `quarkus-maven-plugin:create -DbuildTool=gradle-kotlin-dsl`
wrote (Kotlin DSL only — this skill never generates Groovy build scripts). Keep what `create` wrote in
`settings.gradle.kts` (`pluginManagement` resolving `io.quarkus` from `gradle.properties`) and `gradle.properties`
(`quarkusPluginVersion`, `quarkusPlatform*`), and the Gradle wrapper it generated (Gradle 9.7.0 for platform 3.40 —
don't downgrade it). A hexagonal build uses `hexagonal.md` instead.

Verified with Quarkus 3.40.1, Gradle 9.7.0 (the generated wrapper) on JDK 21 inside the `gradle:8-jdk21` image with
the host Docker socket mounted: `./gradlew check quarkusIntTest` (Dev Services PostgreSQL + the packaged-jar IT),
the unit-only command at the end of this file, `detekt ktlintCheck dokkaGeneratePublicationHtml` (Kotlin),
`checkstyleMain pmdMain spotbugsMain javadoc` (Java), and Sonar plugin configuration (evaluated, not run against a
server). Plugin versions were the latest on the Gradle Plugin Portal at the time of writing — re-resolve them.

## Version and group live in `gradle.properties`

Move `group = "…"` and `version = "…"` out of `build.gradle.kts` into `gradle.properties` (Gradle maps both keys onto
every project). One literal is what `iru-java-bump-version`, `iru-release` and the Sync workflow's script rewrite:

```properties
# Project coordinates — the single place the version lives (read by iru-java-bump-version and the Sync workflow)
group=<group-id>
version=<project-version>
```

## Kotlin — the resulting `build.gradle.kts`

```kotlin
import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.allopen") version "2.4.10"
    kotlin("plugin.noarg") version "2.4.10"
    id("io.quarkus")
    jacoco
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.jetbrains.dokka") version "2.2.0"
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    // … the extension dependencies `create` wrote, unchanged (quarkus-rest, quarkus-kotlin, quarkus-arc, …) …
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.quarkus:quarkus-junit-component")
    testImplementation("io.quarkus:quarkus-junit-mockito")
    testImplementation("io.quarkus:quarkus-jacoco")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.mockk:mockk-jvm:1.14.11")
}

java {
    sourceCompatibility = JavaVersion.VERSION_<java-version>
    targetCompatibility = JavaVersion.VERSION_<java-version>
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

noArg {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_<java-version>
        javaParameters = true
    }
}

// --- Tests and coverage (iru-setup-quarkus-service) ----------------------------------------------------------------
// quarkus-jacoco records the classes Quarkus transforms (@QuarkusComponentTest, @QuarkusTest) into
// build/jacoco-quarkus.exec (quarkus.jacoco.data-file). Without a @QuarkusTest in the run its runtime falls back to
// the jacoco-agent.destfile system property (default ./jacoco.exec), so point that at the same file. The JaCoCo agent
// (plain tests) keeps build/jacoco/test.exec; the report and the 80% check read both.
val quarkusJacocoData = layout.buildDirectory.file("jacoco-quarkus.exec")

tasks.test {
    systemProperty("jacoco-agent.destfile", quarkusJacocoData.get().asFile.absolutePath)
    doFirst { delete(quarkusJacocoData) }
    extensions.configure<JacocoTaskExtension> {
        excludeClassLoaders = listOf("*QuarkusClassLoader")
    }
    // ci-tests: unit — CI passes -PexcludeTestsFile=<file> listing the @QuarkusTest classes (class-file patterns,
    // one per line) so only container-free plain and component tests run.
    providers.gradleProperty("excludeTestsFile").orNull?.let { path ->
        file(path).readLines().filter { it.isNotBlank() }.forEach { exclude(it.trim()) }
    }
    finalizedBy(tasks.jacocoTestReport)
}

val jacocoExecutionData =
    fileTree(layout.buildDirectory) { include("jacoco/test.exec", "jacoco-quarkus.exec") }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    executionData.setFrom(jacocoExecutionData)
    reports {
        xml.required = true
        csv.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    executionData.setFrom(jacocoExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// --- Static analysis and docs ----------------------------------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin", "src/integrationTest/kotlin")
}

// detekt 1.23.x is compiled against Kotlin 2.0.x: keep its own classpath on that compiler instead of the project's.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                io.gitlab.arturbosch.detekt
                    .getSupportedKotlinVersion(),
            )
        }
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "<java-version>"
    reports {
        xml.required = true
        html.required = true
        sarif.required = false
        md.required = false
        txt.required = false
    }
}

ktlint {
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from("module.md")
    }
}
```

Notes:

- **all-open/no-arg** — `create` adds `plugin.allopen` with four annotations; this adds `RequestScoped` and the
  `plugin.noarg` block exactly as `iru-quarkus-code-one-task/reference/kotlin.md` requires, using the Kotlin version
  `create` pinned (the one the platform manages — 2.4.10 for 3.40).
- **Coverage** — same scheme as Maven (`maven.md` explains why): the agent writes `build/jacoco/test.exec`,
  `quarkus-jacoco` writes `build/jacoco-quarkus.exec` (the `jacoco-agent.destfile` property covers runs without a
  `@QuarkusTest`; the `doFirst` delete stops a stale file from a previous run leaking into the report), and both feed
  `jacocoTestReport` (`build/reports/jacoco/test/jacocoTestReport.xml`) and the 80% `jacocoTestCoverageVerification`,
  which `check` depends on. Configure it on `tasks.test` only — applying it to every `Test` task makes
  `quarkusIntTest` delete the Quarkus data file after the report was written.
- **detekt 1.23.x** is compiled against Kotlin 2.0; without the `resolutionStrategy` block the `detekt` task fails
  with "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.4.x". `getSupportedKotlinVersion()`
  is the plugin's own constant, so the pin moves with the plugin.
- **ktlint** — run `./gradlew ktlintFormat` once after writing the files (see `quality-config.md`); it reformats the
  generated `settings.gradle.kts` and this file to `ktlint_official`.
- **Dokka 2** (V2 mode is the default from 2.1) — `./gradlew dokkaGeneratePublicationHtml` → `build/dokka/html`.
- `mavenLocal()` stays only because `create` wrote it; drop it if the project doesn't need it.

## Java — the resulting `build.gradle.kts`

```kotlin
plugins {
    java
    id("io.quarkus")
    jacoco
    checkstyle
    pmd
    id("com.github.spotbugs") version "6.5.13"
    id("org.sonarqube") version "7.5.0.8588" // only when sonar != none
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    // … the extension dependencies `create` wrote, unchanged (quarkus-rest, quarkus-arc, …) …
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.quarkus:quarkus-junit-component")
    testImplementation("io.quarkus:quarkus-junit-mockito")
    testImplementation("io.quarkus:quarkus-jacoco")
    testImplementation("io.rest-assured:rest-assured")
}

java {
    sourceCompatibility = JavaVersion.VERSION_<java-version>
    targetCompatibility = JavaVersion.VERSION_<java-version>
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// --- Tests and coverage (iru-setup-quarkus-service) ----------------------------------------------------------------
// quarkus-jacoco records the classes Quarkus transforms (@QuarkusComponentTest, @QuarkusTest) into
// build/jacoco-quarkus.exec (quarkus.jacoco.data-file). Without a @QuarkusTest in the run its runtime falls back to
// the jacoco-agent.destfile system property (default ./jacoco.exec), so point that at the same file. The JaCoCo agent
// (plain tests) keeps build/jacoco/test.exec; the report and the 80% check read both.
val quarkusJacocoData = layout.buildDirectory.file("jacoco-quarkus.exec")

tasks.test {
    systemProperty("jacoco-agent.destfile", quarkusJacocoData.get().asFile.absolutePath)
    doFirst { delete(quarkusJacocoData) }
    extensions.configure<JacocoTaskExtension> {
        excludeClassLoaders = listOf("*QuarkusClassLoader")
    }
    // ci-tests: unit — CI passes -PexcludeTestsFile=<file> listing the @QuarkusTest classes (class-file patterns,
    // one per line) so only container-free plain and component tests run.
    providers.gradleProperty("excludeTestsFile").orNull?.let { path ->
        file(path).readLines().filter { it.isNotBlank() }.forEach { exclude(it.trim()) }
    }
    finalizedBy(tasks.jacocoTestReport)
}

val jacocoExecutionData =
    fileTree(layout.buildDirectory) { include("jacoco/test.exec", "jacoco-quarkus.exec") }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    executionData.setFrom(jacocoExecutionData)
    reports {
        xml.required = true
        csv.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    executionData.setFrom(jacocoExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// --- Static analysis and docs ----------------------------------------------------------------------------------------
checkstyle {
    toolVersion = "14.3.0"
    configFile = file("checkstyle.xml")
    isIgnoreFailures = true
}

pmd {
    toolVersion = "7.28.0"
    isConsoleOutput = true
    isIgnoreFailures = true
}

spotbugs {
    ignoreFailures = true
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("xml") { required = true }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all", "-quiet")
        addBooleanOption("Werror", true)
        memberLevel = JavadocMemberLevel.PRIVATE
    }
}

// Only when sonar != none — see "Sonar" below.
sonar {
    properties {
        property("sonar.organization", "<sonar-organization>")
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.host.url", "<sonar-host-url>")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.absolutePath,
        )
    }
}
```

Notes: Checkstyle/PMD/SpotBugs are set to report, not fail (`ignoreFailures`), like the Maven setup — the coding
group skills gate on "no new issues versus the baseline" via `iru-java-code-quality`, which runs
`checkstyleMain pmdMain spotbugsMain` and reads `build/reports/{checkstyle,pmd,spotbugs}/*.xml`. The Javadoc task
fails on any doclint warning (`-Xdoclint:all` + `-Werror`), matching Maven's `failOnWarnings`. PMD's default
`auxClasspath` warning in the log is harmless.

## Sonar (`sonar` ≠ `none`)

Add `id("org.sonarqube") version "<latest>"` (7.5.0.8588 at the time of writing) to `plugins {}` and:

```kotlin
sonar {
    properties {
        property("sonar.organization", "<sonar-organization>")   // omit for self-hosted without organizations
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.host.url", "<sonar-host-url>")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.absolutePath,
        )
        // Kotlin only:
        property("sonar.kotlin.detekt.reportPaths", layout.buildDirectory.file("reports/detekt/detekt.xml").get().asFile.absolutePath)
        property(
            "sonar.kotlin.ktlint.reportPaths",
            layout.buildDirectory.file("reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.xml").get().asFile.absolutePath,
        )
    }
}
```

CI runs `./gradlew sonar -Dsonar.token=…` after the build. No `sonar-project.properties` file.

## Container image (`distribution: registry`) and native

Add `implementation("io.quarkus:quarkus-container-image-jib")` (`imageBuilder: jib`); image coordinates are passed by
CI as `-Dquarkus.container-image.*` system properties to `./gradlew build`. Native: nothing to add —
`./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true` and `./gradlew testNative`
(source set `src/native-test`) come from the Quarkus plugin.

## Commands the rest of the pipeline uses

```bash
./gradlew check                       # unit + @QuarkusTest (Dev Services), coverage report + 80% verification, detekt/ktlint (Kotlin)
./gradlew quarkusIntTest              # @QuarkusIntegrationTest classes in src/integrationTest against the packaged jar (ci-tests: full)
# ci-tests: unit — no containers. Gradle's -D doesn't reach the test JVM, the environment does:
grep -rlE '^[[:space:]]*@Quarkus(Main)?Test([^A-Za-z0-9_]|$)' --include='*.java' --include='*.kt' . \
  | sed -E 's#^(.*/)?src/test/(java|kotlin)/##; s#\.(java|kt)$#.class#' > "$TMPDIR/quarkus-tests.excludes"
QUARKUS_DEVSERVICES_ENABLED=false ./gradlew check -PexcludeTestsFile="$TMPDIR/quarkus-tests.excludes"
./gradlew checkstyleMain pmdMain spotbugsMain javadoc     # Java quality + docs gate
./gradlew detekt ktlintCheck dokkaGeneratePublicationHtml # Kotlin quality + docs
```

Running Gradle without a local installation: the wrapper only needs a JDK. With neither a JDK ≥ 21 nor Gradle on
the machine, `docker run --rm -v "$PWD":/w -w /w -v /var/run/docker.sock:/var/run/docker.sock
-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal gradle:<tag>-jdk21 ./gradlew …` works, Dev Services included
(the socket mount and the host override are what let Testcontainers inside the container start sibling containers
on Docker Desktop; on a Linux host drop the override).
