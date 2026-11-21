# Gradle build templates

Root build files of the KMP scaffold. `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are fetched, not templated (SKILL.md Step 6).

Conditional markers (`// >>> ui: compose` … `// <<< ui: compose`, `# >>> sonar` …) and `__pkg__` work as described in SKILL.md's "Reference files" section; every file below was rendered and built for both `ui` values on 2026-10-15.

### `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-<gradle-version>-bin.zip
distributionSha256Sum=<gradle-distribution-sha256>
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### `settings.gradle.kts`

```kotlin
rootProject.name = "<project-name>"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
```

### `gradle.properties`

```properties
# Gradle
org.gradle.jvmargs=-Xmx4096M -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true

# Kotlin
kotlin.code.style=official
kotlin.daemon.jvmargs=-Xmx3072M

# Android
android.useAndroidX=true
android.nonTransitiveRClass=true

# App version — the single source of truth for the Android app (versionName/versionCode read these) and the value
# iru-kmp-bump-version rewrites. VERSION_NAME carries -SNAPSHOT on the integration branch; VERSION_CODE is a plain
# integer bumped only on a release. The iOS app's MARKETING_VERSION/CURRENT_PROJECT_VERSION live in
# iosApp/Configuration/Config.xcconfig and mirror these on every release.
VERSION_NAME=<version-name>
VERSION_CODE=<version-code>
```

`VERSION_NAME`/`VERSION_CODE` are the single version source for Android; `iru-kmp-bump-version` rewrites them (and mirrors release values into `Config.xcconfig`).

### `gradle/libs.versions.toml`

```toml
[versions]
agp = "<agp-version>"
kotlin = "<kotlin-version>"
# >>> ui: compose
composeMultiplatform = "<compose-multiplatform-version>"
composeMaterial3 = "<compose-material3-version>"
# The AndroidX Compose UI line Compose Multiplatform maps to (for ui-test-manifest in the Robolectric UI tests).
androidx-composeUi = "<androidx-compose-ui-version>"
# <<< ui: compose
# >>> ui: native
androidx-composeBom = "<compose-bom-version>"
# <<< ui: native
androidx-activity = "<activity-compose-version>"
androidx-testCore = "<androidx-test-core-version>"
junit = "<junit-version>"
robolectric = "<robolectric-version>"
detekt = "<detekt-version>"
ktlintGradle = "<ktlint-gradle-version>"
kover = "<kover-version>"
dokka = "<dokka-version>"
# >>> sonar
sonarqube = "<sonarqube-plugin-version>"
# <<< sonar
android-compileSdk = "<compile-sdk>"
android-targetSdk = "<target-sdk>"
android-minSdk = "<min-sdk>"

[libraries]
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlin-testJunit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core-ktx", version.ref = "androidx-testCore" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity" }
# >>> ui: compose
compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "composeMultiplatform" }
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "composeMultiplatform" }
compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "composeMultiplatform" }
compose-uiToolingPreview = { module = "org.jetbrains.compose.ui:ui-tooling-preview", version.ref = "composeMultiplatform" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "composeMaterial3" }
compose-uiTest = { module = "org.jetbrains.compose.ui:ui-test", version.ref = "composeMultiplatform" }
androidx-compose-uiTestManifest = { module = "androidx.compose.ui:ui-test-manifest", version.ref = "androidx-composeUi" }
# <<< ui: compose
# >>> ui: native
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "androidx-composeBom" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-uiToolingPreview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-uiTooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-uiTestJunit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-uiTestManifest = { module = "androidx.compose.ui:ui-test-manifest" }
# <<< ui: native

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-kotlinMultiplatformLibrary = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
android-lint = { id = "com.android.lint", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
# >>> ui: compose
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
# <<< ui: compose
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlintGradle" }
kover = { id = "org.jetbrains.kotlinx.kover", version.ref = "kover" }
dokka = { id = "org.jetbrains.dokka", version.ref = "dokka" }
# >>> sonar
sonarqube = { id = "org.sonarqube", version.ref = "sonarqube" }
# <<< sonar
```

### `build.gradle.kts`

```kotlin
plugins {
    // Declared once here (apply false) so every module resolves the same plugin versions from one classloader.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlinMultiplatformLibrary) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // >>> ui: compose
    alias(libs.plugins.compose.multiplatform) apply false
    // <<< ui: compose
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dokka) apply false
    // Applied (not `apply false`) at the root: the root project aggregates every module's Kover data.
    alias(libs.plugins.kover)
    // >>> sonar
    alias(libs.plugins.sonarqube)
    // <<< sonar
}

dependencies {
    // Aggregated coverage: `./gradlew koverXmlReport` -> build/reports/kover/report.xml (JaCoCo-format XML).
    kover(projects.shared)
    kover(projects.androidApp)
}

kover {
    reports {
        filters {
            excludes {
                // UI glue is exercised by previews / UI tests, not by host unit tests — keep it out of the 80% bar.
                annotatedBy("androidx.compose.runtime.Composable")
                classes("*ComposableSingletons*", "*.BuildConfig")
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
// >>> sonar

sonar {
    properties {
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.projectName", "<project-name>")
        property("sonar.organization", "<sonar-organization>")
        property("sonar.host.url", "<sonar-host-url>")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.scanner.skipJreProvisioning", true)
        // Kover's aggregated XML is JaCoCo-format — the Sonar Kotlin/Java analyzers import it through this key.
        property("sonar.coverage.jacoco.xmlReportPaths", "${rootDir}/build/reports/kover/report.xml")
        property("sonar.coverage.exclusions", "**/*Preview*.kt,**/MainViewController.kt,iosApp/**")
    }
}

project(":shared") {
    sonar {
        properties {
            property("sonar.sources", "src/commonMain/kotlin,src/androidMain/kotlin,src/iosMain/kotlin")
            property("sonar.tests", "src/commonTest/kotlin,src/androidHostTest/kotlin,src/iosTest/kotlin")
            property("sonar.junit.reportPaths", "build/test-results/testAndroidHostTest")
            property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
        }
    }
}

project(":androidApp") {
    sonar {
        properties {
            property("sonar.sources", "src/main/kotlin")
            property("sonar.tests", "src/test/kotlin")
            property("sonar.junit.reportPaths", "build/test-results/testDebugUnitTest")
            property("sonar.androidLint.reportPaths", "build/reports/lint-results-debug.xml")
            property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
        }
    }
}
// <<< sonar
```

The root project applies Kover itself and aggregates both modules; `koverVerify` enforces the 80% line bound the catalog's gates use. The `sonar {}` block configures each module's sources/tests and imports Kover's JaCoCo-format XML via `sonar.coverage.jacoco.xmlReportPaths`.

### `detekt.yml`

```yaml
# Layered on top of detekt's default config (buildUponDefaultConfig = true) — only deviations live here.
build:
  maxIssues: 0

naming:
  FunctionNaming:
    # Composable functions are PascalCase by convention.
    ignoreAnnotated:
      - Composable

style:
  MagicNumber:
    ignoreAnnotated:
      - Composable
      - Preview
    excludes:
      - '**/commonTest/**'
      - '**/androidHostTest/**'
      - '**/iosTest/**'
      - '**/test/**'
  UnusedPrivateMember:
    ignoreAnnotated:
      - Preview

complexity:
  LongParameterList:
    ignoreAnnotated:
      - Composable
```

### `.editorconfig`

```editorconfig
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.{kt,kts}]
indent_size = 4
indent_style = space
max_line_length = 120
ktlint_code_style = ktlint_official
# Composable functions are PascalCase by convention.
ktlint_function_naming_ignore_when_annotated_with = Composable

[*.swift]
indent_size = 4
indent_style = space

[*.gradle.kts]
# Version-catalog accessors (`libs.versions.android.compileSdk.get().toInt()`) are idiomatic in build scripts.
ktlint_standard_chain-method-continuation = disabled
```

### `.ui-quality.yml`

Read by the UI test helpers (`shared/src/commonTest/…/uitest/UiScreenshots.kt`, `androidApp/src/test/…/uitest/UiScreenshots.kt`,
`iosApp/iosAppTests/UIScreenshots.swift`) and by the KMP coding/test skills. `<accessibility>` is Step 0's
`accessibility` (default `enforce`).

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: <accessibility>      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```
