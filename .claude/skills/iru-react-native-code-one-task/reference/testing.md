# Tests

Every task's implementation comes with the tests that prove it. This skill **writes** them and does not **run** them
— `iru-react-native-code-one-task-group` runs the scoped tests, the 80% coverage gate (Jest unit tests only), the
full suite, the quality checks, any native build/test stage, and the e2e flows once for the whole group.

## Follow the project

Read an existing test near the code first and match its location (co-located `Foo.test.tsx`, or `__tests__/` — the
CLI template uses `__tests__/App.test.tsx`), naming, globals style (Jest globals are ambient), assertion style, and
mocking habits. Name new files so the project's `testMatch` picks them up (`*.test.ts(x)` / `__tests__/**`), and keep
e2e flows out of Jest's match.

## Runner and preset — detected, never added

| Toolchain | Jest preset | Where configured |
|---|---|---|
| **metro** | `@react-native/jest-preset` (RN ≥ 0.80 template; older projects: `preset: "react-native"`) | `jest.config.js` |
| **expo** | `jest-expo` (or a platform-specific `jest-expo/ios`, `jest-expo/android`, `jest-expo/universal`) | `jest` key in `package.json` or `jest.config.js` |

Both run Jest (29.x in the October 2026 templates) with Babel — **types are not checked by the test run**; `tsc
--noEmit` in the group skill is what catches a type error in a test. If the project has no Jest config at all and the
task asks for tests, that's a blocker to report (adding a runner is a dependency decision), not something to set up
silently.

## React Native Testing Library

`@testing-library/react-native` (RNTL) for anything rendering components. **Check the installed major** — it changes
how every test is written:

- **RNTL 14+** (React 19, peer `test-renderer`): `render`, `rerender`, `unmount`, `renderHook`, `fireEvent`, and `act`
  are **async** — always `await render(<Foo />)`, `await fireEvent.press(button)`, `await renderHook(...)`.
  Forgetting the `await` gives assertions on a stale tree.
- **RNTL 13**: `render`/`fireEvent` are synchronous (`renderAsync`/`fireEventAsync` exist for Suspense).
- Both: `screen` for queries, built-in Jest matchers (`toBeOnTheScreen`, `toHaveTextContent`, `toBeDisabled`,
  `toHaveAccessibilityValue`, …) available from any RNTL import.

Example (verified against RNTL 14.1 with both presets):

```tsx
import { render, screen, userEvent } from "@testing-library/react-native";
import { Counter } from "./Counter";

jest.useFakeTimers();

test("increments and reports the new value on press", async () => {
  const onChange = jest.fn();
  const user = userEvent.setup();
  await render(<Counter initial={1} onChange={onChange} />);

  await user.press(screen.getByRole("button", { name: "Increment" }));

  expect(screen.getByText("Count: 2")).toBeOnTheScreen();
  expect(onChange).toHaveBeenCalledWith(2);
});
```

- **Query priority**: `getByRole(role, { name })` → `getByLabelText` → `getByPlaceholderText` → `getByText` →
  `getByDisplayValue` → `getByTestId` last. `getByRole` only matches elements that are accessibility elements with
  that role — which is a reason to give components proper `accessibilityRole`/labels (`ui-and-platform.md`), not to
  fall back to `testID`.
- **`userEvent`** (`userEvent.setup()`, then `await user.press`, `user.type`, `user.clear`, `user.scrollTo`) for
  realistic interaction; `fireEvent` for events `userEvent` doesn't model (`fireEvent(element, "onLayout", …)`,
  a custom native event). Match the project if it consistently uses `fireEvent`.
- **`findBy*`** (async) for what appears after an effect/fetch; `getBy*` for the initial render; `queryBy*` only to
  assert absence. No `waitFor` around a `getBy*` when a `findBy*` does it.
- Don't snapshot whole screens (`toJSON()` snapshots) as the only assertion — assert the specific behaviour.

## Timers, async, and the environment

- Fake timers for anything time-based (`jest.useFakeTimers()`, `jest.advanceTimersByTime`, `await jest.runAllTimersAsync()`);
  `userEvent` cooperates with them. Never a real `setTimeout` sleep in a test.
- Clean up between tests: RNTL auto-cleans after each test; reset mocks (`clearMocks: true` or
  `jest.clearAllMocks()` in `afterEach`) and restore real timers if a test switched to fake ones.
- `Platform.OS` is `"ios"` by default under both presets (`jest-expo/android` flips it). To exercise an
  `if (Platform.OS === "android")` branch, `jest.replaceProperty(Platform, "OS", "android")` works — but it is **not**
  undone automatically: call `jest.restoreAllMocks()` in `afterEach` (or rely on `restoreMocks: true`), or the next
  test still sees Android. It does **not** change `Platform.select(...)`, which stays bound to the iOS
  implementation — test those values by importing the platform file directly (`Foo.android.tsx`) or by moving the
  branch to a `Platform.OS` check. Verified on RN 0.87's preset.

## Mocking native modules

- **Mock at the boundary you own**: the TS facade over a native module, the API client, the storage module
  (`state-and-data.md`). For third-party native modules, use the library's official Jest mock when it ships one
  (AsyncStorage, `react-native-safe-area-context/jest/mock`, Reanimated, Gesture Handler `jestSetup`), else a
  `jest.mock("<pkg>", () => ({ ... }))` factory exposing only what the code calls.
- The presets already mock React Native core native modules and `jest-expo` mocks Expo SDK modules' native side;
  don't re-mock them unless a test needs a specific return value (`jest.spyOn(Linking, "openURL")`).
- A Turbo Module spec (`specs/Native<Name>.ts`) is mocked as `{ __esModule: true, default: { …methods } }`
  (`native-modules.md`).
- Never mock the component or hook under test, and never stub away the behaviour the task changed.

## Navigation

- **Expo Router**: unit-test screen *logic* through the feature components/hooks the route file composes. To test
  routing itself (redirects, params, guards), use `renderRouter` from `expo-router/testing-library` with an inline
  route map (`{ index: () => <Home />, "orders/[id]": OrderScreen }`) and `initialUrl`. With RNTL 14,
  `renderRouter` returns a promise carrying its helpers — keep the returned object, then await it:

  ```tsx
  const router = renderRouter({ "orders/[id]": OrderScreen }, { initialUrl: "/orders/42" });
  await router;
  expect(router).toHavePathname("/orders/42");
  expect(await screen.findByText("Order 42")).toBeOnTheScreen();
  ```

  (`expect(screen).toHavePathname(...)`, as older docs show, fails with RNTL 14 — verified on SDK 57.)
- **React Navigation**: render the screen component directly with a mocked `navigation`/`route` prop when it only
  calls `navigation.navigate`; for flows across screens, render the real navigator inside a
  `NavigationContainer` (React Navigation's testing guide), asserting on what the user sees, not on navigator state.

## UI tests, the gallery and screenshots

For a task that adds or changes a screen, component or list, `ui-contract.md` §6–§8 apply as mapped in
`ui-implementation.md`: RNTL component tests driven by the component's `*.fixtures.ts` (every visual state, each
event and its payload, disabled states silent), screen tests with fixture state or a mocked view model hook, a UI
gallery registry entry and a `.maestro/ui/<Id>.yaml` capture flow per component/screen (state × direction × theme
through `.maestro/subflows/ui-capture.yaml`), `takeScreenshot` steps in new flows, and role/label queries as the
accessibility check. Jest renders no pixels — screenshots only ever come from the device flows, which this skill
writes and never runs.

## Native tests and e2e — written here, run by the group skill

- Native unit tests (metro: JUnit under `android/app/src/test/…`, XCTest in the iOS test target; Expo modules: their
  own `android/src/test`) per `native-modules.md`. They never count toward the JS coverage gate.
- **E2E**: if the project has **Maestro** flows (`.maestro/*.yaml`, by convention `.maestro/smoke.yaml`), add or extend
  a flow for a user-visible path the task adds — `appId` from the app config (`android.package`/`ios.bundleIdentifier`
  or `applicationId`), `- launchApp`, `- tapOn: "Sign in"` (prefer visible text or accessibility label;
  `id:` matches `testID`), `- assertVisible: ...`. If the project uses **Detox** instead (`.detoxrc.js`, `e2e/*.test.ts`),
  follow it. Never add an e2e framework for one task. E2E needs a booted simulator/emulator and Metro, so the group
  skill runs it serialized; **this skill never runs it** and e2e never counts toward the coverage gate.

## What to cover

The behaviour the task added or changed (happy path plus meaningful variations), every documented `@throws`/
rejection, the loading/error/empty states of a screen, the unsupported-platform path of a native facade, and
validation of untrusted input (deep-link params, API responses). Not the trivia: a pure re-export, a type-only file,
a `StyleSheet` constant. If a test can't pass without something outside the task's scope, say so in the report —
don't delete the assertion.
