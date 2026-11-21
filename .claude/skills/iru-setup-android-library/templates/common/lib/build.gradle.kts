import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.dokka)
    // Omit the next line entirely when sonar: none
    alias(libs.plugins.sonarqube)
    // Omit the next line entirely when publish: no
    alias(libs.plugins.publish)
    // Only when junit: 5
    alias(libs.plugins.mannodermaus.android.junit)
    // Only when coverage-tool: kover
    alias(libs.plugins.kover)
    // Only when static-analysis: lint+detekt+ktlint (both lines)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

val libraryVersion = "<version>"

android {
    namespace = "<namespace>"
    compileSdk = <compile-sdk>

    defaultConfig {
        minSdk = <min-sdk>
        testOptions.targetSdk = <compile-sdk>

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildNumber = System.getenv("BUILD_NUMBER") ?: ""
        val apkPrefixLabels = listOf("<artifact-id>", libraryVersion, buildNumber)
        base.archivesName = apkPrefixLabels.filter { it != "" }.joinToString("-")
    }

    buildTypes {
        debug {
            // Omit these two lines entirely when coverage-tool: kover — Kover replaces AGP's own
            // unit/instrumented-test coverage wiring with its own report tasks (see below)
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes.add("META-INF/LICENSE-notice.md")
            excludes.add("META-INF/LICENSE.md")
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Omit this whole `sonar { ... }` block entirely when sonar: none
sonar {
    properties {
        property("sonar.scanner.skipJreProvisioning", true)
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.projectName", "<artifact-id>")
        property("sonar.organization", "<sonar-organization>")
        property("sonar.host.url", "<sonar-host-url>")

        property("sonar.tests", listOf("src/test/java", "src/androidTest/java"))
        property("sonar.test.inclusions",
            listOf("**/*Test*/**", "src/androidTest/**", "src/test/**"))
        property("sonar.test.exclusions",
            listOf("**/*Test*/**", "src/androidTest/**", "src/test/**"))
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.sources", "src/main/java")
        property("sonar.exclusions", "**/*Test*/**,*.json,'**/*test*/**,**/.gradle/**,**/R.class")

        val libraries = project.android.sdkDirectory.path + "/platforms/android-<compile-sdk>/android.jar"
        property("sonar.libraries", libraries)
        property("sonar.java.libraries", libraries)
        property("sonar.java.test.libraries", libraries)
        property("sonar.binaries", "build/intermediates/javac/debug/classes,build/tmp/kotlin-classes/debug")
        property("sonar.java.binaries", "build/intermediates/javac/debug/classes,build/tmp/kotlin-classes/debug")

        // Departure from the reference: AGP writes the unit-test XML under test/debug/, and the key names below
        // (reportPaths / androidLint.reportPaths) are the ones the Sonar analyzer actually reads
        property("sonar.coverage.jacoco.xmlReportPaths",
            listOf("build/reports/coverage/androidTest/debug/connected/report.xml",
                "build/reports/coverage/test/debug/report.xml"))
        property("sonar.java.coveragePlugin", "jacoco")
        property("sonar.junit.reportPaths",
            listOf("build/test-results/testDebugUnitTest",
                "build/outputs/androidTest-results/connected/debug"))
        property("sonar.androidLint.reportPaths", "build/reports/lint-results-debug.xml")
    }
}

dependencies {
    implementation(libs.material)
    testImplementation(libs.junit)
    // Only when junit: 5, in addition to the JUnit 4 line above (androidTest keeps running on JUnit 4)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.irurueta.test.utils)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.mockk.android)
}

// Only when coverage-tool: kover — unverified locally, review the applied Kover version's own
// documentation for its current report-task names/DSL before relying on this block as-is
kover {
    reports {
        total {
            xml {
                onCheck = false
            }
        }
    }
}

// Only when static-analysis: lint+detekt+ktlint — unverified locally
detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("<ktlint-version>")
        // Omit this licenseHeader(...) call if "No license" was chosen in Step 2
        licenseHeader(
            """
            |/*
            | * Copyright (C) ${"$"}YEAR <developer-name>
            | *
            | * Licensed under the <license-name> (the "License");
            | * you may not use this file except in compliance with the License.
            | * You may obtain a copy of the License at
            | *
            | *         <license-url>
            | *
            | * Unless required by applicable law or agreed to in writing, software
            | * distributed under the License is distributed on an "AS IS" BASIS,
            | * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            | * See the License for the specific language governing permissions and
            | * limitations under the License.
            | */
            |
            """.trimMargin()
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("<ktlint-version>")
    }
}

// Omit this whole `mavenPublishing { ... }` block entirely when publish: no
mavenPublishing {
    configure(AndroidSingleVariantLibrary(
        // the published variant
        variant = "release",
        // whether to publish a sources jar
        sourcesJar = true,
        // whether to publish a javadoc jar
        publishJavadocJar = true,
    ))

    publishToMavenCentral()
    signAllPublications()

    coordinates("<group-id>", "<artifact-id>", libraryVersion)

    pom {
        name.set("<artifact-id>")
        description.set("<description>")
        inceptionYear.set("<inception-year>")
        url.set("<repository-url>")
        // Omit this whole `licenses {...}` block if "No license" was chosen in Step 2
        licenses {
            license {
                name.set("<license-name>")
                url.set("<repository-url>/blob/<stable-branch>/LICENSE")
                distribution.set("<repository-url>/blob/<stable-branch>/LICENSE")
            }
        }
        developers {
            developer {
                id.set("<owner>")
                name.set("<developer-name>")
                email.set("<developer-email>")
                url.set("<organization-url>")
            }
        }
        scm {
            url.set("<repository-url>")
            connection.set("scm:git:<host>/<owner>/<repo>.git")
            developerConnection.set("scm:git:ssh://<host>/<owner>/<repo>.git")
        }
    }
}
