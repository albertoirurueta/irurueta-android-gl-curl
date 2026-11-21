# Ionic (Capacitor)

Conventions specific to an Ionic (Angular or React flavor, Capacitor, `iru-setup-ionic-app`) project. Everything
in `code-style.md`, `tsdoc.md`, and `testing.md` applies, plus `react.md` or `angular.md` depending on which
flavor this project uses (Step 1 of `SKILL.md` detects `ionic` as the framework key but the underlying UI layer
is still React or Angular — read whichever of those two files matches `package.json`'s actual framework
dependency in addition to this one).

## Ionic components over bare HTML/native equivalents

- **Use Ionic's own components** (`<ion-button>`, `<ion-list>`/`<ion-item>`, `<ion-input>`, `<ion-modal>`,
  `<ion-toast>`, `<ion-loading>`, …) instead of a bare `<button>`/`<input>`/hand-rolled modal — they carry the
  correct platform-adaptive styling (iOS vs. Material) and accessibility behavior for free, which a hand-rolled
  equivalent would have to reimplement.
- In the **Angular flavor**, import each Ionic component individually from `@ionic/angular/standalone`
  (`IonButton`, `IonList`, `IonItem`, …) into the standalone component's `imports` array — not the whole
  `IonicModule`, which this catalog's scaffold treats as legacy and pulls in every component unnecessarily.
- In the **React flavor**, import from `@ionic/react` (`IonButton`, `IonList`, `IonItem`, …) as ordinary React
  components, following `react.md` for everything about how the component itself is written (function component,
  props interface, hooks rules).
- Navigation: `IonRouterOutlet`/`useIonRouter()` (React) or Angular Router wrapped by `IonRouterOutlet` (Angular)
  — not a bare `react-router`/`@angular/router` navigation call that bypasses Ionic's page-transition/lifecycle
  handling.
- **Long lists**: `ion-list` alone renders every item. A data-driven list is virtualized (CDK virtual scroll in the
  Angular flavor, `@tanstack/react-virtual` in the React flavor — Ionic's own `ion-virtual-scroll` was removed in
  v6) with `ion-infinite-scroll` for paging; see `ui-implementation.md` (Ionic section) for that, dark palette,
  right-to-left and the component gallery set-up.

## Capacitor plugin usage

- **Call a Capacitor plugin through its typed JS API** (`import { Camera } from "@capacitor/camera";`, `await
  Camera.getPhoto({...})`) — never assume a plugin's native side is present without checking; every plugin call
  that can fail on an unsupported platform/permission should be wrapped so the failure is handled, not left to
  throw uncaught into the UI.
- **A new native capability needs its Capacitor plugin added and platforms synced** (`npm install
  @capacitor/<plugin>`, then `npx cap sync`) as part of the task, not assumed to already be present — note this
  in the Step 7 report as a project-setup step the user must actually run, since this skill's `allowed-tools`
  don't include running `cap sync` as a side effect of implementing a task silently.
- Writing a **custom native plugin** (Swift/Kotlin code under `ios/`/`android/` with no existing Capacitor plugin
  backing it) is out of scope for a single task — report it as a blocker (Step 5 of `SKILL.md`) rather than
  hand-editing the native platform projects.

## Platform checks

- **`Capacitor.getPlatform()`** (`"ios" | "android" | "web"`) or **`Capacitor.isNativePlatform()`** for branching
  behavior that only makes sense on a real device (haptics, a native-only plugin, a platform-specific status bar
  color) — check before calling a plugin whose web implementation is a no-op or throws, rather than letting the
  call fail silently in the browser during development.
- Keep a platform branch small and localized (a single call site, a single style value) — a component whose
  entire body differs by platform is better split the way `react-native.md` describes for RN (`.ios`/`.android`
  file variants don't apply to a web-based Ionic build, but the same "extract the divergent part" principle
  does).
- The web build (Vite for React, the Angular builder for Angular) must still run standalone without a native
  shell — don't write code that assumes `Capacitor.isNativePlatform()` is always `true` without a web fallback,
  since this catalog's CI verification step builds and lints the web target without a device/simulator.

## Testing

- Follow `react.md`'s or `angular.md`'s testing section (Testing Library query priority / `TestBed`, matching
  whichever flavor this project is) for the UI layer itself.
- **Mock Capacitor plugin calls** in unit tests — they have no native implementation to call into under Vitest/
  Jest/`ng test`, and even their web fallback may touch browser APIs (camera, filesystem) a unit test shouldn't
  depend on. Mock at the plugin import boundary (`vi.mock("@capacitor/camera", () => ({ Camera: { getPhoto:
  vi.fn() } }))` or the Jest/Angular-test equivalent), following `testing.md`'s "mock what you don't own" rule.
- A platform-branch (`Capacitor.getPlatform()`) is tested by stubbing the platform check's return value for each
  branch under test, not by actually running on a different device.
- Screens and components also get Playwright UI tests in the browser (web build, plugins mocked) capturing the
  `.ui-screenshots/` matrix with axe checks — `ui-implementation.md`. Device-only behaviour stays out of them.
