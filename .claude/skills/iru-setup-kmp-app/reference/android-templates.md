# `androidApp/` module templates

The Android application (AGP 9 built-in Kotlin — no `org.jetbrains.kotlin.android` plugin).

Conditional markers (`// >>> ui: compose` … `// <<< ui: compose`, `# >>> sonar` …) and `__pkg__` work as described in SKILL.md's "Reference files" section; every file below was rendered and built for both `ui` values on 2026-10-15.

### `androidApp/build.gradle.kts`

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin) — no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// Read once at configuration time. The release signing config only exists when all four are set, so
// `assembleDebug`/`assembleRelease` never fail on a machine without signing secrets (release is left unsigned).
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
    namespace = "<namespace>.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "<application-id>"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Single source of truth: root gradle.properties (iru-kmp-bump-version rewrites it).
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

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
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest.
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Reports (lint-results-debug.xml/.sarif/.html) are always written by AGP 9. New-version-available warnings are Dependabot's job, not a build failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

detekt {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

// Same exclusions as the root aggregate, so this module's own report (`koverXmlReport`) agrees with it.
kover {
    reports {
        filters {
            excludes {
                annotatedBy("androidx.compose.runtime.Composable")
                classes("*ComposableSingletons*", "*.BuildConfig")
            }
        }
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    // >>> ui: native
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.uiToolingPreview)
    debugImplementation(libs.androidx.compose.uiTooling)
    debugImplementation(libs.androidx.compose.uiTestManifest)
    // <<< ui: native

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // >>> ui: native
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.uiTestJunit4)
    // <<< ui: native
}
// >>> ui: native

// UI-test screenshots (.ui-screenshots/<kind>/<Id>/<state>--<dir>--<theme>--android.png) and .ui-quality.yml.
val uiScreenshotsDir: String =
    providers.environmentVariable("UI_SCREENSHOTS_DIR").getOrElse(rootDir.resolve(".ui-screenshots").absolutePath)
tasks.withType<Test>().configureEach {
    environment("UI_SCREENSHOTS_DIR", uiScreenshotsDir)
    environment("UI_REPO_ROOT", rootDir.absolutePath)
}
// <<< ui: native
```

### `androidApp/proguard-rules.pro`

```proguard
# Project-specific ProGuard/R8 rules. Release builds ship with isMinifyEnabled = false until rules are written here.
```

### `androidApp/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

### `androidApp/src/main/kotlin/__pkg__/android/MainActivity.kt` (ui: compose)

```kotlin
package <namespace>.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import <namespace>.App

/**
 * Hosts the shared Compose Multiplatform [App].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
```

### `androidApp/src/main/kotlin/__pkg__/android/MainActivity.kt` (ui: native)

```kotlin
package <namespace>.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import <namespace>.GreetingUiState

/**
 * Native Android UI (Jetpack Compose) over the shared [GreetingUiState].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var state by remember { mutableStateOf(GreetingUiState.initial()) }
                GreetingScreen(state = state, onClick = { state = state.clicked() })
            }
        }
    }
}

/**
 * The app's Material 3 theme, light or dark (the UI tests pass [darkTheme] explicitly).
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
}

/**
 * Stateless greeting screen.
 *
 * Inputs: [state]. Events: [onClick] when the counter button is tapped. Visual states: `default`, `long-content`.
 */
@Composable
fun GreetingScreen(
    state: GreetingUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = state.message, style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onClick) {
                Text(text = state.buttonLabel)
            }
        }
    }
}

@Preview
@Composable
private fun GreetingScreenPreview() {
    AppTheme(darkTheme = false) {
        GreetingScreen(state = GreetingUiState(message = "Hello, Preview!"), onClick = {})
    }
}
```

### `androidApp/src/main/res/drawable/ic_launcher_foreground.xml`

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="@color/ic_launcher_foreground"
        android:pathData="M44,34h20v10h-20z M44,49h20v10h-20z M44,64h20v10h-20z" />
</vector>
```

### `androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

### `androidApp/src/main/res/values/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FF6750A4</color>
    <color name="ic_launcher_foreground">#FFFFFFFF</color>
</resources>
```

### `androidApp/src/main/res/values/strings.xml`

```xml
<resources>
    <string name="app_name"><app-name></string>
</resources>
```

### `androidApp/src/test/kotlin/__pkg__/android/GreetingScreenTest.kt` (ui: native)

```kotlin
package <namespace>.android

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import <namespace>.GreetingUiState
import <namespace>.android.uitest.UiKind
import <namespace>.android.uitest.UiQuality
import <namespace>.android.uitest.captureUiScreenshot
import <namespace>.android.uitest.checkUiAccessibility
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class GreetingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingTheButtonEmitsOnClick() {
        var clicks = 0
        compose.setContent { AppTheme(darkTheme = false) { GreetingScreen(state = DEFAULT, onClick = { clicks++ }) } }

        compose.onNodeWithText("Tap me").performClick()

        assertEquals(1, clicks)
        compose.checkUiAccessibility()
    }

    @Test
    fun everyStateIsCapturedInEveryDirectionAndTheme() {
        var cell by mutableStateOf(DEFAULT to UiQuality.matrix.first())
        compose.setContent {
            val (state, variant) = cell
            CompositionLocalProvider(LocalLayoutDirection provides variant.direction) {
                AppTheme(darkTheme = variant.darkTheme) {
                    GreetingScreen(state = state, onClick = {}, modifier = Modifier.size(360.dp, 640.dp))
                }
            }
        }
        STATES.forEach { (name, state) ->
            UiQuality.matrix.forEach { variant ->
                cell = state to variant
                compose.waitForIdle()
                compose.onRoot().captureUiScreenshot(UiKind.SCREEN, "GreetingScreen", name, variant)
            }
        }
    }

    private companion object {
        val DEFAULT = GreetingUiState(message = "Hello, Test!")
        val STATES =
            mapOf(
                "default" to DEFAULT,
                "long-content" to
                    GreetingUiState(message = "Hello, " + "a very long platform name ".repeat(6), clicks = 12),
                "rtl-content" to GreetingUiState(message = "مرحبا بالعالم"),
            )
    }
}
```

### `androidApp/src/test/kotlin/__pkg__/android/MainActivityTest.kt`

```kotlin
package <namespace>.android

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test
    fun activityStartsAndRendersContent() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()

            assertFalse(activity.isFinishing)
        }
    }
}
```

### `androidApp/src/test/kotlin/__pkg__/android/uitest/UiScreenshots.kt` (ui: native)

The UI contract's screenshot + accessibility helper for the native Android UI (JVM, Robolectric).

```kotlin
package <namespace>.android.uitest

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File

/** Screenshot kinds of the UI contract (`.ui-screenshots/<kind>/…`). */
enum class UiKind(
    internal val dir: String,
) {
    COMPONENT("component"),
    SCREEN("screen"),
    FLOW("flow"),
}

/** One cell of the direction × theme screenshot matrix. */
data class UiVariant(
    val direction: LayoutDirection,
    val darkTheme: Boolean,
) {
    internal val segments: String
        get() = (if (direction == LayoutDirection.Rtl) "rtl" else "ltr") + "--" + (if (darkTheme) "dark" else "light")
}

/** `.ui-quality.yml` at the repository root; a missing file or key means the contract's defaults. */
object UiQuality {
    private val lines: List<String> by lazy {
        File(uiRepoRoot(), ".ui-quality.yml")
            .takeIf { it.isFile }
            ?.readLines()
            .orEmpty()
            .map { it.substringBefore('#').trim() }
    }

    /** `enforce`, `report` or `off`. */
    val accessibility: String
        get() = value("accessibility") ?: "enforce"

    /** Every direction × theme cell the repository asks for (`high-contrast` is not rendered by these tests). */
    val matrix: List<UiVariant>
        get() {
            val directions = list("directions") ?: listOf("ltr", "rtl")
            val themes = (list("themes") ?: listOf("light", "dark")).filter { it == "light" || it == "dark" }
            return directions.flatMap { direction ->
                themes.map { theme ->
                    UiVariant(if (direction == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr, theme == "dark")
                }
            }
        }

    private fun value(key: String): String? =
        lines
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.ifEmpty { null }

    private fun list(key: String): List<String>? =
        value(key)
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
}

/**
 * Captures this node as
 * `<UI_SCREENSHOTS_DIR | <repo>/.ui-screenshots>/<kind>/<id>/<state>--<dir>--<theme>--android[--<extra>…].png`
 * (`android` because the same screen also renders natively on iOS).
 */
fun SemanticsNodeInteraction.captureUiScreenshot(
    kind: UiKind,
    id: String,
    state: String,
    variant: UiVariant,
    vararg extra: String,
) {
    val name = (listOf(state, variant.segments, "android") + extra).joinToString("--")
    val file = File(uiScreenshotsDir(), "${kind.dir}/$id/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** `UI_SCREENSHOTS_DIR`, else `<repository root>/.ui-screenshots`. */
internal fun uiScreenshotsDir(): File =
    System.getenv("UI_SCREENSHOTS_DIR")?.let(::File) ?: File(uiRepoRoot(), ".ui-screenshots")

/** The repository root: `UI_REPO_ROOT` (set by androidApp/build.gradle.kts), else the nearest settings.gradle.kts. */
internal fun uiRepoRoot(): File =
    System.getenv("UI_REPO_ROOT")?.let(::File)
        ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

/** One accessibility finding: a stable rule id and the node it was found on. */
data class UiA11yIssue(
    val rule: String,
    val node: String,
)

private val MIN_TOUCH_TARGET = 48.dp

/**
 * Semantics checks on the JVM: each clickable node has an accessible name and a touch target (its touch bounds,
 * which include Material's minimum interactive size) of at least 48dp. Applies `.ui-quality.yml`'s `accessibility`
 * level — `enforce` fails the test, `report` prints a summary, `off` and `none` skip. (ATF checks through
 * `enableAccessibilityChecks()` only report reliably on a device or emulator, not under Robolectric.)
 */
fun ComposeContentTestRule.checkUiAccessibility(): List<UiA11yIssue> {
    val level = UiQuality.accessibility
    if (level == "off" || level == "none") return emptyList()
    val minPx = with(density) { MIN_TOUCH_TARGET.toPx() }
    val issues =
        onAllNodes(hasClickAction()).fetchSemanticsNodes().flatMap { node ->
            listOfNotNull(
                UiA11yIssue("missing-label", node.describe()).takeUnless { node.hasLabel() },
                UiA11yIssue("touch-target-too-small", node.describe())
                    .takeIf { node.touchBoundsInRoot.width < minPx || node.touchBoundsInRoot.height < minPx },
            )
        }
    if (issues.isNotEmpty()) {
        val summary = issues.groupingBy { it.rule }.eachCount()
        check(level != "enforce") { "Accessibility violations $summary: $issues" }
        println("UI accessibility (report): $summary")
    }
    return issues
}

private fun SemanticsNode.hasLabel(): Boolean =
    !config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
        !config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
        config.getOrNull(SemanticsActions.OnClick)?.label != null

private fun SemanticsNode.describe(): String =
    "node#$id ${config.getOrNull(SemanticsProperties.TestTag).orEmpty()}".trim()
```

### `androidApp/src/test/resources/robolectric.properties`

```properties
sdk=<robolectric-sdk>
```
