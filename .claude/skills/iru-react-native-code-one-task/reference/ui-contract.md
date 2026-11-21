# UI contract — composition, inputs and events, collections, UI tests, screenshots, accessibility

The contract every user interface built with this catalog follows, whatever its platform: web pages and web
components, server-rendered templates, Android (Views or Compose), iOS/iPadOS/macOS/watchOS/visionOS (SwiftUI,
UIKit, AppKit), React Native, Kotlin Multiplatform, Windows desktop (WPF, WinUI 3, Windows Forms) and terminal UIs
(Ratatui, Bubble Tea, Terminal.Gui). This file is repeated verbatim in every coding skill that builds UI; the
skill's own `ui-implementation.md` maps each rule onto that stack's concrete APIs and test tools. When the
repository's own code consistently does something else, follow the code and report the divergence — never
half-apply a rule.

## 1. Vocabulary

| Term | Means | Examples |
|---|---|---|
| **Screen** | A top-level, navigable destination the user lands on. | Android `Activity`/navigation destination, Compose screen, SwiftUI root view of a `NavigationStack` destination, `UIViewController`/`NSViewController`/`NSWindowController` content, web page/route, React Native screen, WPF/WinUI `Page`/`Window`, WinForms `Form`, a full-screen TUI view. |
| **Component** | A reusable, self-contained piece of UI rendered inside a screen or another component. | Android `Fragment`/custom `View`/`@Composable`, SwiftUI `View`, `UIView`/`NSView`, React/Angular/Lit component, Blade/Twig/Django template component, WordPress block, React Native component, WPF/WinUI/WinForms `UserControl`, Ratatui `Widget`, Bubble Tea sub-model, Terminal.Gui `View`. |
| **Flow** | An ordered path through screens that completes one user goal. | sign-in, checkout, onboarding, "create → review → confirm". |
| **UI state** | The immutable value a component or screen renders from. | `OrderCardState`, props, `@Input()`s, a view model's published state. |
| **Event** | A typed notification a component emits when the user asks for an action. | `onSelect(id)`, `(retry)`, `ItemInvoked`, a Bubble Tea `Msg`. |

## 2. Decompose before you build

Given a design (Figma frame, mockup, screenshot, sketch) or only a functional description, decompose it
**before** writing code, top-down, and record the result in the task report (and, from `iru-plan`, in the plan):

1. **Screens** — every distinct destination, and the flows that connect them.
2. **Regions** — the large areas of each screen (header, filter bar, content, footer, side panel).
3. **Components** — the smallest pieces that carry one responsibility. A pattern that appears twice is one
   component with inputs, not two copies. A list, grid, carousel, tab strip or menu is a *collection component*
   plus an *item component* (§5).
4. **For each component**: its UI state (inputs), its events, its visual states (§4), and which existing component
   it reuses or extends.

Write the tree as `Screen › Region › Component` lines, e.g.

```text
OrdersScreen › FilterBar › SearchField (in: query; out: onQueryChange)  [reuses SearchField]
OrdersScreen › Content › OrderList (in: items, loading; out: onOpen(id), onLoadMore)  [new, collection]
OrdersScreen › Content › OrderList › OrderRow (in: OrderRowState; out: onOpen(id))  [new, item]
```

Before creating a component, search the codebase for an existing one that renders the same concept; reuse it,
or add an input/variant to it when it is genuinely the same concept. Build leaves first, then the components that
compose them, then screens, then navigation.

## 3. Composition over inheritance

- Build bigger UI by **nesting** smaller components and by passing content in (children/slots/content
  projection/`@ViewBuilder`/`content: @Composable () -> Unit`/`ContentPresenter`/`DataTemplate`), never by
  subclassing another screen or component to tweak it.
- Subclass a framework type only where the framework demands it (`Activity`, `Fragment`, `UIViewController`,
  `NSView`/`View` with custom drawing, a WinForms `UserControl`) and keep that subclass thin: it hosts composed
  components and forwards state and events.
- **Presentational components** render their UI state and emit events — no data fetching, no navigation, no
  global/singleton state, no platform services (location, storage, network). **Screens** (or their containers)
  own the view model/state holder, call domain services, handle navigation, and pass state down.
- Keep a component small enough to read on one screen: when it passes ~150 lines of view code or ~7 inputs, or
  draws two unrelated things, split it.
- Navigation lives in one place per app (a navigation graph, router table, `NavigationStack` path, file-based
  routes, a coordinator) — never scattered `startActivity`/`push` calls inside components. Documentation derives
  the flows from it.

## 4. Inputs, events and states — the component's API

- **Inputs**: one immutable UI-state value (or a few typed inputs) with stable identity where it is an item.
  Derived values (formatted dates, totals, enabled flags) are computed by the view model, not in the view.
- **Unidirectional data flow is the default**: state flows down, events flow up. An event is named after the
  user's intent (`onOpen(orderId)`, `onRetry`, `onQueryChange(text)`), carries a typed payload, and never leaks a
  raw framework event object (click/mouse/key event) out of anything but a low-level control.
- **Two-way binding** only for form-field-like controls whose value *is* the control (text field, toggle,
  slider, picker), and only through the platform's sanctioned mechanism (hoisted `value` + `onValueChange` in
  Compose and React, Angular `model()`/`[(ngModel)]`, SwiftUI `Binding`, WPF/WinUI `TwoWay` binding to a
  dependency property). Never two-way bind collections or domain objects.
- Every actionable affordance in the design maps to exactly one event; every visible variation maps to an input.
- **Visual states** every component defines where they apply: `default`, `loading`, `empty`, `error`,
  `disabled`, `selected`, `focused`, `pressed`/`hover`, `long-content` (overflow, truncation, wrapping), and for
  data-bound ones `minimal-data` / `typical-data` / `many-items`. A screen defines the same set at screen level.
- **Doc comments** on every component and screen list its purpose, inputs, events and visual states — the
  documentation pages are generated from them (and from the screenshots, §7).
- **Localization and direction**: no hard-coded user-visible strings; layout uses logical start/end edges, never
  left/right; directional icons mirror in right-to-left; nothing assumes a text length.

## 5. Collections — reuse item components through a virtualized container

Whenever a design shows a data-driven or potentially long list — vertical or horizontal lists, grids, carousels,
paged lists, infinite scroll, pop-up/drop-down lists, menus, large numbers of tabs — build it as:

- a **collection component** whose UI state holds the **whole list data** (an immutable list of item UI states,
  each with a stable id, plus paging/loading flags), rendered through the platform's **virtualizing, recycling
  container** so only visible items exist and item views are reused;
- one **item component** per item type: a pure function of one item state, emitting events that carry the item id.

Rules: stable keys (ids, never indexes); diff updates instead of reloading everything; declare item types for
heterogeneous lists; give fixed or estimated item sizes when the container uses them; load pages on demand
through the platform's paging source; keep selection/expansion/scroll position in the view model or saved state,
keyed by id — never inside a recycled item view; create tab contents lazily behind a scrollable tab strip. Never
build every item eagerly (`forEach` inside a non-lazy stack, `map` inside a `ScrollView`, thousands of children in a
`StackPanel`, an unbounded server-rendered loop).

| Platform | Virtualizing container |
|---|---|
| Android Views | `RecyclerView` + `ListAdapter` + `DiffUtil.ItemCallback` (`ConcatAdapter` to combine, Paging 3 `PagingDataAdapter` for paging, `ViewPager2` for pages and many tabs) |
| Jetpack Compose / Compose Multiplatform | `LazyColumn`/`LazyRow`/`LazyVerticalGrid`/`LazyVerticalStaggeredGrid`/`HorizontalPager` with `key` and `contentType`; Paging `collectAsLazyPagingItems()` on Android |
| SwiftUI | `List`, `LazyVStack`/`LazyHStack`/`LazyVGrid` inside `ScrollView`, `ForEach` over `Identifiable` items, `TabView` with `.page` style |
| UIKit | `UICollectionView` + `UICollectionViewDiffableDataSource` + `CellRegistration` + compositional layout (`UITableView` + diffable data source in existing code) |
| AppKit | view-based `NSTableView`/`NSOutlineView` (`makeView(withIdentifier:owner:)` reuse) or `NSCollectionView` with `NSCollectionViewDiffableDataSource` |
| React (web) | `@tanstack/react-virtual` (or the repository's existing virtualizer) with stable `key`s; memoized item component |
| Angular / Ionic | CDK `cdk-virtual-scroll-viewport` + `*cdkVirtualFor` (`trackBy`), `@for (…; track item.id)`; in Ionic the CDK (Angular) or `@tanstack/react-virtual` (React) inside `ion-content` — Ionic has no virtual scroll of its own since v6 — with `ion-infinite-scroll` for paging |
| Hilla / Vaadin | `<VirtualList>` / `<Grid>` with a data provider |
| React Native | `FlatList`/`SectionList` (or `FlashList` when already a dependency) with `keyExtractor`, a memoized `renderItem` component, `getItemLayout` for fixed sizes, `onEndReached` paging |
| WPF | `ListView`/`ListBox`/`DataGrid` with `VirtualizingPanel.IsVirtualizing="True"`, `VirtualizationMode="Recycling"`, `ScrollUnit="Pixel"`, bound to `ObservableCollection<ItemViewModel>` + `DataTemplate` |
| WinUI 3 | `ListView`/`GridView`/`ItemsView`/`ItemsRepeater` (virtualizing by default) with `x:Bind` `DataTemplate` and `x:DataType` |
| Windows Forms | `ListView`/`DataGridView` with `VirtualMode = true` and the retrieve-item callbacks |
| Server-rendered web (Laravel, Django, WordPress, PrestaShop, Next.js server components) | server-side pagination or cursor-based "load more"/infinite scroll returning item partials; one partial/template component per item |
| Ratatui | `List`+`ListState` / `Table`+`TableState`, rendering only the visible window of the data |
| Bubble Tea | `bubbles/list` (paginated, filterable) / `bubbles/table` / `viewport` |
| Terminal.Gui | `ListView`/`TableView` over an `IListDataSource`/table source |

## 6. UI tests

Unit tests for view models, reducers and formatters stay as they are. On top of them, every component and screen
gets UI tests that run against real rendering — on the local desktop or JVM (e.g. Robolectric, desktop
WPF/WinUI/WinForms, macOS), in a simulator/emulator (iOS, Android), or in a real browser (Playwright; Cypress
only where the repository already uses it). A DOM emulator (jsdom/happy-dom) is fine for behaviour tests but never
produces screenshots.

| Level | Renders | Asserts |
|---|---|---|
| Component test | one component in isolation, fed fixture UI states (a component gallery/story host, a test-only fixture route for server-rendered templates, or the screen that hosts it when the platform has no isolated host) | each visual state renders; each event fires with the right payload when the user acts (tap/click/type/keyboard); disabled states do not fire; focus/keyboard navigation where the platform has it |
| Screen test | one screen with a fake view model / fake repositories | screen-level states; events reach the view model; navigation requests are emitted |
| Flow test (e2e) | the real app with real navigation, external services faked | each flow from §2 completes; back/cancel paths work |

Every component and screen test exercises:

- **States**: every visual state from §4 that applies.
- **Data**: fixture sets for `minimal-data`, `typical-data` and `many-items`/`long-content` — long strings, missing
  optional values, large numbers, non-Latin script, and right-to-left text (Arabic or Hebrew).
- **The matrix**: each captured state in **left-to-right and right-to-left** layout × **light and dark** theme
  (narrowed only by `.ui-quality.yml`, §8, or by what the platform genuinely has — watchOS has no light
  appearance; a terminal UI captures the palettes it actually has plus `no-color`, §7). Behaviour assertions need only run once (left-to-right, light);
  screenshots cover the full matrix. Where the platform supports it cheaply, add one high-contrast /
  forced-colors capture and one largest-font (dynamic type / font scale 2.0 / 200% zoom) capture per screen.
- **Determinism**: fixed clock, fixed locale per variant, animations and caret blink disabled, fixed device or
  viewport size, fonts loaded before capture, network faked.

Container-, simulator-, emulator- or OS-dependent UI test stages that cannot run on this machine are reported
**unverified** with the reason — never silently skipped, never counted as passed.

## 7. Screenshots — evidence for review and the source for documentation

Every UI test that renders a state also captures it as a PNG under one repository-wide directory:

```text
<repository root>/.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png
```

- `<kind>`: `component`, `screen` or `flow`.
- `<Id>`: the component/screen type name exactly as in code (`OrderRow`, `OrdersScreen`); for a flow, a
  kebab-case flow name (`checkout`), and the state is `NN-<step>` (`01-cart`, `02-payment`).
- `<state>`: kebab-case, from §4 (`default`, `loading`, `empty`, `error`, `disabled`, `selected`, `long-content`,
  `many-items`, …).
- `<direction>`: `ltr` or `rtl`. `<theme>`: `light`, `dark`, `high-contrast`, or (terminal UIs) `no-color`.
- `<variant>` (optional, repeatable as further `--` segments): the platform when the same `<Id>` renders on
  several platforms (`android`, `ios`, `macos`, `web`), then form factor or size (`phone`, `tablet`, `desktop`,
  `w1280`, `large-text`).

Example: `.ui-screenshots/component/OrderRow/selected--rtl--dark.png`.

- The directory is overridable with the `UI_SCREENSHOTS_DIR` environment variable (absolute path); tests resolve
  it once through a small shared helper (one file, or one common file plus thin per-platform parts where the
  language demands it, e.g. Kotlin `expect`/`actual`), never by hard-coding paths in each test. Where the test
  process cannot write the repository (a sandboxed app, a simulator), the helper also attaches each capture to
  the test result under the same name and the test gate exports it into the directory.
- A screenshot is evidence, not an assertion: the test still asserts behaviour. Pixel-baseline comparison
  (Roborazzi/Paparazzi verify, Playwright `toHaveScreenshot`, swift-snapshot-testing, Verify) is opt-in per
  repository and keeps baselines in that tool's own location; the `.ui-screenshots/` export is produced either way.
- `.ui-screenshots/` is gitignored and cleared at the start of a full UI test run by the test gate, so it always
  holds exactly the current run. The documentation skills copy the screenshots they use into the Antora module's
  `images/ui/` tree (same `<kind>/<Id>/…` layout) — the only committed copies — and discard the rest; when a
  component or screen changes, its old committed images are replaced, and images of removed components deleted.
- Terminal UIs capture the rendered buffer as a text snapshot (`.txt`, same name) and, when a renderer is
  available (VHS, `freeze`), a PNG of it. Terminals do not mirror layout, so a terminal `rtl` capture means
  right-to-left *content* rendered in the normal layout, checking width and truncation. A text capture carries
  no colour, so its themes are `no-color` plus each light/dark palette the UI really implements — never two
  identical light/dark text files.
- Besides captures, the directory may hold one accessibility report per run (`a11y-report.jsonl` or
  `a11y-report.txt`) written in `report` mode so the gate can count violations; it is test evidence, never a
  screenshot.

## 8. Accessibility

Coding defaults, unless `accessibility: none` (see "Opting out and existing code" below):

- Every interactive element has an accessible name, role and state; images have alternative text or are marked
  decorative; icons-only buttons have labels.
- Contrast ≥ 4.5:1 for text and ≥ 3:1 for large text and UI component boundaries, in **both** themes; no
  information conveyed by colour alone; a high-contrast/forced-colors mode stays usable.
- Touch targets ≥ 48dp (Android), 44pt (Apple), 24×24 CSS px (web); visible focus and a logical focus order;
  everything reachable by keyboard on desktop and web.
- Text scales with the user's font size (dynamic type, `sp`, `rem`) without clipping; reduced-motion preferences
  disable non-essential animation; asynchronous changes are announced (live regions, accessibility
  announcements).

Automated checks run inside the UI tests with the platform's tool (axe-core through `@axe-core/playwright` on the
web; on Android the Accessibility Test Framework — Roborazzi's checker or a semantics check (labels, 48dp touch
bounds) on the JVM, Espresso `AccessibilityChecks`/Compose `enableAccessibilityChecks()` on a device;
`performAccessibilityAudit()` in XCUITest; Axe.Windows on Windows; role/label queries plus
`eslint-plugin-react-native-a11y` on React Native; asserted checks — no colour-only information, `NO_COLOR`,
keyboard-only operation, a help line — for terminal UIs, which have no audit tool; accessibility lint rules where
the linter has them) at the level the repository chose:

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: rules apply, checks not run
                            # | none: opted out entirely — none of the rules below apply, no checks, no a11y docs
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

A missing file means the defaults shown (`enforce`) only for a new UI project — a repository with no UI code yet. In a
repository that already has UI code, a missing file means the level was never chosen: the coding skills do not
assume `enforce` but resolve it once, before any UI work, as described in "Opting out and existing code" below.
`off` turns off only the automated
checks — the coding defaults above and the rules below still apply to every new component; only `none` lifts them.

### Platform assistive-technology rules

Unless `accessibility: none`, name the assistive-technology (AT) API of the platform explicitly:

- **Web** (React, Angular, Ionic, Hilla, Next.js, server-rendered templates): native semantic elements first
  (`h1`…`h6`, `nav`, `main`, `article`, `section`, `button`, `a`, `ul`/`li`) before ARIA roles; every interactive
  control is keyboard-operable; never hide a focusable element with `display:none` or `aria-hidden`; accessible
  names come from `aria-label`/`aria-labelledby`, `alt`, or an associated `<label for>`.
- **Android** (TalkBack): `contentDescription` on views, Compose `Modifier.semantics { contentDescription = … }`
  (or `clearAndSetSemantics` for merged content), `importantForAccessibility` to hide purely decorative views,
  touch targets ≥ 48dp.
- **Apple** (VoiceOver): `accessibilityLabel`, `accessibilityValue`, `accessibilityHint` and traits; SwiftUI
  `.accessibilityLabel(_:)`, `.accessibilityValue(_:)`, `.accessibilityHint(_:)`, `.accessibilityAddTraits(_:)`;
  Dynamic Type for every text style.
- **Windows** (Narrator): `AutomationProperties.Name` / `HelpText` / `LabeledBy`, UI Automation peers for custom
  controls, a logical tab order (`TabIndex`, `IsTabStop`).
- **React Native**: `accessibilityLabel`, `accessibilityRole`, `accessibilityState`, `accessibilityHint`.
- **Terminal UIs** (unchanged): no information by colour alone, honour `NO_COLOR`, keyboard-only operation.

### Visual comfort

- Required: legible, user-adjustable font sizes (dynamic type, `sp`, `rem`, no fixed pixel text that ignores the
  system setting) and the contrast ratios above.
- Preferred, not gated (no automated check fails on their absence): a reduced-motion mode
  (`prefers-reduced-motion`, Android animator duration scale, `accessibilityReduceMotion` /
  `UIAccessibility.isReduceMotionEnabled`, `SystemParameters.ClientAreaAnimation`); light and dark themes; a
  high-contrast mode (`forced-colors`, Windows High Contrast themes, iOS Increase Contrast).

### Opting out and existing code

`none` is chosen at setup, never inferred later. A repository that already contains UI code is scanned first —
`iru-explore` reports an `Accessibility: present|partial|absent` line (detection rules in its
`reference/accessibility-detection.md`) and the setup question is pre-selected from it, so a project does not end
up half-compliant (old non-compliant code next to new compliant code). Never mix: when the level is `none`,
generated code, tests and docs omit the AT-specific additions (accessible names, ARIA, semantics, AT lint and audit
tooling, reduced-motion guards added only for accessibility) and the UI docs index says so once; a skill never
silently flips the level in either direction.

When `.ui-quality.yml` is missing in a repository that already has UI code (it predates the catalog's setup, or the
file was deleted), the coding skill resolves the level before its first UI task: it reuses the `Accessibility:` line
of an `iru-explore` report (in the plan's "Current code state" or the conversation) or applies the same detection
rules, asks the user once with the setup's pre-selection — `present` → `enforce`, `partial` → `report` with a
mixed-compliance warning, `absent` → `none` with a half-compliance warning — and writes the answer (with the default
`directions` and `themes`) to `.ui-quality.yml`, so every later skill and test gate reads the same level. Test and
gate skills run after that and keep reading a missing file as `enforce`.

