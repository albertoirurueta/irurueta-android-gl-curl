import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Omit when `ui: views`:
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    // Omit the whole line when `sonar: none`:
    alias(libs.plugins.sonarqube)
}

// Read once, at configuration time — used both for signingConfigs below and Step 8's report.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")
val signingStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning: Boolean =
    !signingStoreFile.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

android {
    namespace = "<namespace>"
    compileSdk = <compile-sdk>

    defaultConfig {
        applicationId = "<application-id>"
        minSdk = <min-sdk>
        targetSdk = <target-sdk>
        versionCode = <version-code>
        versionName = "<version-name>"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildNumber = System.getenv("BUILD_NUMBER") ?: ""
        buildConfigField("String", "BUILD_NUMBER", "\"$buildNumber\"")
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"" + dateFormatter.format(Date()) + "\"")
        val gitCommit = System.getenv("GIT_COMMIT")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        val gitBranch = System.getenv("GIT_BRANCH")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")

        val apkPrefixLabels = listOf("<project-name>", versionName, buildNumber)
        base.archivesName = apkPrefixLabels.filter { it != "" }.joinToString("-")
    }

    // Never references signingConfigs["release"] when it wasn't created below — an app can always be configured
    // and `assembleDebug`'d even with zero signing secrets present (e.g. a fresh clone, or a contributor's laptop).
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // Only when `ui: compose`:
        compose = true
        // Only when `ui: views`:
        viewBinding = true
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

// Omit this entire block when `sonar: none`.
sonar {
    properties {
        property("sonar.scanner.skipJreProvisioning", true)
        property("sonar.projectKey", "<sonar-project-key>")
        property("sonar.projectName", "<project-name>")
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

        val androidJar = project.android.sdkDirectory.path + "/platforms/android-<compile-sdk>/android.jar"
        property("sonar.libraries", androidJar)
        property("sonar.java.libraries", androidJar)
        property("sonar.java.test.libraries", androidJar)
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
    // Only when `ui: compose`:
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.mockk.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.irurueta.test.utils)
    // UI tests (catalog UI contract): Roborazzi captures + Accessibility Test Framework checks on Robolectric.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.accessibility.check)
    // Only when `ui: compose`:
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Only when `ui: views`:
    testImplementation(libs.androidx.espresso.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.mockk.android)
    // Only when `ui: compose`:
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.ui.test.junit4.accessibility)
    // Only when `ui: views`:
    androidTestImplementation(libs.androidx.espresso.accessibility)
}
