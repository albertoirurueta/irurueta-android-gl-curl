# UI and platform

Read when the task adds or changes a screen or component's layout, styling, lists, images, animations,
accessibility, or platform-specific behaviour. For how a screen or component is *structured* (decomposition,
container vs presentational view, state props and `on…` events, fixtures), how collections are built, and how UI is
tested, captured and accessibility-checked, `ui-contract.md` + `ui-implementation.md` are the rule; this file covers
styling and platform detail.

## Styling

- **`StyleSheet.create({...})`** at module scope, below the component — typed, and `react-native/no-inline-styles`
  (CLI ESLint config) warns on inline objects. Inline style only for a value computed from props/state, merged as an
  array: `style={[styles.row, { paddingTop: insets.top }]}`.
- Follow the project's existing approach if it isn't plain `StyleSheet` (NativeWind/Tailwind `className`, Unistyles,
  Tamagui, restyle, a theme hook such as the Expo template's `useTheme()`); never add a second one.
- Colors, spacing, and typography from the project's theme/constants (`constants/theme.ts` in the Expo template),
  not literals. Support dark mode the way the project does (`useColorScheme()`, theme provider) — a new hardcoded
  `#fff` background is a dark-mode bug. Contrast ≥ 4.5:1 (text) / 3:1 (large text, control borders) in **both**
  themes (`ui-contract.md` §8).
- Logical edges only — `marginStart`/`paddingEnd`/`start`/`end`, never `left`/`right` or `marginLeft` — so layouts
  mirror in right-to-left (`ui-implementation.md`, "Composition, inputs and events").
- Flexbox defaults differ from the web: `flexDirection: "column"`, `alignContent: "flex-start"`, `flexShrink: 0`
  — don't port web CSS assumptions. Dimensions are density-independent pixels; use `useWindowDimensions()` (reacts to
  rotation/split-screen), not `Dimensions.get` captured once at module load.
- Text must be inside `<Text>` — a bare string in a `<View>` crashes. Respect font scaling (don't set
  `allowFontScaling={false}` unless the task demands it; test layouts with large text in mind — use `maxFontSizeMultiplier`
  sparingly).

## Safe areas and system UI

- Use **`react-native-safe-area-context`** (`SafeAreaProvider` at the root — present in both templates — and
  `useSafeAreaInsets()` or its `SafeAreaView` with explicit `edges`). React Native's own `SafeAreaView` is
  deprecated (iOS-only, no Android support); don't use it in new code.
- Android 15+ forces edge-to-edge (`edgeToEdgeEnabled=true` in the CLI template's `gradle.properties`; default in Expo):
  content draws under the status and navigation bars, so bottom insets matter on Android too.
- Status bar: `expo-status-bar`'s `<StatusBar style>` (expo) or React Native's `<StatusBar barStyle>` (metro) —
  whichever the project uses. Keyboard: `KeyboardAvoidingView` (`behavior="padding"` on iOS) or the project's
  `react-native-keyboard-controller`; make sure inputs near the bottom stay visible.

## Platform-specific behaviour

- Platform files (`Foo.ios.tsx` / `Foo.android.tsx` / `Foo.tsx`, plus `.web.tsx` for Expo web) for substantial
  differences; `Platform.select`/`Platform.OS` for small ones (`code-style.md`). Same exports and types in every
  variant.
- Version-gated APIs: `Platform.Version` (Android API level number / iOS version string) before using something only
  newer OS versions support.
- Respect platform conventions the task implies: back handling on Android (`BackHandler`, or the navigator's
  `beforeRemove`/`usePreventRemove`), haptics/alerts styles, `Pressable` `android_ripple` vs. iOS opacity feedback.
- Permissions are requested at the moment of use with an explanation, and the denied/"don't ask again" path is
  handled (open settings via `Linking.openSettings()`), on both platforms.

## Accessibility

- Automated checks: `eslint-plugin-react-native-a11y` at the `.ui-quality.yml` level plus RNTL role queries; native
  audits are limited (`ui-implementation.md`, "Accessibility checks"). The rules below apply whatever the level.
- Every interactive element has an **accessible role and name**: `accessibilityRole="button"` (or the `role` prop)
  and visible text or `accessibilityLabel`. Icon-only buttons always need a label. This is also what makes
  `getByRole(..., { name })` queries work in tests.
- State via `accessibilityState={{ disabled, selected, checked, expanded, busy }}` (or `aria-*` props); values via
  `accessibilityValue` for sliders/progress. `accessibilityHint` only when the result of the action isn't obvious.
- Group related content (`accessible` on a container) so screen readers announce a card once instead of five
  fragments; hide decorative elements (`accessibilityElementsHidden` on iOS + `importantForAccessibility="no-hide-descendants"`
  on Android, or `aria-hidden`).
- Touch targets at least 44×44 pt (iOS) / 48×48 dp (Android) — use `hitSlop` for small icons.
- Announce async results that change the screen without focus moving (`AccessibilityInfo.announceForAccessibility`)
  and respect reduced motion (`AccessibilityInfo.isReduceMotionEnabled()`, Reanimated's `useReducedMotion()`).

## Lists and performance

The collection pattern (view model holding the whole list with stable ids, `FlatList` + memoized item component,
paging, selection keyed by id) and its code sketch are in `ui-implementation.md`, "Collections"; the tuning notes
below complement it.

- Long or unbounded data → **`FlatList`/`SectionList`** (or `@shopify/flash-list` / `LegendList` if the project
  already uses one) — never `ScrollView` + `.map()` over a list that can grow.
- `keyExtractor` returning a stable id; `renderItem` defined outside render or memoized, rendering a memoized row
  component (`React.memo`) that receives primitive/stable props; `getItemLayout` when rows have a fixed height;
  tune `initialNumToRender`/`windowSize`/`maxToRenderPerBatch` only with a reason. `ListEmptyComponent`,
  `ListFooterComponent` for loading-more, `onEndReachedThreshold` for pagination; don't nest a vertical `FlatList`
  in a vertical `ScrollView`.
- Keep work off the JS thread during interaction: no heavy synchronous computation in render or in a scroll handler;
  defer non-urgent work (`requestIdleCallback`/`InteractionManager` where the project uses it, `startTransition`).
- Avoid anonymous-object props to memoized children on hot paths unless the React Compiler is on (it memoizes for
  you; then don't hand-write `useMemo`/`useCallback` without a reason).

## Images

- `expo-image` (expo, when installed) or `react-native`'s `<Image>` / the project's `react-native-fast-image`
  replacement — whichever exists. Remote images need explicit dimensions (or `aspectRatio`) — they have no intrinsic
  size before load; local `require("./x.png")` images supply theirs.
- Provide `@2x`/`@3x` variants for bundled raster assets, or vector (`react-native-svg`, SF Symbols via
  `expo-symbols`) when the project has it. Decorative images get no accessible name; meaningful ones get
  `accessibilityLabel`/`alt`.
- Large lists of images: `expo-image`'s `recyclingKey`/caching, or the project's cache policy; never load full-size
  originals for thumbnails.

## Animations and gestures

- **Reanimated** (if installed — both templates' ecosystems commonly have it; Reanimated 4 needs the New
  Architecture and `react-native-worklets`): `useSharedValue` + `useAnimatedStyle` + `withTiming`/`withSpring`, or
  CSS-style animations/transitions (Reanimated 4); layout animations (`entering`/`exiting`) for mount/unmount.
  Animated values never go through React state. Worklet functions only touch shared values and worklet-safe APIs;
  call back to JS with `scheduleOnRN` from `react-native-worklets` (Reanimated 4; Reanimated's own `runOnJS` is the
  older API — match whichever the project uses).
- **Gesture Handler** (`react-native-gesture-handler`, `GestureDetector` + `Gesture.Pan()`…) for gestures beyond a
  press; `GestureHandlerRootView` must wrap the app root (Expo Router does it).
- Without Reanimated, React Native's `Animated` API with `useNativeDriver: true` for transform/opacity. Never add
  Reanimated for one task.
- Respect reduced motion (above). Tests: Reanimated ships Jest support (`react-native-reanimated/mock` or
  `setUpTests()` per the installed major) — use the setup the project already has.
