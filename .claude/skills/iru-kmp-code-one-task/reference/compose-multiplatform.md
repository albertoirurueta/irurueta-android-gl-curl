# Compose Multiplatform (`ui: compose`)

Conventions for shared UI written with Compose Multiplatform in the KMP module's `commonMain` (`shared/` in the
catalog scaffold, `composeApp/` in the older wizard layout). Everything in `code-style.md`, `kdoc.md`, and
`testing.md` still applies. Read `native-ui.md` instead when the screen is native per platform (`ui: native`).

Detect the setup once: the module applies `org.jetbrains.compose` and `org.jetbrains.kotlin.plugin.compose`;
dependencies are either the `compose.*` accessors (`compose.runtime`, `compose.material3`, `compose.components.resources`
— older projects) or direct coordinates from the version catalog (`org.jetbrains.compose.runtime:runtime`,
`org.jetbrains.compose.material3:material3`, `org.jetbrains.compose.components:components-resources`). Match whichever
the module uses when a task genuinely needs another Compose artifact (and report it — it's a new dependency).

## Composables: stateless, hoisted, `Modifier` first optional parameter

Same conventions as Jetpack Compose (the APIs are the same `androidx.compose.*` packages):

- A composable receives the data it renders and callbacks for events; it holds only transient visual state.
  Screen-level state lives in a `ViewModel` (`shared-logic.md`) and is collected with
  `val state by viewModel.state.collectAsStateWithLifecycle()` (multiplatform `lifecycle-runtime-compose`) or
  `collectAsState()` if the module doesn't have the lifecycle artifact.
- Split a screen into a stateful entry (`ProfileRoute(viewModel)`) and a stateless body (`ProfileScreen(state, onEvent,
  modifier)`); previews and UI tests target the stateless one.
- `modifier: Modifier = Modifier` is the first optional parameter and is applied to the root layout node.
- `remember`/`rememberSaveable` for local state; `derivedStateOf` for values derived from other state;
  `LaunchedEffect`/`DisposableEffect` for side effects — never launch coroutines in the composable body.
- Read colors/typography/shapes from `MaterialTheme`; wrap the root (`App()`) and every preview in the project's
  theme composable, which takes `darkTheme: Boolean = isSystemInDarkTheme()` so tests can force either theme.
- Screen/component structure, inputs and events, and lists follow `ui-contract.md` as mapped in
  `ui-implementation.md`: decompose first, components never see the view model, and every data-driven list, grid,
  pager or tab set is a `Lazy*`/`HorizontalPager` with `key` and `contentType` — never `forEach` in a scrolling
  `Column`.

## Platform differences inside shared UI

- Shared UI must not import `android.*`/`androidx.activity.*` or `platform.UIKit.*`. Platform-specific UI pieces
  (a native map, a camera preview, a share sheet, haptics) are reached through an interface or `expect`
  composable/function with actuals in `androidMain`/`iosMain` — `@Composable expect fun PlatformMapView(...)` is
  legitimate when the platform implementation is itself UI.
- `LocalContext` exists only on Android; don't use it in `commonMain`. Platform services come in through DI.
- Back handling: use the multiplatform back-handler API the project's Compose/navigation version provides, not
  `androidx.activity.compose.BackHandler` in common code.
- Insets/safe areas: `WindowInsets.safeDrawing`/`Modifier.safeDrawingPadding()` work on both platforms; the iOS
  host must not add its own safe-area padding on top (`ignoresSafeArea()` on the SwiftUI side, see below).

## Resources — the generated `Res` class

- Files live under `<module>/src/commonMain/composeResources/`: `values/strings.xml` (and `values-<lang>/` for
  translations), `drawable/` (PNG, JPEG, WebP, Android vector XML, SVG on non-Android targets), `font/`, `files/`
  (raw files read with `Res.readBytes("files/...")`).
- The Gradle plugin generates a `Res` object, by default in package `<rootProject name>.<module name>.generated.resources`
  (verified: `import cmpsample.shared.generated.resources.Res` for root project `cmpsample`, module `shared`), and one
  accessor per resource (`Res.string.greeting`, `Res.drawable.logo`). The package and visibility can be changed with
  `compose.resources { packageOfResClass = "…"; publicResClass = true }` — import from whatever the module
  configured; never edit or copy the generated code under `build/`.
- Use `stringResource(Res.string.x)`, `stringResource(Res.string.x, arg)`, `pluralStringResource(...)`,
  `painterResource(Res.drawable.x)`, `Font(Res.font.x)` in composables; the `suspend fun getString(Res.string.x)` form
  outside composition (e.g. in a `ViewModel` — though returning a resource key and resolving it in UI is cleaner).
- No user-facing literal strings in composables — every visible string is a resource so it can be translated.
  Accessibility labels (`contentDescription`) too.
- Resource names: `snake_case`, valid Kotlin identifiers.

## Navigation

Use what the module already uses:

- **Jetpack Navigation for Compose Multiplatform** (`org.jetbrains.androidx.navigation:navigation-compose`):
  type-safe routes as `@Serializable` objects/data classes (`@Serializable data class Profile(val userId: String)`),
  `NavHost(navController, startDestination = Home) { composable<Home> { … }; composable<Profile> { … } }`,
  `navController.navigate(Profile(id))`, arguments via `backStackEntry.toRoute<Profile>()`. Route arguments are small
  identifiers, never whole objects.
- Navigation 3 or a third-party library (Voyager, Decompose) if the project already uses one — follow its patterns;
  don't mix navigation libraries.
- Screens receive navigation as callbacks (`onOpenProfile: (String) -> Unit`), not the `NavController`, so they stay
  previewable and testable.

## Previews

- `@Preview` on a `private` zero-argument composable rendering the stateless screen with sample data inside the theme.
  In current Compose Multiplatform `androidx.compose.ui.tooling.preview.Preview` works in `commonMain` (with the
  `ui-tooling-preview` artifact; older projects use `org.jetbrains.compose.ui.tooling.preview.Preview`) — use the
  import the module already uses; add a preview only where the module has the tooling dependency.
- Previews are not tests; they don't count toward coverage — keep logic out of them.

## The iOS entry point — `MainViewController`

- `iosMain` exposes `fun MainViewController(): UIViewController = ComposeUIViewController { App() }` (verified to
  compile). Swift wraps it:

  ```swift
  struct ComposeView: UIViewControllerRepresentable {
      func makeUIViewController(context: Context) -> UIViewController {
          MainViewControllerKt.MainViewController()
      }
      func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
  }

  struct ContentView: View {
      var body: some View {
          ComposeView().ignoresSafeArea()   // Compose handles insets itself
      }
  }
  ```
- The top-level function is exposed to Swift on a class named after its file (`MainViewController.kt` →
  `MainViewControllerKt`) — keep the file name stable or add `@ObjCName` (`ios-interop.md`).
- `ComposeUIViewController { … }` accepts a configure block for options; dependencies the shared UI needs from iOS
  (e.g. a Swift-implemented service) are passed as parameters to `MainViewController(...)`, typed with Kotlin
  interfaces that Swift implements.
- On Android, `androidApp`'s `MainActivity` calls `setContent { App() }` — the same root composable.

## Interop with UIKit / SwiftUI

- **Native iOS view inside Compose**: `UIKitView(factory = { MKMapView() }, modifier = …, update = { … })` in
  `iosMain` (behind an `expect`/interface boundary as above), or `UIKitViewController` for a view controller. Size it
  with a `Modifier`; it doesn't measure itself.
- **SwiftUI inside Compose**: Kotlin can't reference SwiftUI types. Swift creates a `UIHostingController(rootView:)`
  and hands it to Kotlin as a `UIViewController` through a factory parameter of `MainViewController(...)`; Kotlin hosts
  it with `UIKitViewController`.
- **Compose inside SwiftUI**: the `UIViewControllerRepresentable` above.
- Keep interop surfaces small and stateless; state flows from the shared `ViewModel`, not through ad hoc callbacks
  between Swift and Compose.

## Accessibility

- Every non-decorative image/icon has a `contentDescription` (from resources); decorative ones pass `null`.
- Clickable custom components use `Modifier.clickable(role = Role.Button, onClickLabel = …)` or `semantics { }` so
  TalkBack and VoiceOver announce them — Compose Multiplatform maps semantics to iOS accessibility (VoiceOver), so the
  same semantics serve both platforms.
- Touch targets ≥ 48dp; text scales with the user's font size (use `sp` from the theme typography, don't fix heights
  that clip scaled text).
- `Modifier.testTag("…")` is for tests only; it's not an accessibility label.
- UI tests run `checkUiAccessibility()` at the `.ui-quality.yml` level (`ui-implementation.md`).

## Testing Compose Multiplatform UI

- Common UI tests use `runComposeUiTest { setContent { … }; onNodeWithText(…).performClick(); … }`
  (`androidx.compose.ui.test.v2.runComposeUiTest` — the non-`v2` one is deprecated in Compose Multiplatform 1.12) from
  `org.jetbrains.compose.ui:ui-test` in `commonTest`, with `@OptIn(ExperimentalTestApi::class)`. Query by
  semantics (`onNodeWithText`, `onNodeWithContentDescription`), `onNodeWithTag` as a last resort. **Read `testing.md` before
  placing one**: verified locally, a `commonTest` Compose UI test passes on the iOS simulator but fails on the
  Android host-test target unless the module runs its host tests under Robolectric through the `UiTest` base class
  (`ui-implementation.md`) — without that wiring such tests go in `iosTest`.
- Every UI test also captures its states across the `.ui-quality.yml` matrix into `.ui-screenshots/` with the
  helper and runs the accessibility check — `ui-implementation.md`.
- Test the stateless screen with fake state and recorded callbacks; test the `ViewModel` separately with plain
  `runTest` + Turbine — that's where most logic lives and it's faster.
