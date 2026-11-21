# UI implementation on Android (Views and Jetpack Compose)

`ui-contract.md` (next to this file) is the rule set; this file maps each rule onto plain Android `lib`/`app`
modules — classic Views and Jetpack Compose — and the test tools this catalog's scaffolds wire (JUnit 4,
Robolectric, Roborazzi, Espresso, the Compose UI test rules, the Accessibility Test Framework). Read it together
with `compose.md` or `views.md`, whichever toolkit the touched UI uses, and `testing.md`. When the repository's
own UI code consistently does something else, follow the code and report the divergence.

## Screens, components and navigation

| Contract term | Compose | Views |
|---|---|---|
| Screen | A navigation destination: `OrdersScreen(viewModel)` collects the view model's state and forwards events to a stateless `OrdersContent(state, onOpen, …)` that renders it | A `Fragment` destination of a Navigation graph, kept thin: inflate the binding, collect the view model's state, call `render(state)` on the components, forward their events |
| Component | A stateless `@Composable` taking a state value and event lambdas | A custom `View` or a compound view (`<merge>` layout + ViewBinding inside a `FrameLayout`/`LinearLayout` subclass) exposing `render(state)` and listener properties; an item layout bound by a `ViewHolder` |
| Navigation | One `NavHost` (Navigation Compose, type-safe `@Serializable` routes) | One `nav_graph.xml` (or Kotlin DSL graph) inside a `NavHostFragment` |

- **Single Activity.** The app has one `Activity` hosting the navigation host; every other screen is a
  navigation destination (a Compose destination or a `Fragment`). A `Fragment` is a destination host, not a
  reusable building block — reusable UI is a composable or a custom/compound `View`.
- **Navigation lives in the graph.** Components emit intent events (`onOpen(orderId)`); only the screen (or the
  `NavHost` lambda wiring it) calls `navController.navigate(...)`. Never `startActivity`/`navigate` from inside a
  component, an adapter or a `ViewHolder`. The documentation derives the flows from the graph.
- **Composition over inheritance.** Compose: pass content in through slots (`content: @Composable () -> Unit`,
  `actions: @Composable RowScope.() -> Unit`). Views: nest `<include>`d layouts and compound views; never a
  `BaseFragment`/`BaseActivity` that subclasses inherit screen behaviour from — share behaviour through a
  delegate, a Kotlin extension, or a composed component.

## Inputs, events and state

- **State**: one `@Immutable data class XxxState` (Compose) / plain `data class` (Views) per component; a screen's
  view model exposes `StateFlow<XxxUiState>` with every derived value (formatted totals, enabled flags) already
  computed. Compose screens collect with `collectAsStateWithLifecycle()` (`androidx.lifecycle:lifecycle-runtime-compose`);
  Fragments collect inside `viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { … } }`.
- **Events**: typed lambdas named after the intent — `onOpen: (orderId: String) -> Unit`, `onRetry: () -> Unit` —
  never a raw `View`/`MotionEvent`/`KeyEvent` leaking out. A custom `View` exposes `var onOpen: ((String) -> Unit)?`
  and calls it from its own click listener.
- **Two-way binding** only for form fields: Compose's hoisted `value` + `onValueChange` (`TextField`, `Switch`,
  `Slider`); in Views, `render()` sets the value and `doAfterTextChanged`/`setOnCheckedChangeListener` emits
  `onQueryChange(text)`. No two-way Data Binding of lists or domain objects.
- **Localization and direction**: strings from resources (`stringResource`, `getString`); `start`/`end`
  everywhere (`Modifier.padding(start = …)`, `paddingStart`, `layout_constraintStart_toStartOf`,
  `textAlignment="viewStart"`); `android:supportsRtl="true"` in the manifest; directional icons mirrored
  (`Icons.AutoMirrored.*` in Compose, `android:autoMirrored="true"` on vector drawables).
- **Visual states** from the contract §4 are inputs of the state class (`loading`, `error`, `selected`, …), never
  private `remember`/field state a test cannot drive.

## Collections — the rule on Android

Any data-driven or potentially long list, grid, carousel, paged list, drop-down with many entries or large tab
set uses the recycling container — **never** `Column { items.forEach { … } }` inside `verticalScroll`, a
`LinearLayout` filled with `addView` in a loop, or a `wrap_content` `RecyclerView` inside a `NestedScrollView`
(that measures every item and defeats recycling).

| Shape | Views | Compose |
|---|---|---|
| List / grid | `RecyclerView` + `ListAdapter` + `DiffUtil.ItemCallback` (`LinearLayoutManager`, `GridLayoutManager`); `ConcatAdapter` to combine header/footer adapters | `LazyColumn`/`LazyRow`/`LazyVerticalGrid`/`LazyVerticalStaggeredGrid` with `key` and `contentType` |
| Paged / infinite | Paging 3 `PagingDataAdapter` + `withLoadStateFooter(...)` | Paging 3 `collectAsLazyPagingItems()` with `itemKey { it.id }` / `itemContentType { … }` |
| Pages, carousel, many tabs | `ViewPager2` + `FragmentStateAdapter` (or a `RecyclerView.Adapter`) + `TabLayoutMediator` with a scrollable `TabLayout` | `HorizontalPager` + `PrimaryScrollableTabRow`/`ScrollableTabRow` |
| Drop-down / pop-up list | `ListPopupWindow`/`ExposedDropdownMenu` backed by an adapter | `ExposedDropdownMenuBox` with a `LazyColumn` when long |

The collection's view model holds the **whole list state**; selection, expansion and scroll position live there
(or in `rememberSaveable`/`SavedStateHandle`), keyed by item id — never in a `ViewHolder` field or inside a lazy
item's `remember`. Heterogeneous lists declare item types (`getItemViewType` / `contentType`).

```kotlin
data class OrderRowState(val id: String, val title: String, val total: String)
data class OrderListUiState(val items: List<OrderRowState> = emptyList(), val loadingMore: Boolean = false, val endReached: Boolean = false)

// Views: one ViewHolder per item type, diffed by id.
class OrderListAdapter(private val onOpen: (orderId: String) -> Unit) :
    ListAdapter<OrderRowState, OrderListAdapter.OrderRowViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        OrderRowViewHolder(ItemOrderRowBinding.inflate(LayoutInflater.from(parent.context), parent, false), onOpen)
    override fun onBindViewHolder(holder: OrderRowViewHolder, position: Int) = holder.bind(getItem(position))

    class OrderRowViewHolder(private val binding: ItemOrderRowBinding, onOpen: (String) -> Unit) :
        RecyclerView.ViewHolder(binding.root) {
        private var boundId: String? = null
        init { binding.root.setOnClickListener { boundId?.let(onOpen) } }
        fun bind(state: OrderRowState) { boundId = state.id; binding.title.text = state.title; binding.total.text = state.total }
    }

    private object Diff : DiffUtil.ItemCallback<OrderRowState>() {
        override fun areItemsTheSame(old: OrderRowState, new: OrderRowState) = old.id == new.id
        override fun areContentsTheSame(old: OrderRowState, new: OrderRowState) = old == new
    }
}
// The Fragment: binding.orderList.adapter = adapter; then adapter.submitList(state.items) on every new state.

// Compose: the same state, rendered lazily with stable keys.
@Composable
fun OrderList(state: OrderListUiState, onOpen: (orderId: String) -> Unit, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 5 }
    }
    LaunchedEffect(nearEnd, state.endReached) { if (nearEnd && !state.endReached && !state.loadingMore) onLoadMore() }
    LazyColumn(state = listState, modifier = modifier) {
        items(items = state.items, key = { it.id }, contentType = { "order" }) { row -> OrderRow(state = row, onOpen = onOpen) }
        if (state.loadingMore) item(key = "loading", contentType = "loading") { CircularProgressIndicator() }
    }
}
```

Use Paging 3 instead of the hand-rolled `onLoadMore` once the data source is genuinely paged (a `PagingSource`
in the data layer); `ViewPager2`'s `FragmentStateAdapter` and `HorizontalPager` both create pages lazily.

## UI tests — what runs where

| Level | Where | How |
|---|---|---|
| Component | JVM, Robolectric (`src/test`) | Compose: `createComposeRule()` + `setContent { AppTheme { Component(state, …) } }`. Views: inflate the component into an `ActivityScenario`'s activity (or `launchFragmentInContainer` from `androidx.fragment:fragment-testing` for a Fragment host) |
| Screen | JVM, Robolectric (`src/test`) | The screen's content with a fake view model / fake repositories; assert events reach the fake and navigation requests are emitted |
| Flow | Device/emulator (`src/androidTest`) | `createAndroidComposeRule<MainActivity>()` or `ActivityScenarioRule` + Espresso, real navigation, external services faked through the app's DI seam |

Robolectric UI tests need `testOptions { unitTests { isIncludeAndroidResources = true } }` and
`@GraphicsMode(GraphicsMode.Mode.NATIVE)` on the class for real rendering; pin a device with
`@Config(qualifiers = "w360dp-h640dp-xxhdpi")`. Compose UI test 1.11+ deprecates the `createComposeRule` /
`createAndroidComposeRule` factories in favour of the same names under `androidx.compose.ui.test.junit4.v2` —
use the `v2` package when the project's Compose BOM provides it, the classic one otherwise. Behaviour assertions
run once (left-to-right, light); screenshots cover the matrix. Determinism: fixed qualifiers and locale, a fixed
clock injected into the view model, `composeRule.mainClock.autoAdvance = false` around animations, network faked.

### Switching direction, theme and font scale

- **Compose** — wrap the content: `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`,
  `AppTheme(darkTheme = true)`, and for the large-text capture
  `CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f))`.
- **Views (Robolectric)** — set qualifiers before launching the activity/fragment:
  `RuntimeEnvironment.setQualifiers("+ldrtl-night")` (or `@Config(qualifiers = "+ldrtl")` per test),
  `RuntimeEnvironment.setFontScale(2f)` for large text.
- **Device** — `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))` for right-to-left
  and `AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)` before launching the scenario.
- High contrast has no cheap switch on the JVM; capture it only when the app ships its own high-contrast colour
  scheme (`theme` segment `high-contrast`).

## Screenshot helper

One file per module that has UI tests: `<module>/src/test/java/<package>/testing/UiScreenshots.kt`. It resolves
`UI_SCREENSHOTS_DIR`, else `<repository root>/.ui-screenshots`, reads `.ui-quality.yml`, builds the capture
matrix and the contract's file name, and forces Roborazzi to write (its `captureRoboImage` is a no-op unless a
`roborazzi.test.*` mode is on). Dependencies (test scope): `io.github.takahirom.roborazzi:roborazzi`,
`roborazzi-compose` (Compose only) and `roborazzi-accessibility-check`; no Roborazzi Gradle plugin is needed.

```kotlin
object UiScreenshots {
    enum class Kind { COMPONENT, SCREEN, FLOW }
    enum class Direction { LTR, RTL }
    enum class Theme { LIGHT, DARK, HIGH_CONTRAST }
    enum class Accessibility { ENFORCE, REPORT, OFF, NONE }
    data class Variant(val direction: Direction, val theme: Theme) {
        val isRtl: Boolean get() = direction == Direction.RTL
        val isDark: Boolean get() = theme == Theme.DARK
    }

    private val segment = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val setting = Regex("""^\s*(accessibility|directions|themes)\s*:\s*([^#]*)""")

    val repositoryRoot: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        generateSequence(start) { it.parentFile }.firstOrNull { File(it, ".git").exists() }
            ?: generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }
            ?: start
    }
    val rootDir: File by lazy {
        System.getenv("UI_SCREENSHOTS_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(repositoryRoot, ".ui-screenshots")
    }
    private val settings: Map<String, String> by lazy {
        val file = File(repositoryRoot, ".ui-quality.yml")
        if (!file.isFile) emptyMap()
        else file.readLines().mapNotNull { setting.find(it) }.associate { it.groupValues[1] to it.groupValues[2].trim() }
    }
    private fun list(key: String) = settings[key]?.trim('[', ']', ' ')?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    val accessibility: Accessibility by lazy {
        settings["accessibility"]?.let { Accessibility.valueOf(it.uppercase()) } ?: Accessibility.ENFORCE
    }
    val matrix: List<Variant> by lazy {
        val directions = list("directions")?.map { Direction.valueOf(it.uppercase()) } ?: Direction.entries
        val themes = list("themes")?.map { Theme.valueOf(it.uppercase().replace('-', '_')) } ?: listOf(Theme.LIGHT, Theme.DARK)
        directions.flatMap { d -> themes.map { t -> Variant(d, t) } }
    }

    fun file(kind: Kind, id: String, state: String, variant: Variant, vararg extra: String): File {
        require(id.isNotBlank() && !id.contains('/')) { "Invalid id '$id'" }
        (listOf(state) + extra).forEach { require(segment.matches(it)) { "'$it' is not kebab-case" } }
        val name = (listOf(state, variant.direction.slug(), variant.theme.slug()) + extra).joinToString("--")
        return File(rootDir, "${kind.slug()}/$id/$name.png").also { it.parentFile?.mkdirs() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    val recordOptions: RoborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)

    @OptIn(ExperimentalRoborazziApi::class)
    val accessibilityOptions: RoborazziATFAccessibilityCheckOptions? by lazy {
        when (accessibility) {
            Accessibility.ENFORCE -> RoborazziATFAccessibilityCheckOptions(failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error)
            Accessibility.REPORT -> RoborazziATFAccessibilityCheckOptions(failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.LogOnly)
            Accessibility.OFF, Accessibility.NONE -> null
        }
    }

    private fun Enum<*>.slug(): String = name.lowercase().replace('_', '-')
}
```

A component test capturing every state across the matrix (Compose; a Views test swaps the
`CompositionLocalProvider` for `RuntimeEnvironment.setQualifiers` + `ActivityScenario.launch` per cell and
captures with `onView(isRoot()).captureRoboImage(...)` / checks with `onView(isRoot()).checkRoboAccessibility(...)`):

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class OrderRowTest {
    @get:Rule val composeRule = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun orderRow_capturesEveryState() {
        var state by mutableStateOf(OrderRowFixtures.typical)
        var cell by mutableStateOf(UiScreenshots.matrix.first())
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (cell.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                AppTheme(darkTheme = cell.isDark) { Surface { OrderRow(state = state, onOpen = {}) } }
            }
        }
        for ((name, fixture) in OrderRowFixtures.byState) for (variant in UiScreenshots.matrix) {
            state = if (variant.isRtl) fixture.rtl else fixture.ltr
            cell = variant
            composeRule.waitForIdle()
            val file = UiScreenshots.file(UiScreenshots.Kind.COMPONENT, "OrderRow", name, variant)
            composeRule.onRoot().captureRoboImage(file.path, roborazziOptions = UiScreenshots.recordOptions)
            UiScreenshots.accessibilityOptions?.let { composeRule.onRoot().checkRoboAccessibility(it) }
        }
    }
}
```

**On a device** (flow tests), the same names are written through AndroidX Test Storage so AGP pulls them back:
add `androidTestImplementation("androidx.test.services:storage")`, `androidTestUtil("androidx.test.services:test-services")`
and `testInstrumentationRunnerArguments["useTestStorageService"] = "true"`, and keep a small
`<module>/src/androidTest/java/<package>/testing/DeviceUiScreenshots.kt` that validates the segments like the JVM
helper and writes `TestStorage().openOutputFile("ui-screenshots/<kind>/<Id>/<name>.png")` from a bitmap
(`composeRule.onRoot().captureToImage().asAndroidBitmap()`, or Espresso's `captureToBitmap` view action).
AGP copies them to `build/outputs/connected_android_test_additional_output/`; `iru-android-test` moves them into
`.ui-screenshots/` (falling back to `adb pull /sdcard/googletest/test_outputfiles/ui-screenshots`).

Pixel-baseline comparison is opt-in: a repository that wants it applies the `io.github.takahirom.roborazzi`
Gradle plugin and records baselines with its own `recordRoborazziDebug`/`verifyRoborazziDebug` tasks in the
plugin's output directory — separate from the `.ui-screenshots/` export, which is produced either way.

## Accessibility checks

- **JVM (primary)** — `checkRoboAccessibility(UiScreenshots.accessibilityOptions)` from
  `roborazzi-accessibility-check` runs the Accessibility Test Framework (ATF) over the rendered Compose node or
  View hierarchy on Robolectric: `enforce` fails the test (`AccessibilityViewCheckException`), `report` logs each
  result to stderr as `Roborazzi: Error: [… AccessibilityHierarchyCheckResult ERROR <CheckName> …]`, `off` and `none` skip it.
- **Espresso on a device** — `AccessibilityChecks.enable().setRunChecksFromRootView(true)`
  (`androidx.test.espresso:espresso-accessibility`) in a `@Before`; every `perform(...)` then runs ATF. For
  `report`, also call `.setThrowExceptionFor(null)` so results are logged instead of thrown.
- **Compose on a device** — `composeRule.enableAccessibilityChecks()` from `androidx.compose.ui:ui-test-junit4-accessibility`
  (Compose 1.8+, `@RequiresApi(34)` — a device or emulator on API 34+, not Robolectric); checks run on actions,
  or explicitly through `onNode(...).tryPerformAccessibilityChecks()`. Pass an `AccessibilityValidator` with
  `setThrowExceptionFor(null)` for `report`.
- **Reading the level** — JVM tests read `.ui-quality.yml` through `UiScreenshots`; instrumented tests read the
  `uiAccessibility` runner argument (`InstrumentationRegistry.getArguments().getString("uiAccessibility", "enforce")`),
  which `iru-android-test` passes as `-Pandroid.testInstrumentationRunnerArguments.uiAccessibility=<level>`.
- ATF's `TouchTargetSizeCheck` accepts 32dp by default; the contract still asks for 48dp targets, which Android
  Lint and `Modifier.minimumInteractiveComponentSize()` (Material 3 does this for its own controls) help keep.
  Suppress an individual ATF result only with a narrow matcher and a comment saying why.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- TalkBack: `contentDescription` on icon-only/image views (`null`/`importantForAccessibility="no"` when decorative); Compose: `Modifier.semantics { }`, `clearAndSetSemantics`, `Role`, `stateDescription`, `liveRegion`, `mergeDescendants` for grouped rows.
- Text in `sp`, layouts that survive font scale 200%; touch targets >= 48dp (`minimumInteractiveComponentSize`); honour the animator-duration scale (0 = no non-essential animation) via `ValueAnimator.areAnimatorsEnabled()` / `Settings.Global.ANIMATOR_DURATION_SCALE`.
- Logical focus order via `traversalIndex`/`accessibilityTraversalBefore`; custom views implement `AccessibilityNodeInfo` actions.

## Sources

- Guide to app architecture, UI layer: <https://developer.android.com/topic/architecture/ui-layer>
- State and Jetpack Compose / state hoisting: <https://developer.android.com/develop/ui/compose/state-hoisting>
- Navigation (single activity, Navigation Compose type safety): <https://developer.android.com/guide/navigation>
- Lists in Compose (keys, content types): <https://developer.android.com/develop/ui/compose/lists>
- RecyclerView and `ListAdapter`: <https://developer.android.com/develop/ui/views/layout/recyclerview>
- Paging 3: <https://developer.android.com/topic/libraries/architecture/paging/v3-overview>
- ViewPager2 with tabs: <https://developer.android.com/guide/navigation/navigation-swipe-view-2>
- Support right-to-left layouts: <https://developer.android.com/training/basics/supporting-devices/languages#SupportLayoutMirroring>
- Test your Compose layout: <https://developer.android.com/develop/ui/compose/testing>
- Accessibility testing in Compose: <https://developer.android.com/develop/ui/compose/accessibility/testing>
- Espresso accessibility checking: <https://developer.android.com/training/testing/espresso/accessibility-checking>
- Accessibility Test Framework for Android: <https://github.com/google/Accessibility-Test-Framework-for-Android>
- Robolectric (device configuration, native graphics): <https://robolectric.org/device-configuration/>
- Roborazzi (capture API, accessibility check): <https://github.com/takahirom/roborazzi>
- AndroidX Test Storage / additional test output: <https://developer.android.com/reference/androidx/test/services/storage/TestStorage>
