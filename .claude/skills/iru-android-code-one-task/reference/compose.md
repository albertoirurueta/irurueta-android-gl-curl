# Jetpack Compose

Conventions specific to Jetpack Compose UI in the `app` module (this catalog's Compose sample, or a
`com.android.application` project's own screens). Everything in `code-style.md`, `kdoc.md`, and `testing.md`
still applies; this file adds what's specific to composables and Compose state. Read this file instead of
`views.md` for a task that touches a `@Composable` function — a screen/component is Compose or classic Views, not
both. For anything that is a screen, component or list, also read `ui-contract.md` and `ui-implementation.md` —
decomposition, the screen/content split, lazy collections, UI tests, screenshots and accessibility checks live
there.

## Stateless composables and state hoisting

- **A composable is stateless by default**: it receives the data it renders and the callbacks it invokes as
  parameters, and owns no state of its own beyond transient, purely visual state (an expand/collapse animation
  progress, for instance). The caller — a screen-level composable, or a `ViewModel` behind it — owns the actual
  state and passes it down.
- **State hoisting**: when a composable needs local mutable state, hoist it to the nearest caller that needs to
  observe or control it, via a `value: T` + `onValueChange: (T) -> Unit` pair (mirroring `TextField`'s own
  shape), rather than the composable holding a private `remember { mutableStateOf(...) }` the caller can't see or
  drive from a test/preview.
- A composable that *is* the state owner (a small, self-contained widget with no caller-visible state) can hold
  its own `remember`/`rememberSaveable` — don't hoist state nobody outside the composable needs.

```kotlin
@Composable
fun OrientationBadge(
    orientationDegrees: Int,
    onRecalculate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Text(stringResource(R.string.orientation_degrees, orientationDegrees))
        IconButton(onClick = onRecalculate) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.recalculate_orientation))
        }
    }
}
```

User-visible text and content descriptions always come from string resources (`stringResource`) — never literals —
so the right-to-left and other locale captures in `ui-implementation.md` render real translations.

## Lists, grids and pagers are lazy

Any data-driven or potentially long collection is a `LazyColumn`/`LazyRow`/`LazyVerticalGrid`/
`LazyVerticalStaggeredGrid`/`HorizontalPager` whose `items(...)` pass a stable `key` (the item id, never the
index) and a `contentType`, rendering one stateless item composable — never `Column { list.forEach { … } }` inside
`Modifier.verticalScroll`. Paged data uses Paging 3's `collectAsLazyPagingItems()`; the whole list state,
selection and scroll position belong to the view model or `rememberSaveable`, keyed by id. See
`ui-implementation.md`'s "Collections" section for the full pattern.

## `Modifier` as the first optional parameter

Every composable that emits UI takes a `modifier: Modifier = Modifier` parameter, defaulted so callers that don't
need to customize layout/styling can omit it — and it's the first parameter after any required data/callback
parameters (never last), so a caller applying `Modifier.fillMaxWidth().padding(16.dp)` reads naturally right
after the composable's name. Forward it to the composable's own root layout node, not to some nested child, so a
caller's `Modifier.testTag(...)`/`.semantics { }` actually lands where they expect.

## `remember` and `rememberSaveable`

- **`remember { ... }`** survives recomposition but not a configuration change or process death — for state that
  is fine to lose then (an animation's current frame, a derived value recomputed from stable inputs).
- **`rememberSaveable { ... }`** additionally survives configuration change/process death by saving into the
  `Bundle` — use it for anything the user would be annoyed to lose on rotation (a form field's current text, a
  selected tab). Requires the value be `Parcelable`, a primitive, or paired with a custom `Saver`.
- **`derivedStateOf { ... }`** for a value computed from other `State`s that shouldn't trigger recomposition on
  every read, only when its own computed result actually changes (a "is the list scrolled past item 3" boolean
  derived from a `LazyListState`).
- Never wrap a value in `remember` and then also hoist it — pick one owner for a given piece of state.

## Side effects

- **`LaunchedEffect(key1, ...)`** for a suspend-function side effect tied to a composable's lifecycle, restarted
  when any key changes — the standard place to kick off a coroutine that depends on a composable's input.
- **`DisposableEffect(key1, ...)`** when the effect needs explicit cleanup (a listener registered on an Android
  framework object needs to be unregistered) — return an `onDispose { }` block that does it, mirroring
  `GLTextureView.onDetachedFromWindow()`'s Views-world cleanup obligation, but scoped to the composable's presence
  in the tree instead of a `View`'s window attachment.
- **`SideEffect { }`** only for a non-suspending effect that must run on every successful recomposition (syncing
  a value to a non-Compose system) — rare; reach for `LaunchedEffect` first.
- Never launch a coroutine directly inside a composable's body (outside `LaunchedEffect`/a coroutine scope from
  `rememberCoroutineScope()`) — it would relaunch on every recomposition with no cancellation tied to the
  composable leaving the tree.

## `@Preview`

Every composable meant to be visually reviewed gets at least one `@Preview` composable alongside it — a
zero-argument wrapper that calls the real composable with representative sample data, wrapped in the project's
Material 3 theme:

```kotlin
@Preview(showBackground = true)
@Composable
private fun OrientationBadgePreview() {
    AppTheme {
        OrientationBadge(orientationDegrees = 90, onRecalculate = {})
    }
}
```

Keep the preview `private` — it exists for tooling, not as part of the module's API — and add
`@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)` alongside the default light preview whenever the composable
has non-trivial color/contrast logic worth checking in dark mode too.

## Material 3 theming

- Read colors, typography, and shapes from `MaterialTheme.colorScheme`/`.typography`/`.shapes` inside the
  composable — never a hardcoded `Color(0xFF...)` or a literal `sp`/`dp` text size for something the theme
  already defines, so the whole screen re-themes consistently (including dark mode) from one place.
  `androidx.compose.material3` is this catalog's default (see `reference/README.md`'s
  `androidx-material3`/`androidx-compose-bom` aliases) — Material 2 (`androidx.compose.material`) is not mixed
  into a Material 3 screen.
- Wrap the app's root composable (and every `@Preview`) in the project's own `AppTheme`/`<ProjectName>Theme`
  composable, not `MaterialTheme` directly, so a later palette/typography change is a one-file edit.

## Testing with `createComposeRule`

Component and screen tests run on the JVM with Robolectric (`@RunWith(RobolectricTestRunner::class)` +
`@GraphicsMode(GraphicsMode.Mode.NATIVE)` in `src/test`), using the same `createComposeRule()` API — and capture
every visual state across the `.ui-quality.yml` matrix with Roborazzi plus an accessibility check, as
`ui-implementation.md` shows. Instrumented Compose tests (`src/androidTest`) are for flows on a device; see
`testing.md`'s "`androidTest`" section. The rule API is the same in both places:

```kotlin
@get:Rule
val composeTestRule = createAndroidComposeRule<ComponentActivity>()

@Test
fun orientationBadge_click_invokesOnRecalculate() {
    var recalculated = false
    composeTestRule.setContent {
        AppTheme {
            OrientationBadge(orientationDegrees = 0, onRecalculate = { recalculated = true })
        }
    }

    composeTestRule.onNodeWithContentDescription(
        composeTestRule.activity.getString(R.string.recalculate_orientation),
    ).performClick()

    assertTrue(recalculated)
}
```

- **`createComposeRule()`** for a composable tested in isolation (`createAndroidComposeRule<ComponentActivity>()`
  when the test needs the activity, e.g. for `getString`); with Compose UI test 1.11+ import both from
  `androidx.compose.ui.test.junit4.v2`; **`createAndroidComposeRule<MainActivity>()`**
  when the test needs a real hosting `Activity` (navigation, `savedInstanceState`, system-service access).
- Query by semantics the same way Espresso queries by accessible attributes: `onNodeWithText`,
  `onNodeWithContentDescription`, `onNodeWithTag` (paired with `Modifier.testTag(...)` set deliberately in the
  composable, as a last resort when nothing accessible-facing distinguishes the node).
- `composeTestRule.setContent { ... }` replaces the traditional Espresso `ActivityScenario` launch for a
  composable-only test — don't combine both approaches for the same screen.
