# `shared/` module templates

The KMP module: Android target via `com.android.kotlin.multiplatform.library` + `com.android.lint`, `iosArm64`/`iosSimulatorArm64` static framework `Shared`, sample code and a test in every source set.

Conditional markers (`// >>> ui: compose` … `// <<< ui: compose`, `# >>> sonar` …) and `__pkg__` work as described in SKILL.md's "Reference files" section; every file below was rendered and built for both `ui` values on 2026-10-15.

### `shared/build.gradle.kts`

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// >>> ui: compose
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
// <<< ui: compose

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlinMultiplatformLibrary)
    // The KMP library plugin registers no lint task on its own — this adds :shared:lint.
    alias(libs.plugins.android.lint)
    // >>> ui: compose
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // <<< ui: compose
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

kotlin {
    // >>> ui: compose
    compilerOptions {
        // commonTest's `expect abstract class UiTest` (Robolectric runner on Android, nothing on iOS).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // <<< ui: compose
    android {
        namespace = "<namespace>.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // JVM host tests for androidMain + commonMain (task: testAndroidHostTest, source set: androidHostTest).
        // >>> ui: native
        withHostTest {}
        // <<< ui: native
        // >>> ui: compose
        withHostTest {
            // Robolectric renders the Compose UI tests from commonTest on the JVM.
            isIncludeAndroidResources = true
        }
        // <<< ui: compose

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // >>> ui: compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.uiToolingPreview)
            // <<< ui: compose
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // >>> ui: compose
            implementation(libs.compose.uiTest)
            // <<< ui: compose
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.junit)
            // >>> ui: compose
            implementation(libs.robolectric)
            implementation(libs.androidx.compose.uiTestManifest)
            // <<< ui: compose
        }
    }
}

detekt {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
    source.setFrom(
        "src/commonMain/kotlin",
        "src/androidMain/kotlin",
        "src/iosMain/kotlin",
        "src/commonTest/kotlin",
        "src/androidHostTest/kotlin",
        "src/iosTest/kotlin",
    )
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

dokka {
    moduleName.set("shared")
    moduleVersion.set(providers.gradleProperty("VERSION_NAME"))
}
// >>> ui: compose

// UI-test screenshots (.ui-screenshots/<kind>/<Id>/<state>--<dir>--<theme>--<platform>.png) and .ui-quality.yml:
// the tests read UI_SCREENSHOTS_DIR / UI_REPO_ROOT; the iOS simulator only receives SIMCTL_CHILD_-prefixed variables.
val uiScreenshotsDir: String =
    providers.environmentVariable("UI_SCREENSHOTS_DIR").getOrElse(rootDir.resolve(".ui-screenshots").absolutePath)
tasks.withType<Test>().configureEach {
    environment("UI_SCREENSHOTS_DIR", uiScreenshotsDir)
    environment("UI_REPO_ROOT", rootDir.absolutePath)
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_UI_SCREENSHOTS_DIR", uiScreenshotsDir)
    environment("SIMCTL_CHILD_UI_REPO_ROOT", rootDir.absolutePath)
}
// <<< ui: compose
```

### `shared/src/androidHostTest/kotlin/__pkg__/AndroidPlatformTest.kt`

```kotlin
package <namespace>

import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlatformTest {
    @Test
    fun nameStartsWithAndroid() {
        assertTrue(getPlatform().name.startsWith("Android "))
    }
}
```

### `shared/src/androidHostTest/kotlin/__pkg__/uitest/UiTest.android.kt` (ui: compose)

```kotlin
package <namespace>.uitest

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Robolectric with native graphics, so `captureToImage()` renders real pixels; fixed SDK and screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [<robolectric-sdk>], qualifiers = "w360dp-h640dp-xhdpi")
actual abstract class UiTest actual constructor()

internal actual val uiPlatform: String = "android"

internal actual fun uiEnv(name: String): String? = System.getenv(name)

internal actual fun uiReadText(path: String): String? = File(path).takeIf { it.isFile }?.readText()

internal actual fun uiWritePng(
    path: String,
    image: ImageBitmap,
) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}
```

### `shared/src/androidMain/kotlin/__pkg__/AndroidPlatform.kt`

```kotlin
package <namespace>

import android.os.Build

/**
 * Android [Platform], named after the device's API level.
 */
class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()
```

### `shared/src/commonMain/kotlin/__pkg__/App.kt` (ui: compose)

```kotlin
package <namespace>

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

/**
 * Root composable shared by the Android and iOS apps.
 */
@Composable
fun App() {
    AppTheme {
        var state by remember { mutableStateOf(GreetingUiState.initial()) }
        GreetingScreen(state = state, onClick = { state = state.clicked() })
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

### `shared/src/commonMain/kotlin/__pkg__/Greeting.kt`

```kotlin
package <namespace>

/**
 * Builds the greeting shown on the app's first screen.
 *
 * Overloads instead of default arguments: Kotlin default values are not visible from Swift.
 *
 * @property platform the platform to greet.
 */
class Greeting(
    private val platform: Platform,
) {
    /** Greets the platform the code is currently running on. */
    constructor() : this(getPlatform())

    /**
     * Returns a greeting naming the platform.
     */
    fun greet(): String = "Hello, ${platform.name}!"
}
```

### `shared/src/commonMain/kotlin/__pkg__/GreetingUiState.kt`

```kotlin
package <namespace>

/**
 * Immutable UI state for the greeting screen, shared by the Android and iOS UIs.
 *
 * @property message the greeting to display.
 * @property clicks how many times the user tapped the button.
 */
data class GreetingUiState(
    val message: String,
    val clicks: Int = 0,
) {
    /** Label for the counter button. */
    val buttonLabel: String
        get() =
            when (clicks) {
                0 -> "Tap me"
                1 -> "Tapped 1 time"
                else -> "Tapped $clicks times"
            }

    /**
     * Returns the state after one more tap.
     */
    fun clicked(): GreetingUiState = copy(clicks = clicks + 1)

    /** Factories for the initial state (overloads, so Swift sees both). */
    companion object {
        /**
         * Creates the initial state greeting the current platform.
         */
        fun initial(): GreetingUiState = initial(Greeting())

        /**
         * Creates the initial state from [greeting].
         */
        fun initial(greeting: Greeting): GreetingUiState = GreetingUiState(message = greeting.greet())
    }
}
```

### `shared/src/commonMain/kotlin/__pkg__/Platform.kt`

```kotlin
package <namespace>

/**
 * The platform the shared code is currently running on.
 */
interface Platform {
    /** Human-readable platform name, e.g. `Android 36` or `iOS 26.4`. */
    val name: String
}

/**
 * Returns the [Platform] implementation for the current target.
 */
expect fun getPlatform(): Platform
```

### `shared/src/commonTest/kotlin/__pkg__/GreetingScreenTest.kt` (ui: compose)

```kotlin
package <namespace>

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import <namespace>.uitest.UiKind
import <namespace>.uitest.UiQuality
import <namespace>.uitest.UiTest
import <namespace>.uitest.captureUiScreenshot
import <namespace>.uitest.checkUiAccessibility
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GreetingScreenTest : UiTest() {
    @Test
    fun tappingTheButtonEmitsOnClick() =
        runComposeUiTest {
            var clicks = 0
            setContent { AppTheme(darkTheme = false) { GreetingScreen(state = DEFAULT, onClick = { clicks++ }) } }

            onNodeWithText("Tap me").performClick()

            assertEquals(1, clicks)
            checkUiAccessibility()
        }

    @Test
    fun everyStateIsCapturedInEveryDirectionAndTheme() =
        runComposeUiTest {
            var cell by mutableStateOf(DEFAULT to UiQuality.matrix.first())
            setContent {
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
                    waitForIdle()
                    onRoot().captureUiScreenshot(UiKind.SCREEN, "GreetingScreen", name, variant)
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

### `shared/src/commonTest/kotlin/__pkg__/GreetingTest.kt`

```kotlin
package <namespace>

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GreetingTest {
    private val fakePlatform =
        object : Platform {
            override val name: String = "Test"
        }

    @Test
    fun greetNamesThePlatform() {
        assertEquals("Hello, Test!", Greeting(fakePlatform).greet())
    }

    @Test
    fun defaultGreetingUsesTheCurrentPlatform() {
        assertTrue(Greeting().greet().startsWith("Hello, "))
    }
}
```

### `shared/src/commonTest/kotlin/__pkg__/GreetingUiStateTest.kt`

```kotlin
package <namespace>

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingUiStateTest {
    private val greeting =
        Greeting(
            object : Platform {
                override val name: String = "Test"
            },
        )

    @Test
    fun initialStateHasNoClicks() {
        val state = GreetingUiState.initial(greeting)

        assertEquals("Hello, Test!", state.message)
        assertEquals(0, state.clicks)
        assertEquals("Tap me", state.buttonLabel)
    }

    @Test
    fun clickedIncrementsTheCounter() {
        val once = GreetingUiState.initial(greeting).clicked()
        val twice = once.clicked()

        assertEquals("Tapped 1 time", once.buttonLabel)
        assertEquals("Tapped 2 times", twice.buttonLabel)
    }

    @Test
    fun defaultInitialStateGreetsTheCurrentPlatform() {
        assertEquals(Greeting().greet(), GreetingUiState.initial().message)
    }
}
```

### `shared/src/commonTest/kotlin/__pkg__/uitest/UiScreenshots.kt` (ui: compose)

The UI contract's shared screenshot + accessibility helper (`expect` declarations; actuals in `androidHostTest`/`iosTest`).

```kotlin
package <namespace>.uitest

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Base class of every Compose UI test in commonTest: gives the Android host run its Robolectric runner (JUnit 4
 * needs a class-level `@RunWith`); a plain class on iOS.
 */
expect abstract class UiTest()

/** `android` or `ios` — the platform segment of every screenshot name, since common tests render on both. */
internal expect val uiPlatform: String

/** Reads an environment variable of the test process. */
internal expect fun uiEnv(name: String): String?

/** Returns the text of the file at [path], or `null` when it does not exist. */
internal expect fun uiReadText(path: String): String?

/** Encodes [image] as PNG at [path], creating the parent directories. */
internal expect fun uiWritePng(
    path: String,
    image: ImageBitmap,
)

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
        uiReadText("${uiRepoRoot()}/.ui-quality.yml")
            ?.lines()
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
 * `<UI_SCREENSHOTS_DIR | <repo>/.ui-screenshots>/<kind>/<id>/<state>--<dir>--<theme>--<platform>[--<extra>…].png`.
 */
fun SemanticsNodeInteraction.captureUiScreenshot(
    kind: UiKind,
    id: String,
    state: String,
    variant: UiVariant,
    vararg extra: String,
) {
    val name = (listOf(state, variant.segments, uiPlatform) + extra).joinToString("--")
    uiWritePng("${uiScreenshotsDir()}/${kind.dir}/$id/$name.png", captureToImage())
}

/** `UI_SCREENSHOTS_DIR`, else `<repository root>/.ui-screenshots`. */
internal fun uiScreenshotsDir(): String = uiEnv("UI_SCREENSHOTS_DIR") ?: "${uiRepoRoot()}/.ui-screenshots"

/** The repository root, passed by the build as `UI_REPO_ROOT` (see shared/build.gradle.kts). */
internal fun uiRepoRoot(): String = checkNotNull(uiEnv("UI_REPO_ROOT")) { "UI_REPO_ROOT is not set by the build" }

/** One accessibility finding: a stable rule id and the node it was found on. */
data class UiA11yIssue(
    val rule: String,
    val node: String,
)

private val MIN_TOUCH_TARGET = 48.dp

/**
 * Portable semantics checks that run on every target: each clickable node has an accessible name and a touch
 * target (its touch bounds, which include Material's minimum interactive size) of at least 48dp. Applies
 * `.ui-quality.yml`'s `accessibility` level — `enforce` fails the test, `report` prints a summary, `off` and `none` skip.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.checkUiAccessibility(): List<UiA11yIssue> {
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

### `shared/src/iosMain/kotlin/__pkg__/IOSPlatform.kt`

```kotlin
package <namespace>

import platform.UIKit.UIDevice

/**
 * iOS [Platform], named after the running system and version.
 */
class IOSPlatform : Platform {
    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()
```

### `shared/src/iosMain/kotlin/__pkg__/MainViewController.kt` (ui: compose)

```kotlin
package <namespace>

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point the iOS app embeds (see iosApp/iosApp/ContentView.swift).
 */
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
```

### `shared/src/iosTest/kotlin/__pkg__/IOSPlatformTest.kt`

```kotlin
package <namespace>

import kotlin.test.Test
import kotlin.test.assertTrue

class IOSPlatformTest {
    @Test
    fun nameIsNotBlank() {
        assertTrue(getPlatform().name.isNotBlank())
    }
}
```

### `shared/src/iosTest/kotlin/__pkg__/uitest/UiTest.ios.kt` (ui: compose)

```kotlin
package <namespace>.uitest

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.getenv

/** No runner needed on Kotlin/Native. */
actual abstract class UiTest actual constructor()

internal actual val uiPlatform: String = "ios"

@OptIn(ExperimentalForeignApi::class)
internal actual fun uiEnv(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun uiReadText(path: String): String? =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

@OptIn(ExperimentalForeignApi::class)
internal actual fun uiWritePng(
    path: String,
    image: ImageBitmap,
) {
    val bytes =
        checkNotNull(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes) {
            "PNG encoding failed for $path"
        }
    NSFileManager.defaultManager.createDirectoryAtPath(
        path.substringBeforeLast('/'),
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    check(data.writeToFile(path, atomically = true)) { "Could not write $path" }
}
```
