import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    // Omit the next line entirely when sonar: none
    alias(libs.plugins.sonarqube)
}

android {
    namespace = "<namespace>.app"
    compileSdk = <compile-sdk>

    defaultConfig {
        applicationId = "<namespace>.app"
        minSdk = <min-sdk>
        targetSdk = <compile-sdk>
        versionCode = 1
        versionName = "<version>"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildNumber = System.getenv("BUILD_NUMBER") ?: ""
        buildConfigField("String", "BUILD_NUMBER", "\"$buildNumber\"")
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"" + dateFormatter.format(Date()) + "\"")
        val gitCommit = System.getenv("GIT_COMMIT")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        val gitBranch = System.getenv("GIT_BRANCH")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")

        val apkPrefixLabels = listOf("<artifact-id>", versionName, buildNumber)
        base.archivesName = apkPrefixLabels.filter { it != "" }.joinToString("-")
    }

    buildTypes {
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
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric UI tests need the merged resources (strings, themes) and render with them.
            isIncludeAndroidResources = true
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Omit this whole `sonar { ... }` block entirely when sonar: none — same shape as lib/build.gradle.kts's block
// (Step 6), except `sonar.projectName` is suffixed with the Gradle project name so the two modules are
// distinguishable in the same SonarCloud/SonarQube project
sonar {
    properties {
        property("sonar.scanner.skipJreProvisioning", true)
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.projectName", "<artifact-id>-${project.name}")
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
    implementation(project(":lib"))
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // UI tests (catalog UI contract): Compose rule + Roborazzi captures + ATF checks on Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.accessibility.check)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.ui.test.junit4.accessibility)
}
