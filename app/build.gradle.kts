import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    alias(libs.plugins.sonarqube)
}

android {
    namespace = "com.irurueta.android.gl.curl.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.irurueta.android.gl.curl.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildNumber = System.getenv("BUILD_NUMBER")
        buildConfigField("String", "BUILD_NUMBER", "\"$buildNumber\"")
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"" + dateFormatter.format(Date()) + "\"")
        val gitCommit = System.getenv("GIT_COMMIT")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")

        val gitBranch = System.getenv("GIT_BRANCH")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")

        val apkPrefixLabels = listOf("glutils", versionName, buildNumber)
        base.archivesName = apkPrefixLabels.filter({ it != "" }) .joinToString("-")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

sonar {
    properties {
        property("sonar.scanner.skipJreProvisioning", true)
        property("sonar.projectKey", "albertoirurueta_irurueta-android-gl-curl")
        property("sonar.projectName", "irurueta-android-gl-curl-${project.name}")
        property("sonar.organization", "albertoirurueta-github")
        property("sonar.host.url", "https://sonarcloud.io")

        property("sonar.tests", listOf("src/test/java", "src/androidTest/java"))
        property("sonar.test.inclusions",
            listOf("**/*Test*/**", "src/androidTest/**", "src/test/**"))
        property("sonar.test.exclusions",
            listOf("**/*Test*/**", "src/androidTest/**", "src/test/**"))
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.sources", "src/main/java")
        property("sonar.exclusions", "**/*Test*/**,*.json,'**/*test*/**,**/.gradle/**,**/R.class")

        val libraries = project.android.sdkDirectory.path + "/platforms/android-36/android.jar"
        property("sonar.libraries", libraries)
        property("sonar.java.libraries", libraries)
        property("sonar.java.test.libraries", libraries)
        property("sonar.binaries", "build/intermediates/javac/debug/classes,build/tmp/kotlin-classes/debug")
        property("sonar.java.binaries", "build/intermediates/javac/debug/classes,build/tmp/kotlin-classes/debug")

        property("sonar.coverage.jacoco.xmlReportPaths",
            listOf("build/reports/coverage/androidTest/debug/connected/report.xml",
                "build/reports/coverage/test/report.xml"))
        property("sonar.java.coveragePlugin", "jacoco")
        property("sonar.junit.reportsPath",
            listOf("build/build/test-results/testDebugUnitTest",
                "build/build/outputs/androidTest-results/connected/debug"))
        property("sonar.android.lint.report", "build/build/reports/lint-results-debug.xml")
    }
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}