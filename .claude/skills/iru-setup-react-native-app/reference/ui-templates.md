# UI templates (both toolchains)

The files `iru-setup-react-native-app` adds so a new app is ready for the catalog's UI contract (the coding skill
`iru-react-native-code-one-task`'s `reference/ui-contract.md` and `ui-implementation.md`): the `.ui-quality.yml`
settings file, a dev/test-only **UI gallery** that renders one component or screen state from a deep link, its
Jest test, and the Maestro **screenshot helper** subflow plus a sample capture flow for the scaffold's root screen.
The toolchain-specific parts — the ESLint accessibility config, the entry-point wiring, the deep-link scheme — are in
`expo-templates.md` / `metro-templates.md`.

Verified in October 2026 in `$TMPDIR/iru-verify/react-native/setup-react-native-app/` on a fresh `create-expo-app`
blank-typescript scaffold (Expo SDK 58, `react-native@0.88.0`, `react@19.3.0`, `jest-expo` 58.0.11, Jest 30.5,
RNTL 14.1, ESLint 9.39.5) and a React Native CLI 20.2.0 template (`react-native@0.88.0`, Jest 29, ESLint 8): `jest`
(6 tests, `UiGalleryHost.tsx` 97% lines), `tsc --noEmit`, the lint script with the accessibility plugin, and Prettier
pass on both; the production bundle (`npx expo export --platform web`; `npx react-native bundle --dev false`)
contains no trace of the gallery while the development bundle does. The Maestro flows were checked for YAML/Prettier
validity only — running them needs the Maestro CLI and a booted simulator/emulator (**unverified locally**).

The blocks are formatted for the expo `.prettierrc` (double quotes, 100 columns). On **metro** write them as shown,
then run `npx prettier --write src/dev index.js .eslintrc.js` once — the template's `.prettierrc.js` (single quotes,
80 columns, `arrowParens: 'avoid'`) reformats them, and `@react-native/eslint-config` reports Prettier differences
as lint errors until then.

## `.ui-quality.yml` (repository root)

Exactly the contract's shape; `<accessibility>` is Step 0's `accessibility` (default `enforce`). Written at the
**repository root** (`git rev-parse --show-toplevel`), not inside `<app-directory>`, because the coding, test and
documentation skills look for it there; the ESLint configs search upward for it.

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: <accessibility>      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

Prettier would re-align its comments — `expo-templates.md`'s `.prettierignore` lists it (only relevant when the app
directory is the repository root).

## `src/dev/ui-gallery/index.ts`

```ts
export { parseGalleryUrl, withUiGallery, type GalleryRequest } from "./UiGalleryHost";
```

## `src/dev/ui-gallery/registry.tsx`

The scaffold's only screen is the template's `App`; every later component/screen adds an entry (states from its
`*.fixtures.ts`), per the coding skill's `ui-implementation.md`.

```tsx
import type { ReactElement } from "react";

import App from "../../../App";

/** One component or screen the UI gallery can render, with one render function per visual state. */
export type GalleryEntry = Readonly<{
  kind: "component" | "screen";
  states: Readonly<Record<string, () => ReactElement>>;
}>;

/**
 * Every component/screen the Maestro capture flows (`.maestro/ui/`) screenshot, keyed by its type name exactly as
 * in code. Add an entry (states from the component's `*.fixtures.ts`) for each new component or screen.
 */
export const galleryRegistry: Readonly<Record<string, GalleryEntry>> = {
  App: { kind: "screen", states: { default: () => <App /> } },
};
```

## `src/dev/ui-gallery/UiGalleryHost.tsx`

`Appearance.setColorScheme` applies the requested theme to `useColorScheme()` (React Native 0.88 types:
`"light" | "dark" | "auto" | "unspecified"`); the `direction` style mirrors rows and logical edges for `rtl`. The
`ui-gallery-ready` test id appears only once both are applied — the capture subflow waits for it.

```tsx
import { useEffect, useState, type ComponentType, type ReactElement } from "react";
import { Appearance, Linking, StyleSheet, Text, View } from "react-native";

import { galleryRegistry } from "./registry";

/** A request to render one gallery entry in one state, direction and theme. */
export type GalleryRequest = Readonly<{
  id: string;
  state: string;
  dir: "ltr" | "rtl";
  theme: "light" | "dark";
}>;

const GALLERY_LINK = /^[a-z][a-z0-9+.-]*:\/\/ui-gallery\/?(\?|$)/i;

/**
 * Parses a `<scheme>://ui-gallery?id=<Id>&state=<state>&dir=<ltr|rtl>&theme=<light|dark>` deep link.
 *
 * @param url - The URL the app was opened with, if any.
 * @returns The gallery request, or `null` when the URL is not a complete gallery link.
 */
export function parseGalleryUrl(url: string | null | undefined): GalleryRequest | null {
  if (!url || !GALLERY_LINK.test(url)) {
    return null;
  }
  const query = url.split("?")[1] ?? "";
  const params = new Map(
    query
      .split("&")
      .filter((pair) => pair.length > 0)
      .map((pair) => {
        const [key, value = ""] = pair.split("=");
        return [decodeURIComponent(key), decodeURIComponent(value)] as const;
      }),
  );
  const id = params.get("id");
  const state = params.get("state");
  if (!id || !state) {
    return null;
  }
  return {
    id,
    state,
    dir: params.get("dir") === "rtl" ? "rtl" : "ltr",
    theme: params.get("theme") === "dark" ? "dark" : "light",
  };
}

/**
 * Wraps the app root with the dev/test-only UI gallery: a `ui-gallery` deep link renders one registry entry in the
 * requested state, direction and theme (for the Maestro screenshot flows); any other launch renders the app.
 * Only ever reached behind `__DEV__`, so production bundles never contain it.
 *
 * @param App - The app's root component.
 * @returns The wrapped root component.
 */
export function withUiGallery(App: ComponentType): ComponentType {
  return function UiGalleryHost(): ReactElement {
    const [request, setRequest] = useState<GalleryRequest | null>(null);
    const [ready, setReady] = useState(false);

    useEffect(() => {
      let active = true;
      Linking.getInitialURL()
        .then((url) => {
          if (active) {
            setRequest(parseGalleryUrl(url));
          }
        })
        .catch(() => setRequest(null));
      const subscription = Linking.addEventListener("url", ({ url }) =>
        setRequest(parseGalleryUrl(url)),
      );
      return () => {
        active = false;
        subscription.remove();
      };
    }, []);

    useEffect(() => {
      setReady(false);
      if (request !== null) {
        Appearance.setColorScheme(request.theme);
        setReady(true);
      }
    }, [request]);

    if (request === null) {
      return <App />;
    }
    const render = galleryRegistry[request.id]?.states[request.state];
    return (
      <View
        testID={ready ? "ui-gallery-ready" : undefined}
        style={[styles.root, { direction: request.dir }]}
      >
        {render ? render() : <Text>{`No gallery entry ${request.id}/${request.state}`}</Text>}
      </View>
    );
  };
}

const styles = StyleSheet.create({
  root: { flex: 1 },
});
```

## `src/dev/ui-gallery/__tests__/UiGalleryHost.test.tsx`

Keeps the gallery inside the 80% unit-coverage gate (97% lines when verified) and documents the link format.

```tsx
import { render, screen } from "@testing-library/react-native";
import { Linking, Text } from "react-native";

import { parseGalleryUrl, withUiGallery } from "..";

const Root = () => <Text>app root</Text>;

afterEach(() => {
  jest.restoreAllMocks();
});

test("parses a complete gallery link", () => {
  expect(parseGalleryUrl("myapp://ui-gallery?id=App&state=default&dir=rtl&theme=dark")).toEqual({
    id: "App",
    state: "default",
    dir: "rtl",
    theme: "dark",
  });
});

test("defaults direction and theme, and rejects other links", () => {
  expect(parseGalleryUrl("myapp://ui-gallery?id=App&state=default")).toMatchObject({
    dir: "ltr",
    theme: "light",
  });
  expect(parseGalleryUrl("myapp://ui-gallery?id=App")).toBeNull();
  expect(parseGalleryUrl("myapp://orders/42")).toBeNull();
  expect(parseGalleryUrl(null)).toBeNull();
});

test("renders the app when not opened through a gallery link", async () => {
  jest.spyOn(Linking, "getInitialURL").mockResolvedValue(null);
  const Host = withUiGallery(Root);
  await render(<Host />);
  expect(await screen.findByText("app root")).toBeOnTheScreen();
});

test("renders the requested gallery entry", async () => {
  jest
    .spyOn(Linking, "getInitialURL")
    .mockResolvedValue("myapp://ui-gallery?id=App&state=default&dir=rtl&theme=dark");
  const Host = withUiGallery(Root);
  await render(<Host />);
  expect(await screen.findByTestId("ui-gallery-ready")).toBeOnTheScreen();
});

test("reports an unknown gallery entry", async () => {
  jest
    .spyOn(Linking, "getInitialURL")
    .mockResolvedValue("myapp://ui-gallery?id=Nope&state=default");
  const Host = withUiGallery(Root);
  await render(<Host />);
  expect(await screen.findByText("No gallery entry Nope/default")).toBeOnTheScreen();
});
```

## `.maestro/config.yaml`

```yaml
# Maestro workspace: `maestro test .maestro/` runs the top-level flows plus the UI gallery capture flows in ui/
# (subflows/ holds helpers that only run through runFlow).
flows:
  - "*"
  - "ui/*"
```

`maestro test .maestro/` runs the top-level flows (`smoke.yaml`) and the gallery capture flows in `ui/`; `subflows/`
is only ever reached through `runFlow`.

## `.maestro/subflows/ui-capture.yaml` — the screenshot helper

`<app-id>` as in `smoke.yaml`; `<app-scheme>` is the scheme the toolchain file registers (`<app-slug>`). The
file name is built only here: `<KIND>/<ID>/<STATE>--<DIR>--<THEME>--<platform>.png` under `UI_SCREENSHOTS_DIR`, which
`iru-typescript-test`'s `e2e` selector passes with `-e` (by hand: `maestro test -e
UI_SCREENSHOTS_DIR="$(git rev-parse --show-toplevel)/.ui-screenshots" .maestro/ui/`). `${maestro.platform}`
(`ios`/`android`) is the contract's platform `<variant>`, since the same component renders on both.

```yaml
# UI contract screenshot helper: opens the dev-only UI gallery on one entry and state, then saves
# ${UI_SCREENSHOTS_DIR}/<KIND>/<ID>/<STATE>--<DIR>--<THEME>--<platform>.png
# (KIND, ID, STATE, DIR, THEME come from the calling runFlow's env; UI_SCREENSHOTS_DIR from `maestro test -e`).
appId: <app-id>
---
- openLink: "<app-scheme>://ui-gallery?id=${ID}&state=${STATE}&dir=${DIR}&theme=${THEME}"
- tapOn:
    text: "Open"
    optional: true
- extendedWaitUntil:
    visible:
      id: "ui-gallery-ready"
    timeout: 15000
- waitForAnimationToEnd
- takeScreenshot: "${UI_SCREENSHOTS_DIR}/${KIND}/${ID}/${STATE}--${DIR}--${THEME}--${maestro.platform}"
```

## `.maestro/ui/App.yaml` — sample capture flow

One `runFlow` per state × direction × theme of `.ui-quality.yml` (here `App` has one state, `default`). Tagged
`ui-gallery`: the gallery exists only in debug/development builds, so CI (which tests a release build) runs
`maestro test --exclude-tags=ui-gallery`.

```yaml
# Captures the App screen in every state × direction × theme through the UI gallery (debug/development builds only).
appId: <app-id>
tags:
  - ui-gallery
---
- launchApp
- runFlow:
    file: ../subflows/ui-capture.yaml
    env:
      KIND: screen
      ID: App
      STATE: default
      DIR: ltr
      THEME: light
- runFlow:
    file: ../subflows/ui-capture.yaml
    env:
      KIND: screen
      ID: App
      STATE: default
      DIR: ltr
      THEME: dark
- runFlow:
    file: ../subflows/ui-capture.yaml
    env:
      KIND: screen
      ID: App
      STATE: default
      DIR: rtl
      THEME: light
- runFlow:
    file: ../subflows/ui-capture.yaml
    env:
      KIND: screen
      ID: App
      STATE: default
      DIR: rtl
      THEME: dark
```
