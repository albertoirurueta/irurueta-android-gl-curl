# UI implementation (React Native)

Read with `ui-contract.md` whenever the task adds or changes a screen, component, list or other UI. The contract
says *what* every UI in this catalog does; this file maps it onto React Native's real APIs for both toolchains
(`expo` with Expo Router or a single root component, `metro` with React Navigation). Where the repository already
does something else consistently, follow it and report the divergence (contract preamble).

## Screens, components and where they live

| Contract term | React Native |
|---|---|
| **Screen** | A function component registered as a navigation destination: an Expo Router route file (`app/orders/index.tsx`) or a React Navigation screen (`<Stack.Screen name="Orders" component={OrdersScreen} />` / static config). Route files stay thin: they read params and render `<OrdersScreen />` from `src/features/<feature>/screens/`. |
| **Component** | A function component with a `Readonly<…Props>` props interface, in `src/features/<feature>/components/` (feature-specific) or `src/components/` (shared). Never a class component in new code. |
| **Flow** | A path through routes/screens — derived from the Expo Router `app/` tree or the root navigator, the single navigation definition (contract §3). Tested by a Maestro flow (or Detox test). |
| **UI state** | A `Readonly` TypeScript type per component (`OrderRowState`), produced by the screen's view model hook. |
| **Event** | A typed callback prop named after the intent: `onOpen(orderId: string)`, `onRetry()`, `onQueryChange(text: string)`. Never pass `GestureResponderEvent` up. |

**Screen = container + view.** The screen component owns the view model — a hook such as
`useOrdersViewModel()` returning `{ state, actions }` (built on the project's TanStack Query/store, see
`state-and-data.md`) — handles navigation (`router.push`/`navigation.navigate`) and passes state down to a
presentational `OrdersView` that only renders props and calls callbacks. That split is what makes the screen
testable with a fake view model and renderable in the UI gallery (below).

## Composition, inputs and events

- Compose by nesting and by passing content in: `children`, typed slot props (`header?: ReactNode`,
  `renderAction?: (item: OrderRowState) => ReactNode`), and the list slots `ListHeaderComponent`/`ListEmptyComponent`.
  No HOC chains that change behaviour and never subclass a component; the only "framework subclasses" are native
  views written in Kotlin/Swift (`native-modules.md`), kept thin.
- Presentational components import no API client, store, navigation hook (`useRouter`/`useNavigation`),
  `Linking`, permissions or storage — screens do.
- Props: one `state` object (or a few primitives) plus `on…` callbacks; derived strings (formatted price, relative
  date) come from the view model already formatted and localized.
- Two-way binding exists only as **controlled inputs**: `<TextInput value={query} onChangeText={onQueryChange} />`,
  `<Switch value={enabled} onValueChange={onToggle} />` with the value hoisted to the owner. Never mirror a prop into
  `useState` and never let a child mutate a list or domain object.
- Visual states map to props, not internal flags: `loading`, `error`, `empty`, `disabled`, `selected`. Keep a
  **fixtures file** next to each component (`OrderRow.fixtures.ts`) exporting one state value per visual state and
  data set (`default`, `selected`, `disabled`, `long-content`, `rtl-text` …); the RNTL tests and the UI gallery both
  import it, so every documented state is rendered somewhere.
- Directionality: logical edges only (`marginStart`, `paddingEnd`, `start`/`end`, `borderStartWidth`),
  `flexDirection: "row"` (flips automatically in RTL), `textAlign: "auto"`/`writingDirection` left to the platform;
  mirror directional icons with `transform: [{ scaleX: I18nManager.isRTL ? -1 : 1 }]` or an RTL-aware icon. No
  hard-coded `left`/`right` and no user-visible string literals (use the project's i18n layer).
- TSDoc on every component and screen lists purpose, props, events and visual states (`tsdoc.md`).

## Collections — `FlatList`/`SectionList` + a memoized item component

The collection's view model holds the whole list (immutable items with stable ids + paging flags); the list renders
through `FlatList` (`SectionList` for grouped data; `@shopify/flash-list` only when it is already a dependency — same
shape, `estimatedItemSize` on v1, none needed on v2). Never `ScrollView` + `.map()` over data that can grow
(`ui-and-platform.md`).

```tsx
/** UI state of one order row. */
export type OrderRowState = Readonly<{ id: string; title: string; total: string; selected: boolean }>;

/** Props of {@link OrderRow}. */
export type OrderRowProps = Readonly<{ state: OrderRowState; onOpen: (id: string) => void }>;

const ROW_HEIGHT = 72;

/** One order in {@link OrderList}; states: default, selected, long-content. */
export const OrderRow = memo(function OrderRow({ state, onOpen }: OrderRowProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: state.selected }}
      onPress={() => onOpen(state.id)}
      style={styles.row}
    >
      <Text numberOfLines={1} style={styles.title}>{state.title}</Text>
      <Text>{state.total}</Text>
    </Pressable>
  );
});

/** UI state of {@link OrderList}: the whole list plus paging flags. */
export type OrderListState = Readonly<{
  items: readonly OrderRowState[];
  loadingMore: boolean;
  endReached: boolean;
}>;

/** Virtualized order list; states: empty, typical-data, many-items, loading (more). */
export function OrderList({ state, onOpen, onLoadMore, emptyLabel, loadingLabel }: Readonly<{
  state: OrderListState;
  onOpen: (id: string) => void;
  onLoadMore: () => void;
  emptyLabel: string;
  loadingLabel: string;
}>) {
  const renderItem = useCallback<ListRenderItem<OrderRowState>>(
    ({ item }) => <OrderRow state={item} onOpen={onOpen} />,
    [onOpen],
  );
  return (
    <FlatList
      data={state.items}
      keyExtractor={keyExtractor}
      renderItem={renderItem}
      getItemLayout={getItemLayout}
      onEndReached={state.endReached || state.loadingMore ? undefined : onLoadMore}
      onEndReachedThreshold={0.5}
      ListEmptyComponent={<Text>{emptyLabel}</Text>}
      ListFooterComponent={state.loadingMore ? <ActivityIndicator accessibilityLabel={loadingLabel} /> : null}
    />
  );
}

const keyExtractor = (item: OrderRowState): string => item.id;
const getItemLayout = (_: ArrayLike<OrderRowState> | null | undefined, index: number) => ({
  length: ROW_HEIGHT,
  offset: ROW_HEIGHT * index,
  index,
});
```

- **Stable keys**: `keyExtractor` returns the domain id — never the index.
- **Diffing**: the view model returns a new array with unchanged item objects kept by reference (TanStack Query's
  structural sharing does this; with a store, update immutably), so `React.memo` skips unchanged rows. Pass
  `extraData` only for state the rows read from outside `data`.
- **Paging**: `useInfiniteQuery` (TanStack) or the project's pager feeds `items` and `loadingMore`; `onEndReached`
  calls `fetchNextPage`. Pull-to-refresh: `refreshing` + `onRefresh`.
- **Selection/expansion** live in the view model keyed by id; scroll position survives via navigation state, not row
  state. Many tabs: a scrollable tab bar (`@react-navigation/material-top-tabs` with `lazy`, or Expo Router tabs)
  whose screens mount lazily.
- Heterogeneous lists: `SectionList`, or one `renderItem` switching on an item `type` discriminant with one memoized
  component per type (FlashList: `getItemType`).

## UI tests — where each level runs

| Level | Tool | Runs on | Covers |
|---|---|---|---|
| Component | Jest + React Native Testing Library (`testing.md`) | Node (no rendering engine — never screenshots) | every state from the fixtures renders; each event fires with the item id via `userEvent`; disabled does not fire; role/label queries |
| Screen | Jest + RNTL, the presentational view fed fixture state, or the screen with its view model hook mocked at the module boundary | Node | loading/empty/error/data states; actions reach the view model; navigation calls emitted (`expo-router` `router` spy or a mocked `navigation` prop) |
| Visual capture (component + screen) | Maestro flow over the **UI gallery** (below) | booted iOS simulator / Android emulator | every state × `.ui-quality.yml` directions × themes, as PNGs |
| Flow | Maestro flows (or Detox, when the project uses it) over the real app, network faked | booted simulator/emulator + Metro on 8081 | each flow from the decomposition, back/cancel paths, one capture per step |

Behaviour assertions run once (LTR, light) in Jest. Device stages that can't run here (no simulator/emulator, no
Maestro) are **unverified** — `iru-react-native-code-one-task-group` runs them serialized behind
`.integration-test.lock`; this skill only writes them.

Example component test (RNTL 14, async API — match the installed major):

```tsx
import { render, screen, userEvent } from "@testing-library/react-native";
import { OrderRow } from "./OrderRow";
import { orderRowFixtures } from "./OrderRow.fixtures";

test.each(Object.entries(orderRowFixtures))("renders the %s state", async (_name, state) => {
  await render(<OrderRow state={state} onOpen={jest.fn()} />);
  expect(screen.getByRole("button", { name: new RegExp(state.title) })).toBeOnTheScreen();
});

test("emits onOpen with the order id", async () => {
  const onOpen = jest.fn();
  await render(<OrderRow state={orderRowFixtures.default} onOpen={onOpen} />);
  await userEvent.setup().press(screen.getByRole("button"));
  expect(onOpen).toHaveBeenCalledWith(orderRowFixtures.default.id);
});
```

A list's `many-items` fixture (hundreds of rows) is rendered in Jest too: RNTL's `FlatList` renders only the initial
window, so assert on the first rows and `onEndReached` (`fireEvent.scroll` or `user.scrollTo`), never on the count of
every item.

## The UI gallery — a dev/test-only screen that renders one entry in one state

Device captures of components and screens come from a gallery the production bundle never contains:

- `src/dev/ui-gallery/registry.tsx` — `Record<Id, { kind: "component" | "screen"; states: Record<state, () =>
  ReactElement> }>` built from each component's fixtures (screens render their presentational view with fixture
  state, never a live view model).
- `src/dev/ui-gallery/UiGalleryHost.tsx` (written by `iru-setup-react-native-app`; reuse it when present) — parses `<scheme>://ui-gallery?id=<Id>&state=<state>&dir=<ltr|rtl>&theme=<light|dark>`,
  applies the theme with `Appearance.setColorScheme(theme)` (so `useColorScheme()` and the app's theme follow; RN
  0.88 resets with `"auto"` — match the installed type, older versions take `null`/`"unspecified"`), wraps the entry
  in `<View style={{ direction: dir }}>` (Yoga mirrors rows and logical edges), and shows a `testID="ui-gallery-ready"`
  root only after both are applied.
- **Excluded from production by construction**: it is reached only through a `__DEV__` constant (Metro inlines it
  and folds dead branches in production builds, dropping the `require`):
  - no-router entry (blank Expo template, metro `index.js`): register
    `__DEV__ ? require("./src/dev/ui-gallery").withUiGallery(App) : App`, a wrapper that renders the gallery when
    `Linking.getInitialURL()`/the `url` event carries a `ui-gallery` link and `<App />` otherwise;
  - Expo Router: a route `app/ui-gallery.tsx` whose default export is
    `__DEV__ ? require("@/dev/ui-gallery").UiGalleryRoute : NotFound` (reading `useLocalSearchParams()`), optionally
    hidden behind `<Stack.Protected guard={__DEV__}>`;
  - React Navigation: add a `UiGallery` screen and its `linking.config.screens` entry only when `__DEV__`.
  Confirm the exclusion once per project: build a production bundle (`npx expo export`, or
  `npx react-native bundle --dev false`) and grep it for `ui-gallery-ready` — it must not appear.
- Deep links need the app's URL scheme: expo `expo.scheme` in app config; metro an `intent-filter` with
  `<data android:scheme=…>` on `MainActivity`, `CFBundleURLTypes` in `Info.plist`, and the `RCTLinkingManager`
  forwarding (already in the React Native 0.88 template's `SceneDelegate.swift`; older `AppDelegate` templates need it
  added — React Native "Linking" docs). Adding a scheme is a config/native change — a blocker
  to report if the task didn't ask for it and the project has none (SKILL.md Step 5).

## Screenshot helper — one subflow, one file name

The shared helper is the Maestro subflow `.maestro/subflows/ui-capture.yaml`; it is the only place a file name is
built. The test gate passes `UI_SCREENSHOTS_DIR` (already resolved to `$UI_SCREENSHOTS_DIR` or
`<repo root>/.ui-screenshots`) with `-e`; by hand: `maestro test -e UI_SCREENSHOTS_DIR="$(git rev-parse
--show-toplevel)/.ui-screenshots" .maestro/ui/`.

```yaml
# .maestro/subflows/ui-capture.yaml — UI contract §7 screenshot helper (KIND, ID, STATE, DIR, THEME from runFlow env)
appId: <app-id>
---
- openLink: "<app-scheme>://ui-gallery?id=${ID}&state=${STATE}&dir=${DIR}&theme=${THEME}"
- tapOn:            # iOS asks "Open in <App>?" for custom schemes
    text: "Open"
    optional: true
- extendedWaitUntil:
    visible:
      id: "ui-gallery-ready"
    timeout: 15000
- waitForAnimationToEnd
- takeScreenshot: "${UI_SCREENSHOTS_DIR}/${KIND}/${ID}/${STATE}--${DIR}--${THEME}--${maestro.platform}"
```

One flow per component/screen in `.maestro/ui/<Id>.yaml` (tag `ui-gallery`; `.maestro/config.yaml` lists
`flows: ["*", "ui/*"]` so the suite picks them up) calls it once per state × direction × theme:

```yaml
appId: <app-id>
tags: [ui-gallery]
---
- launchApp
- runFlow: { file: ../subflows/ui-capture.yaml, env: { KIND: component, ID: OrderRow, STATE: default, DIR: ltr, THEME: light } }
- runFlow: { file: ../subflows/ui-capture.yaml, env: { KIND: component, ID: OrderRow, STATE: default, DIR: rtl, THEME: dark } }
# … every state × [ltr, rtl] × [light, dark] from .ui-quality.yml
```

The platform (`ios`/`android`) is always appended as a `<variant>` because the same `<Id>` renders on both. Flow
captures (tag `ui-flow`) take `- takeScreenshot: "${UI_SCREENSHOTS_DIR}/flow/<flow-name>/NN-<step>--ltr--${UI_THEME}--${maestro.platform}"`
after each step; the gate runs them once per theme, switching the device (below) and passing `UI_THEME`.

**Detox** projects use `e2e/support/ui-screenshots.ts` instead — same name pattern, written from Node:

```ts
import { execSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { device } from "detox";

const SEGMENT = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

/** Resolves `UI_SCREENSHOTS_DIR`, else `<repository root>/.ui-screenshots`. */
export function uiScreenshotsDir(): string {
  const fromEnv = process.env.UI_SCREENSHOTS_DIR;
  if (fromEnv) return resolve(fromEnv);
  return join(execSync("git rev-parse --show-toplevel", { encoding: "utf8" }).trim(), ".ui-screenshots");
}

/** Captures the device screen as `<kind>/<id>/<state>--<dir>--<theme>--<platform>[--<variant>…].png`. */
export async function captureUi(
  kind: "component" | "screen" | "flow", id: string, state: string,
  dir: "ltr" | "rtl", theme: "light" | "dark" | "high-contrast", ...variants: string[]
): Promise<string> {
  for (const segment of [state, ...variants]) {
    if (!SEGMENT.test(segment)) throw new Error(`Not a kebab-case screenshot segment: ${segment}`);
  }
  const name = [state, dir, theme, device.getPlatform(), ...variants].join("--");
  const temp = await device.takeScreenshot(`${id}--${name}`); // Detox returns the temporary PNG's path
  const target = join(uiScreenshotsDir(), kind, id, `${name}.png`);
  mkdirSync(dirname(target), { recursive: true });
  copyFileSync(temp, target);
  return target;
}
```

## Switching theme, direction, contrast and text size

| What | In the gallery (components, screens) | On the device (flows) |
|---|---|---|
| Dark/light | `theme=` link param → `Appearance.setColorScheme` | iOS `xcrun simctl ui <udid> appearance dark\|light`; Android `adb shell cmd uimode night yes\|no` (restore afterwards) |
| RTL | `dir=` link param → `direction` style | `I18nManager.allowRTL(true); I18nManager.forceRTL(true)` then a reload (`DevSettings.reload()` in dev / `expo-updates` `reloadAsync`), or an RTL device locale; code that reads `I18nManager.isRTL` only flips this way |
| Large text | a `--large-text` variant captured after `xcrun simctl ui <udid> content_size accessibility-extra-extra-extra-large` / `adb shell settings put system font_scale 2.0` | same |
| High contrast | iOS `xcrun simctl ui <udid> increase_contrast enabled` (one `--high-contrast` theme capture per screen where cheap); Android has no stable shell toggle — skip and say so | same |

In Jest, theme and direction are props of the provider the app already has (render inside its `ThemeProvider` with
`scheme="dark"`), not device settings.

## Accessibility checks and `.ui-quality.yml`

- **Lint** — `eslint-plugin-react-native-a11y` (3.5.1, Formidable; last release November 2024, peer range stops at
  ESLint 8 but its rules run unchanged on ESLint 9/10 flat config — install with an npm `overrides` entry
  `{ "eslint-plugin-react-native-a11y": { "eslint": "$eslint" } }`). Its `all` preset minus
  `has-accessibility-hint` (which demands a hint on every label, contradicting `ui-and-platform.md`). The ESLint
  config reads the level from `.ui-quality.yml` (searched upward from the config file): `enforce` → `"error"`,
  `report` → `"warn"`, `off` → rules off. It checks the `accessibility*` props; the newer `role`/`aria-*` props
  are not validated by it.
- **Tests** — RNTL role/label queries (`getByRole("button", { name })`) are accessibility checks by construction:
  an element without a role or name can't be found. Assert `accessibilityState` with `toBeSelected()`,
  `toBeDisabled()`, `toBeChecked()`, `toBeExpanded()` and labels with `toHaveAccessibleName()`. These run whatever the
  level, because they are behaviour.
- **Native audits are limited**: Maestro and Detox have no accessibility audit; React Native exposes no
  axe-equivalent. Where a metro app has an XCUITest target, `XCUIApplication().performAccessibilityAudit()` (iOS 17+)
  is optional extra coverage; otherwise contrast and touch-target size are checked by review against the contract
  §8 defaults (and Android Accessibility Scanner / Xcode Accessibility Inspector by hand). Report this limitation;
  never claim an automated contrast check ran.
- Level semantics: `enforce` → lint errors fail the group's quality gate; `report` → listed in the summary;
  `off` → lint rules off, the coding defaults still apply.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Every pressable has `accessibilityRole`, `accessibilityLabel` (icon-only) and `accessibilityState` (`selected`, `disabled`, `checked`); group related content with `accessible`; hide decorative elements (`importantForAccessibility="no"`, `accessibilityElementsHidden`).
- Leave `allowFontScaling` on and test with a large `fontScale`; use `AccessibilityInfo` (`isReduceMotionEnabled`, `announceForAccessibility`) for motion and announcements; targets >= 44pt/48dp.
- Screen readers: VoiceOver (iOS) and TalkBack (Android); `eslint-plugin-react-native-a11y` is the automated check.

## Sources

- React Native: FlatList <https://reactnative.dev/docs/flatlist>, SectionList <https://reactnative.dev/docs/sectionlist>,
  optimizing FlatList <https://reactnative.dev/docs/optimizing-flatlist-configuration>, Appearance
  <https://reactnative.dev/docs/appearance>, I18nManager <https://reactnative.dev/docs/i18nmanager>, layout props
  (`direction`) <https://reactnative.dev/docs/layout-props>, Linking <https://reactnative.dev/docs/linking>,
  accessibility <https://reactnative.dev/docs/accessibility>
- Expo Router: protected routes <https://docs.expo.dev/router/advanced/protected/>, linking
  <https://docs.expo.dev/linking/into-your-app/>; React Navigation deep linking
  <https://reactnavigation.org/docs/deep-linking>
- React Native Testing Library: queries <https://callstack.github.io/react-native-testing-library/docs/api/queries>,
  Jest matchers <https://callstack.github.io/react-native-testing-library/docs/api/jest-matchers>
- Maestro: `takeScreenshot` <https://docs.maestro.dev/api-reference/commands/takescreenshot>, `openLink`
  <https://docs.maestro.dev/api-reference/commands/openlink>, `runFlow` <https://docs.maestro.dev/api-reference/commands/runflow>,
  workspace config <https://docs.maestro.dev/cli/test-suites-and-reports>, JavaScript `maestro` object
  <https://docs.maestro.dev/advanced/javascript>
- Detox `device.takeScreenshot()` <https://wix.github.io/Detox/docs/api/screenshots>
- `eslint-plugin-react-native-a11y` <https://github.com/FormidableLabs/eslint-plugin-react-native-a11y>
- `xcrun simctl ui` (`xcrun simctl help ui`); Android `cmd uimode` <https://developer.android.com/develop/ui/views/theming/darktheme#test>
- FlashList <https://shopify.github.io/flash-list/>; TanStack Query infinite queries
  <https://tanstack.com/query/latest/docs/framework/react/guides/infinite-queries>
