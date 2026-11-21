# UI implementation in Kotlin Multiplatform (Compose Multiplatform and native UI)

`ui-contract.md` (next to this file) is the rule set; this file maps each rule onto a KMP app — `ui: compose`
(Compose Multiplatform screens in the shared module's `commonMain`) and `ui: native` (Jetpack Compose in
`androidApp`, SwiftUI in `iosApp`, both over the same shared state) — and onto the test tools that actually run
here. Read it together with `compose-multiplatform.md` or `native-ui.md` (whichever the touched UI uses) and
`testing.md`. When the repository's own UI code consistently does something else, follow the code and report the
divergence.

## Screens, components and navigation

| Contract term | `ui: compose` (shared) | `ui: native` — Android (`androidApp`) | `ui: native` — iOS (`iosApp`) |
|---|---|---|---|
| Screen | A navigation destination in `commonMain`: `OrdersRoute(viewModel)` collects the shared view model's state and passes it to a stateless `OrdersScreen(state, onOpen, …)` | Same split in `androidApp`: `OrdersRoute` owns the shared view model, `OrdersScreen` is stateless | A root SwiftUI `View` of a `NavigationStack` destination owning the Swift wrapper model (`native-ui.md`) and passing `state` + closures to a stateless view |
| Component | A stateless `@Composable` in `commonMain` taking a state value and event lambdas | A stateless `@Composable` in `androidApp` | A SwiftUI `View` struct with `let` inputs and closure outputs |
| Navigation | One `NavHost` (Navigation for Compose Multiplatform, `@Serializable` routes) | One `NavHost` in `androidApp` | One `NavigationStack(path:)` in `iosApp` |

- **The shared view model is the state holder** (`shared-logic.md`): a `ViewModel` (or plain state holder) in
  `commonMain` exposes one `StateFlow<XUiState>` — an immutable `data class` (or sealed interface) with every
  derived value already computed — plus non-`suspend` intent functions (`onOpen(id)`, `onRetry()`,
  `onQueryChange(text)`). With `ui: native` both platforms consume that *same* class; never duplicate presentation
  logic in Kotlin-Android and Swift.
- **Components never see the view model.** Only the screen (`…Route` / the SwiftUI root view) holds it, collects
  state, handles navigation, and passes state down and lambdas/closures up. Previews and UI tests target the
  stateless screen and components.
- **Composition over inheritance.** Compose: slots (`content: @Composable () -> Unit`,
  `actions: @Composable RowScope.() -> Unit`); SwiftUI: `@ViewBuilder` content and view modifiers. The only
  subclasses are the hosts the platforms demand (`MainActivity : ComponentActivity`, the
  `UIViewControllerRepresentable` around `MainViewController()`), kept thin.
- **Navigation lives in one place per app** — the shared `NavHost` (`ui: compose`) or one per platform
  (`ui: native`); components emit intent events and never call `navController.navigate` or push views themselves.
  Documentation derives the flows from that graph.

## Inputs, events and state

- **State**: `data class OrderRowState(val id: String, …)` in `commonMain`. Add `@Immutable` only in a module that
  depends on the Compose runtime (`ui: compose`); with `ui: native` the shared module has no Compose dependency and
  plain `val`s of read-only types are enough. Swift sees the class through the Objective-C header with the same
  property names (`state.items` is `[OrderRowState]`, `row.id` stays `id` — verified in a generated `Shared.h`).
- **Events**: typed lambdas named after the intent (`onOpen: (orderId: String) -> Unit`); SwiftUI views take
  closures (`let onOpen: (String) -> Void`). Never leak a raw pointer/gesture event.
- **Two-way binding** only for form fields: hoisted `value` + `onValueChange` in Compose; in SwiftUI a
  `Binding` created from the wrapper model (`Binding(get: { model.state.query }, set: model.onQueryChange)`) —
  never a `Binding` to a whole Kotlin state object or list.
- **Visual states** (`loading`, `empty`, `error`, `selected`, …) are fields of the state class, never private
  `remember`/`@State` a test cannot drive.
- **Localization and direction**: `ui: compose` strings from `composeResources/values*/strings.xml` through `Res`;
  `ui: native` from `androidApp` resources and the iOS String Catalog. Logical edges only — `Modifier.padding(start
  = …)`, `Arrangement.Start`, SwiftUI `.leading`/`.trailing`; mirrored icons (`Icons.AutoMirrored.*`,
  `Image(systemName:)` symbols that flip, `.flipsForRightToLeftLayoutDirection(true)` on custom images).

## Collections — the rule in KMP

Any data-driven or potentially long list, grid, carousel, paged list, long drop-down or large tab set:

| Shape | Compose (shared or `androidApp`) | SwiftUI (`iosApp`) |
|---|---|---|
| List / grid | `LazyColumn`/`LazyRow`/`LazyVerticalGrid`/`LazyVerticalStaggeredGrid` with `key` and `contentType` | `List`, or `LazyVStack`/`LazyHStack`/`LazyVGrid` inside `ScrollView`, `ForEach(items, id: \.id)` |
| Paged / infinite | A shared view model that appends pages, triggered by a `derivedStateOf` near-end check; AndroidX Paging (`collectAsLazyPagingItems()`) only where the module already uses Paging | `.onAppear` of the last rows calls the shared `onLoadMore()` |
| Pages, carousel, many tabs | `HorizontalPager` + `PrimaryScrollableTabRow`/`ScrollableTabRow` | `TabView` with `.tabViewStyle(.page)`; a scrollable tab strip in a `ScrollView(.horizontal)` of `LazyHStack` |

Never `Column { items.forEach { … } }` inside `verticalScroll`, `VStack { ForEach }` inside a `ScrollView`, or a
`forEach` over unbounded data. The shared view model holds the **whole list state**; selection, expansion and
scroll anchors live there (keyed by id), never in a lazy item's `remember` or a row's `@State`.

```kotlin
// commonMain — state and the shared view model (both UIs consume it).
data class OrderRowState(val id: String, val title: String, val total: String)
data class OrderListUiState(
    val items: List<OrderRowState> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

class OrderListViewModel(private val repository: OrderRepository) : ViewModel() {
    private val _state = MutableStateFlow(OrderListUiState())
    val state: StateFlow<OrderListUiState> = _state.asStateFlow()

    fun onLoadMore() {
        val current = _state.value
        if (current.loadingMore || current.endReached) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            val page = repository.page(after = current.items.lastOrNull()?.id)
            _state.update { it.copy(items = it.items + page.rows, loadingMore = false, endReached = page.isLast) }
        }
    }
}

// Collection component (commonMain for ui: compose, androidApp for ui: native) — lazy, keyed, typed.
@Composable
fun OrderList(state: OrderListUiState, onOpen: (String) -> Unit, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.loadingMore, state.endReached) {
        if (nearEnd && !state.loadingMore && !state.endReached) onLoadMore()
    }
    LazyColumn(modifier, state = listState) {
        items(state.items, key = { it.id }, contentType = { "order" }) { row -> OrderRow(row, onOpen) }
        if (state.loadingMore) item(key = "loading", contentType = "loading") { CircularProgressIndicator() }
    }
}
```

```swift
// iosApp (ui: native) — the item view is a pure function of one Kotlin row state.
struct OrderList: View {
    let state: OrderListUiState
    let onOpen: (String) -> Void
    let onLoadMore: () -> Void

    var body: some View {
        List(state.items, id: \.id) { row in
            OrderRow(state: row, onOpen: onOpen)
                .onAppear { if row.id == state.items.last?.id { onLoadMore() } }
        }
    }
}
```

The `OrderList`/`OrderRow` `LazyColumn` sketch above was compiled and rendered for Android (Robolectric) and iOS
(simulator) in a throwaway project.

## UI tests — what runs where

| Level | `ui: compose` | `ui: native` Android | `ui: native` iOS |
|---|---|---|---|
| Component / screen | `commonTest`, `runComposeUiTest` (`androidx.compose.ui.test.v2.runComposeUiTest`, `@OptIn(ExperimentalTestApi::class)`) — runs on **both** the Android host (Robolectric) and the iOS simulator | `androidApp/src/test`, Robolectric + `createComposeRule()` | `iosApp/iosAppTests` (XCTest), the SwiftUI view rendered with `ImageRenderer` |
| Flow (e2e) | Android: `androidApp/src/androidTest` on a device/emulator; iOS: an XCUITest target in `iosApp` if the repository has one | `androidApp/src/androidTest` (device/emulator) | An XCUITest target in `iosApp` (simulator) |

- **`runComposeUiTest` on the Android host needs Robolectric** (verified: without it, it NPEs). The module wires
  it once: `kotlin { android { withHostTest { isIncludeAndroidResources = true } } }`, Robolectric and
  `androidx.compose.ui:ui-test-manifest` in `androidHostTest`, and an `expect abstract class UiTest()` in
  `commonTest` whose Android `actual` carries `@RunWith(RobolectricTestRunner::class)`,
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and a pinned `@Config(sdk = …, qualifiers = "w360dp-h640dp-xhdpi")`
  (the iOS `actual` is empty; `-Xexpect-actual-classes` silences the Beta warning). Every common UI test class
  extends `UiTest` — the one inheritance JUnit 4's class-level runner forces. A module without this wiring keeps
  Compose UI tests in `iosTest` (see `testing.md`) and reports the Android side as not covered; adding the wiring
  is a build change the task must ask for.
- Use the `v2` `runComposeUiTest` (the original is deprecated in Compose Multiplatform 1.12; `v2` queues
  coroutines on a `StandardTestDispatcher`, so call `waitForIdle()` after changing state). Call `setContent` **once**
  per test (a second call throws on Android) and drive state changes through a `mutableStateOf` the content reads.
- Behaviour assertions run once (left-to-right, light); screenshots cover the matrix. Determinism: a fixed size
  (`Modifier.size(360.dp, 640.dp)` on the root, Robolectric `qualifiers`), a fixed clock injected into the view
  model, `mainClock.autoAdvance = false` around animations, fakes for repositories (`testing.md`).
- **Flow tests and XCUITest** need an emulator/simulator: they are written only when the task asks, and run by
  the group skill behind `.integration-test.lock`; unavailable → "unverified".

### Switching direction, theme and text size inside a test

- **Compose** (both modes): `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`; the
  app theme composable takes `darkTheme: Boolean` (`AppTheme(darkTheme = true) { … }`, defaulting to
  `isSystemInDarkTheme()` in production); large text with
  `CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f))`.
- **SwiftUI** (`ImageRenderer`): `.environment(\.layoutDirection, .rightToLeft)`,
  `.environment(\.colorScheme, .dark)`, `.environment(\.dynamicTypeSize, .accessibility3)` for large text.
  `ImageRenderer` does not render UIKit-backed views (`List`, `ScrollView`, `TextField`, maps): capture those
  through XCUITest (`XCUIScreen.main.screenshot()` / `app.screenshot()`) with launch arguments
  `-AppleLanguages (ar)` and an app-side dark-mode switch, and render the item component alone with
  `ImageRenderer`.
- High contrast has no cheap switch here; capture `high-contrast` only when the app ships its own scheme.

## Screenshot helpers

The same `<Id>` renders on Android and iOS, so every KMP capture carries the platform variant segment:
`.ui-screenshots/<kind>/<Id>/<state>--<dir>--<theme>--android.png` and `…--ios.png`.

**`ui: compose` — `shared/src/commonTest/kotlin/<package>/uitest/UiScreenshots.kt`** plus two actuals,
`androidHostTest/…/uitest/UiTest.android.kt` and `iosTest/…/uitest/UiTest.ios.kt`. The common file holds
`expect abstract class UiTest()`, `UiKind`, `UiVariant`, `UiQuality` (reads `.ui-quality.yml`: `accessibility`,
`directions`, `themes`; missing file or key → the contract defaults), `SemanticsNodeInteraction.captureUiScreenshot(kind,
id, state, variant, vararg extra)` (uses the common `captureToImage()`) and `ComposeUiTest.checkUiAccessibility()`;
the actuals supply the runner, the platform segment, environment access and PNG encoding:

```kotlin
// commonTest
fun SemanticsNodeInteraction.captureUiScreenshot(kind: UiKind, id: String, state: String, variant: UiVariant, vararg extra: String) {
    val name = (listOf(state, variant.segments, uiPlatform) + extra).joinToString("--")
    uiWritePng("${uiScreenshotsDir()}/${kind.dir}/$id/$name.png", captureToImage())
}
internal fun uiScreenshotsDir(): String = uiEnv("UI_SCREENSHOTS_DIR") ?: "${uiRepoRoot()}/.ui-screenshots"

// androidHostTest — Robolectric, native graphics
internal actual fun uiWritePng(path: String, image: ImageBitmap) {
    File(path).apply { parentFile?.mkdirs() }.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}

// iosTest — Skia (exposed by Compose Multiplatform) encodes; Foundation writes to the host file system
internal actual fun uiWritePng(path: String, image: ImageBitmap) {
    val bytes = checkNotNull(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes)
    NSFileManager.defaultManager.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
    bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }.writeToFile(path, true)
}
```

The build passes the locations — the iOS simulator receives only `SIMCTL_CHILD_`-prefixed variables (verified):

```kotlin
// shared/build.gradle.kts
val uiScreenshotsDir: String =
    providers.environmentVariable("UI_SCREENSHOTS_DIR").getOrElse(rootDir.resolve(".ui-screenshots").absolutePath)
tasks.withType<Test>().configureEach {
    environment("UI_SCREENSHOTS_DIR", uiScreenshotsDir); environment("UI_REPO_ROOT", rootDir.absolutePath)
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_UI_SCREENSHOTS_DIR", uiScreenshotsDir); environment("SIMCTL_CHILD_UI_REPO_ROOT", rootDir.absolutePath)
}
```

A screen test capturing every state across the matrix (runs on both targets):

```kotlin
@OptIn(ExperimentalTestApi::class)
class OrderListTest : UiTest() {
    @Test
    fun everyStateIsCapturedInEveryDirectionAndTheme() = runComposeUiTest {
        var cell by mutableStateOf(OrderListFixtures.typical to UiQuality.matrix.first())
        setContent {
            val (state, variant) = cell
            CompositionLocalProvider(LocalLayoutDirection provides variant.direction) {
                AppTheme(darkTheme = variant.darkTheme) {
                    OrderList(state, onOpen = {}, onLoadMore = {}, modifier = Modifier.size(360.dp, 640.dp))
                }
            }
        }
        OrderListFixtures.byState.forEach { (name, state) ->
            UiQuality.matrix.forEach { variant ->
                cell = state to variant
                waitForIdle()
                onRoot().captureUiScreenshot(UiKind.COMPONENT, "OrderList", name, variant)
            }
        }
        checkUiAccessibility()
    }
}
```

**`ui: native` Android — `androidApp/src/test/kotlin/<package>/android/uitest/UiScreenshots.kt`**: the same API on
the JVM only (`createComposeRule()`, `captureToImage().asAndroidBitmap()`, platform segment `android`,
`ComposeContentTestRule.checkUiAccessibility()`), the repository root from `UI_REPO_ROOT` (set by
`androidApp/build.gradle.kts`) or the nearest `settings.gradle.kts`.

**`ui: native` iOS — `iosApp/iosAppTests/UIScreenshots.swift`**: renders a SwiftUI view with `ImageRenderer`
(`scale = 2`, a fixed `frame`, `Color(uiColor: .systemBackground)` behind it) and writes `…--ios.png`. The
repository root is derived from `#filePath` (`<repo>/iosApp/iosAppTests/UIScreenshots.swift` → three levels up);
`UI_SCREENSHOTS_DIR` reaches the test process when `xcodebuild test` is run with `TEST_RUNNER_UI_SCREENSHOTS_DIR`
(verified). XCUITest captures are attached instead (`XCTAttachment(screenshot:)`, `name` = the contract file name,
`lifetime = .keepAlways`) and exported from the `.xcresult` by `iru-kmp-test`.

Pixel-baseline comparison is opt-in and separate from this export: Roborazzi (`io.github.takahirom.roborazzi`)
covers Android host tests (`roborazzi-compose`, `recordRoborazziDebug`/`verifyRoborazziDebug`) and has
experimental Compose Multiplatform iOS (`roborazzi-compose-ios`, `onRoot().captureRoboImage(this, filePath = …)`,
`recordRoborazziIosSimulatorArm64`) and desktop (`roborazzi-compose-desktop`) support — use it only where the
repository already applies its plugin (unverified here).

## Accessibility checks

- **Portable semantics check (all KMP Compose UI tests)** — `checkUiAccessibility()` walks the merged semantics
  tree and reports every clickable node without an accessible name (`missing-label`: no text, content description
  or click label) or with touch bounds under 48dp (`touch-target-too-small`, using `touchBoundsInRoot`, which
  includes Material's minimum interactive size). `enforce` fails the test, `report` prints
  `UI accessibility (report): {rule=count}`, `off` skips. Verified on the Android host and the iOS simulator.
- **ATF (Android)** — `androidx.compose.ui:ui-test-junit4-accessibility` adds `enableAccessibilityChecks()` to
  `ComposeTestRule`/`AndroidComposeTestRule` only (not to `runComposeUiTest`), and under Robolectric it passed an
  obviously unlabeled 20dp clickable (verified) — so it counts only in device tests (`androidApp/src/androidTest`,
  API 34+). Robolectric runs rely on the semantics check above.
- **iOS native** — XCUITest `try app.performAccessibilityAudit()` (Xcode 15+/iOS 17+) in the UI-test target, with
  an issue handler that records instead of failing for `report`. `ImageRenderer` component tests have no audit;
  they rely on the SwiftUI defaults (`.accessibilityLabel`, Dynamic Type fonts, 44pt targets).
- The level comes from `.ui-quality.yml` (read by the helpers; `iru-kmp-test` reports which level applied).

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Compose Multiplatform: `Modifier.semantics` (`contentDescription`, `role`, `stateDescription`) maps to TalkBack on Android and VoiceOver on iOS; text in `sp`; targets >= 48dp.
- Android side: see the Android guidance (TalkBack, `contentDescription`, animator-duration scale). iOS host (`iosApp/`): VoiceOver - native SwiftUI/UIKit views set `accessibilityLabel`/`accessibilityTraits`; respect Dynamic Type and Reduce Motion in host code.
- Native-UI flavor: each platform's own rules apply to its own UI code.

## Sources

- Testing Compose Multiplatform UI (`runComposeUiTest`, targets): <https://kotlinlang.org/docs/multiplatform/compose-test.html>
- Compose Multiplatform accessibility: <https://kotlinlang.org/docs/multiplatform/compose-accessibility.html>
- Lists and grids in Compose (keys, content types): <https://developer.android.com/develop/ui/compose/lists>
- State hoisting: <https://developer.android.com/develop/ui/compose/state-hoisting>
- Navigation in Compose Multiplatform: <https://kotlinlang.org/docs/multiplatform/compose-navigation.html>
- Common ViewModel: <https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html>
- Compose accessibility testing (ATF, `enableAccessibilityChecks`): <https://developer.android.com/develop/ui/compose/accessibility/testing>
- Robolectric device configuration and native graphics: <https://robolectric.org/device-configuration/>
- Roborazzi (Android, Compose Multiplatform desktop/iOS): <https://github.com/takahirom/roborazzi>
- SwiftUI lists and lazy stacks: <https://developer.apple.com/documentation/swiftui/lists>,
  <https://developer.apple.com/documentation/swiftui/lazyvstack>
- `ImageRenderer`: <https://developer.apple.com/documentation/swiftui/imagerenderer>
- XCUITest accessibility audits: <https://developer.apple.com/documentation/xctest/xcuiapplication/performaccessibilityaudit(for:_:)>
- `simctl` environment forwarding (`SIMCTL_CHILD_`): `xcrun simctl help spawn`
- Kotlin/Native Objective-C interop: <https://kotlinlang.org/docs/native-objc-interop.html>
