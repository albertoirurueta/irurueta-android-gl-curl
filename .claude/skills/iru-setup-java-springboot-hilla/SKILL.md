---
name: iru-setup-java-springboot-hilla
description: "Invoked as `/iru-setup-java-springboot-hilla` (args: `stack-file`, `mode`, `on-existing`, `accessibility`), normally by `iru-setup-java-springboot`. Scaffolds a Hilla (Vaadin) React frontend into the web module of a Spring Boot build: Vaadin build wiring, `src/main/frontend/`, Vitest, ESLint/Prettier and Playwright UI tests."
model: sonnet
---

# Setup Java Spring Boot Hilla Frontend

Runs standalone against any build created by `iru-setup-java-springboot-modules`, or is invoked by `iru-setup-java-springboot`'s Step 8 (via `iru-isolated-skill-executor`) right after `iru-setup-java-springboot-apis` whenever the manifest's `frontend` is `hilla`.

Scaffold a Hilla React frontend into the module of a Spring Boot hexagonal reactor that runs the web server. Two
things about Hilla in the Vaadin 25.x line are easy to get wrong and were confirmed empirically while writing this
skill (a real Spring Boot 4 + Hilla project was built and run end to end — the same verification this skill's own
Step 8 repeats on every future run):

1. **`com.vaadin:hilla-spring-boot-starter` alone does not build.** Even a frontend with zero server-side Flow
   `@Route` views still ships an auto-generated Flow compatibility shim
   (`src/main/frontend/generated/flow/Flow.tsx`) that imports a Flow client resource
   (`Frontend/generated/jar-resources/Flow.js`) which is only present when `com.vaadin:vaadin-spring-boot-starter`
   is also on the classpath. Without it, `vaadin:build-frontend` fails at the Vite step with
   `Cannot find module 'Frontend/generated/jar-resources/Flow.js'`. **Always add both starters** — the "Flow
   views" question in Step 1 decides only whether example Flow `@Route` Java views are scaffolded, not whether the
   dependency is present.
2. **`routes.tsx` is generated, never hand-written.** Hilla 25's file-based router derives
   `src/main/frontend/generated/routes.tsx` and `generated/file-routes.ts` from whatever `.tsx`/`.jsx` files exist
   under `src/main/frontend/views/`, every time `vaadin:prepare-frontend`/`build-frontend` runs. This skill writes
   `views/@index.tsx` and `views/@layout.tsx` only — never `routes.tsx` itself, and never anything under
   `src/main/frontend/generated/`.

**Kotlin and Gradle.** The manifest's `project.language` (`java` | `kotlin`, absent ⇒ `java`) and
`project.buildTool` (`maven` | `gradle`, absent ⇒ `maven`; `gradle` only with `kotlin`) change the build wiring
(Step 2) and the server-side service code (Step 2a) — never the frontend files of Steps 3–5, which are identical in
all three combinations. Both Kotlin paths were verified on Spring Boot 4.1.1 + Vaadin 25.3.2 + Kotlin 2.3.21: a
Kotlin `@BrowserCallable` service compiled, the TypeScript client was generated from it, and a production bundle
was built — on Maven with `vaadin-maven-plugin` and on Gradle 8.14 with the `com.vaadin` plugin.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

Parse `args` for `key: value` lines:

```
stack-file: springboot-stack.yml
mode: existing
on-existing: ask
accessibility: enforce
```

`mode` (`new`/`existing`) is informational — it never means "update silently"; `on-existing`
(`ask`/`keep`/`update`/`replace`, default `ask`) pre-answers Step 1's existing-frontend question for every file.

`accessibility` (`enforce`/`report`/`off`/`none`) is the UI tests' automated accessibility level written to
`.ui-quality.yml` (Step 4b). Resolve it from `args` first, else the manifest's top-level `accessibility` key
(written by `iru-setup-java-springboot`, meaningful only with `frontend: hilla`), else `enforce` — never ask.

`stack-file` defaults to `springboot-stack.yml` at the repository root. Read it in full, including
`project.language` and `project.buildTool` (defaults above; stop and report a manifest pairing `gradle` with `java`). Stop and tell the user to
run `/iru-setup-java-springboot` (or `/iru-setup-java-springboot-modules`) first if it doesn't exist — this skill
doesn't scaffold a reactor from nothing, only a frontend into one that already exists. Confirm `frontend: hilla` is
actually set; if it's `none`, ask via `AskUserQuestion` whether to proceed anyway (the user may be adding a
frontend after the fact) or stop, and if proceeding, update the manifest's `frontend:` field to `hilla` before
writing anything else, so a later re-run of `iru-setup-java-springboot` sees the choice that was actually made.

**Accessibility level `none` and assistive-technology templates.** `accessibility` takes `enforce`, `report`, `off` or
`none` (UI contract §8; the stand-alone question gains a fourth option, **None — opt out entirely**, e.g. a mandated
non-compliant design). With `none`: write `accessibility: none` to `.ui-quality.yml`, do not install or configure the
accessibility test tooling (axe-core in the Playwright UI tests), and omit the accessibility-specific attributes from the generated templates.
With `enforce`, `report` or `off`: include the assistive-technology attributes from the contract's §8 platform
subsections in every generated template — semantic landmarks, accessible names on icon-only buttons, `alt` on images, relative font sizes, `prefers-reduced-motion` guards — and, for `enforce`/`report` only, the automated checks. In
`mode: existing`, a prior `.ui-quality.yml` follows this skill's existing Update / Keep / Replace prompt and its
`accessibility` value is never silently flipped (**Update** sets it only when `args` supplied it).

## Step 1 — Survey

- **The web module.** This catalog's reactor runs the `@SpringBootApplication` class and packages the executable
  jar in the `boot` module (see `iru-setup-java-springboot`'s module table) — that is also the module
  `spring-boot-maven-plugin`'s `repackage` goal runs in, and Vaadin's `build-frontend` must run in the module that
  produces the boot jar, since the production Vite bundle is written under that module's
  `target/classes/META-INF/VAADIN/`. Unless the manifest's `modules:` list is missing `boot` (report this as a
  blocker — it means `iru-setup-java-springboot-modules` hasn't run yet), **`boot` is the web module** this skill
  writes into. This was checked against `iru-setup-java-springboot-pom`'s own module table (`boot` is "every other
  module, `spring-boot-starter`... the only module with `spring-boot-maven-plugin`") and confirmed against the
  official `vaadin/skeleton-starter-hilla-react` reference project, whose single module plays exactly this role.
- **Gradle:** the web module is the `:boot` project (`settings.gradle.kts`), its build file
  `boot/build.gradle.kts`; Vite's working files land in `boot/build/` instead of `boot/target/`.
- **The root pom** (Maven). Check whether `<vaadin.version>`, the `vaadin-bom` `dependencyManagement` import, the
  `vaadin-maven-plugin` `pluginManagement` entry, and a `production` profile already exist (written by
  `iru-setup-java-springboot-pom` — see that skill's Step 1). If they do, use the existing `<vaadin.version>`
  as-is rather than re-resolving it, so the module and root stay on one version. **If they don't** (this skill is
  running standalone, or the root pom predates that skill's Hilla support), write them yourself now, following
  exactly the fragment `iru-setup-java-springboot-pom` documents, and say so in the final report so the user knows
  the root pom was touched by this skill rather than its usual owner.
- **The version catalog** (Gradle). The equivalent of the root-pom wiring is a `vaadin` entry in
  `gradle/libs.versions.toml` `[versions]`, a `vaadin-bom` library (`com.vaadin:vaadin-bom`, `version.ref = "vaadin"`),
  the versionless `vaadin-spring-boot-starter`/`hilla-spring-boot-starter`/`vaadin-dev` libraries, and a `vaadin`
  plugin alias (`id = "com.vaadin"`, `version.ref = "vaadin"` — the Vaadin Gradle plugin is published at the same
  version as the BOM). Same rule as Maven: use what `iru-setup-java-springboot-pom` wrote, fill the gap yourself
  only when it's missing, and say so in the report. Use `com.vaadin`, not the older standalone `com.vaadin.hilla`
  plugin: `com.vaadin` already registers `hillaConfigure`/`hillaGenerate` when Hilla is on the classpath (verified:
  `./gradlew :boot:tasks --group vaadin` lists them), and the `com.vaadin.hilla` plugin portal entry currently
  points at pre-releases only.
- **Existing frontend.** Check for `<web-module>/src/main/frontend/`, `<web-module>/vitest.config.ts`,
  `<web-module>/eslint.config.js`, the UI-test files (`<web-module>/playwright.config.ts`, `<web-module>/playwright/`,
  `<web-module>/e2e/`), `.ui-quality.yml` at the repository root, and an existing `com.vaadin:hilla-spring-boot-starter`/
  `vaadin-spring-boot-starter` dependency in `<web-module>/pom.xml` (Gradle: `boot/build.gradle.kts`, plus a `com.vaadin`
plugin alias there). Say whether this run is **scaffolding the
  frontend from scratch** (none of these exist) or **updating an existing frontend** (list which exist). If any
  exist, warn and — unless `on-existing` says otherwise — ask with `AskUserQuestion` rather than overwriting
  hand-written frontend code by default:
  - **Update — fill gaps only (Recommended)** — preserve every file already present and only add what's missing
    (and correct the pom wiring this skill owns, e.g. a missing `vaadin-maven-plugin` execution). If the user
    also wants the sample view/test regenerated, ask that as a follow-up rather than assuming it.
  - **Keep as is** — touch nothing in the frontend; it may disagree with this skill's conventions (e.g. no lcov
    output for Sonar) — the report lists each mismatch.
  - **Replace — regenerate everything** — rewrite every file this skill owns from its templates; hand-written
    views, tests, and config customizations are lost (recoverable only from git history).
  - **Stop** — end this skill now without changing anything.
- **Look up current versions**, and record what you resolved for Step "Report":
  - `com.vaadin:vaadin-bom` — `https://repo1.maven.org/maven2/com/vaadin/vaadin-bom/maven-metadata.xml`. Use the
    `<release>` version (skip any `-alpha`/`-beta`/`-rc` suffix even if it's newer — an RC line is not what a
    service scaffold should default to). At the time this skill was written, `<release>` briefly pointed at a
    `-rc1` build; if that's still true, use the newest version in `<versions>` **without** a pre-release suffix
    instead of trusting `<release>`/`<latest>` blindly. Recorded fallback: **`25.2.8`**.
  - Every npm devDependency below — `https://registry.npmjs.org/<package>/latest`. Two npm-specific traps, both
    hit while verifying this skill, worth checking for on every run:
    - `@eslint/js` is versioned independently of `eslint` itself (e.g. `eslint@10.10.0` next to `@eslint/js@10.0.1`
      at the time of writing) — never assume the two version numbers match.
    - A single `npm view <pkg>@latest` can return a stale cached answer; if an `npm install` then reports an
      `ERESOLVE` peer conflict against the version you resolved, re-resolve from the failing install's own error
      message (it names the actual peer range) rather than retrying the same lookup.
  - Recorded fallbacks (all current at the time this skill was written and mutually compatible — verified by an
    actual `npm install` + `npx eslint`/`npx prettier --check`/`npx vitest run --coverage` run): `eslint@10.10.0`,
    `@eslint/js@10.0.1`, `typescript-eslint@8.70.0`, `eslint-plugin-react-hooks@7.1.1`,
    `eslint-config-prettier@10.1.8`, `prettier@3.9.7`, `vitest@5.0.1`, `@vitest/coverage-v8@5.0.1` (must match
    `vitest`'s own version), `@testing-library/react@16.3.3`, `@testing-library/jest-dom@7.0.1`, `jsdom@30.0.1`.
- **Node.** Confirm `node`/`npm`/`npx` are on `PATH` and report the version found. `vaadin:prepare-frontend` uses a
  globally installed Node when one satisfies its minimum version requirement (confirmed: it logged
  `Using globally installed Node.js version 24.16.0` rather than downloading one) and only downloads its own copy
  into `~/.vaadin` when no usable global Node exists — mention whichever happened in the report, since the
  download adds real time to a first CI run.
- **Flow views** (`AskUserQuestion`, default **No**) — does this service want hand-written server-side Flow
  `@Route` views alongside the Hilla React ones? Default is No (a pure-React Hilla frontend). This only decides
  whether Step 3 also scaffolds an example `@Route` Java class — `vaadin-spring-boot-starter` is added either way,
  per the "always add both starters" finding above.

## Step 2 — Module build: dependencies and plugin

Add to `<web-module>/pom.xml` (`boot/pom.xml` unless Step 1 found otherwise). No `<version>` on any of these — they
resolve through the root pom's `dependencyManagement`/`pluginManagement`, exactly like every other module in this
reactor.

```xml
<dependencies>
  <!-- ... existing boot module dependencies ... -->

  <!-- Hilla (React endpoints/file-router) needs vaadin-spring-boot-starter too: the auto-generated Flow
       compatibility shim under src/main/frontend/generated/flow/ always references a Flow client resource
       that only vaadin-spring-boot-starter provides, even when no @Route Flow view is ever written. -->
  <dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>vaadin-spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>hilla-spring-boot-starter</artifactId>
  </dependency>
  <!-- Dev-mode live reload banner and Vaadin's browser dev tools; harmless and excluded from the runtime
       artifact in a production build. -->
  <dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>vaadin-dev</artifactId>
    <optional>true</optional>
  </dependency>
</dependencies>

<build>
  <plugins>
    <!-- ... existing boot module plugins (spring-boot-maven-plugin, groovy-maven-plugin, ...) ... -->
    <plugin>
      <groupId>com.vaadin</groupId>
      <artifactId>vaadin-maven-plugin</artifactId>
      <!-- version comes from the root pom's pluginManagement (${vaadin.version}) -->
      <executions>
        <execution>
          <goals>
            <goal>prepare-frontend</goal>
            <goal>build-frontend</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

If Step 1's "Flow views" question was answered Yes, also add whichever Flow UI component starters the user wants
(e.g. `com.vaadin:vaadin` for the full component set) and scaffold one example `@Route` class under
`<web-module>/src/main/java/<base-package>/...` (Kotlin: `src/main/kotlin/<base-package>/...`, a plain `class`
extending the Flow component — `kotlin-spring` doesn't need to open it, Flow instantiates rather than proxies it);
this is a small, self-contained addition and not templated here since it's opt-in.

**Kotlin + Maven** — the pom fragment above is unchanged. `vaadin-maven-plugin`'s goals bind to `process-classes`,
after `kotlin-maven-plugin`'s `compile`, so the Hilla generator parses the compiled Kotlin classes in
`target/classes` like Java ones. Keep Spring Boot parent's `<javaParameters>true</javaParameters>` for
`kotlin-maven-plugin` (the parent sets it; don't override it) — parameter names are what the generated TypeScript
client's argument names come from.

**Kotlin + Gradle** — in `boot/build.gradle.kts` (which `iru-setup-java-springboot-pom`'s `iru.spring-boot-app`
convention plugin already makes a Spring Boot application):

```kotlin
plugins {
    // ... existing iru.* convention plugins ...
    alias(libs.plugins.vaadin)          // com.vaadin — vaadinPrepareFrontend, hillaGenerate, vaadinBuildFrontend
}

kotlin {
    compilerOptions {
        // Hilla reads method parameter names from bytecode for the generated TypeScript client.
        // Hilla 25's Kotlin parser also reads Kotlin metadata, so names survived without it in verification —
        // keep it anyway: Spring's own parameter binding wants it too.
        javaParameters.set(true)
    }
}

dependencies {
    implementation(platform(libs.vaadin.bom))
    implementation(libs.vaadin.spring.boot.starter)   // required even with no Flow views — see the top of this skill
    implementation(libs.hilla.spring.boot.starter)
    // developmentOnly is a separate configuration: the BOM platform must be added to it as well, or the build fails
    // with "Could not find com.vaadin:vaadin-dev:." (hit while verifying this skill).
    developmentOnly(platform(libs.vaadin.bom))
    developmentOnly(libs.vaadin.dev)
}
```

The `com.vaadin` plugin needs no task wiring: it hooks `hillaConfigure` and `vaadinBuildFrontend` into
`processResources`/`bootJar` itself (verified with `--dry-run`), and `vaadinBuildFrontend` decides on its own whether
a bundle is needed. `vaadinPrepareFrontend` is **not** part of that graph — run it explicitly where Step 8 says so.
The Maven `production` profile's equivalent is the `-Pvaadin.productionMode=true` project property (verified:
`./gradlew :boot:build -Pvaadin.productionMode=true -x test` wrote the bundle under `META-INF/VAADIN/webapp/` in
`boot/build/libs/boot.jar`). The plugin's CI flag is the same key as Maven's, `vaadin.ci.build` (present in
`flow-gradle-plugin` 25.3; pass `-Pvaadin.ci.build=true` — not exercised during verification, so confirm it shows
`npm ci` rather than `npm install` in the first CI log).

## Step 2a — Kotlin `@BrowserCallable` services (`language: kotlin` only)

Skip for Java. Kotlin services are written exactly like Java ones — a class annotated `@BrowserCallable` (plus an
access annotation such as `@AnonymousAllowed` or `@RolesAllowed`), public methods become the TypeScript client —
with one difference that matters: **nullability comes from Kotlin's type system, not from annotations.** Since
Vaadin 24.8 Hilla's parser reads Kotlin metadata, and in Vaadin 25 this is built into the TypeScript generator (the
separate `hilla-parser-jvm-plugin-nonnull-kotlin` artifact stopped at `25.0.0-beta3` — never add it). Verified
output for

```kotlin
data class Greeting(val text: String, val audience: String?)

@BrowserCallable
@AnonymousAllowed
class GreetingService {
    fun greet(name: String, title: String?): Greeting = Greeting("Hello ${title.orEmpty()} $name".trim(), title)
}
```

is `greet(name: string, title: string | undefined): Promise<Greeting>` and `interface Greeting { text: string;
audience?: string; }` — so:

- Do **not** add JSpecify `@NonNull`/`@Nullable` (the Java way of getting required TypeScript fields) to Kotlin
  services or DTOs; `String` vs `String?` already says it, and two sources of truth drift.
- Return and accept Kotlin `data class`es for DTOs; keep entity types off the endpoint signature exactly as for Java.
- `@BrowserCallable` classes are Spring beans, so `kotlin-spring` opens them for proxying (security interceptors);
  nothing extra to configure. Hilla's own Kotlin test project additionally opens `com.vaadin.hilla.EndpointExposed`
  with `allOpen` — add that annotation to the module's all-open list only if a service extends an
  `@EndpointExposed` base class (e.g. `CrudRepositoryService`).
- Service classes go under `src/main/kotlin/<base-package>/...` of the web module, or the `api` module the
  modules skill designated for them — wherever Java services would go.

Scaffold no sample service unless the frontend's sample view (Step 3) calls one; this skill's sample view doesn't.

## Step 3 — `src/main/frontend/`

All of this lives directly under `<web-module>/src/main/frontend/` — **not** under `src/main/resources` or any
generated-sources directory. Never create or hand-edit anything under `src/main/frontend/generated/`: that whole
directory (and `routes.tsx`/`file-routes.ts` inside it) is written by `vaadin:prepare-frontend` on every run and
would be silently overwritten.

**`src/main/frontend/views/@index.tsx`** — the file-based router's index route:

```tsx
export default function HomeView() {
  return <div>Hello from <project-name></div>;
}
```

**`src/main/frontend/views/@layout.tsx`** — wraps every route; `<Outlet />` is where the router renders the active
view:

```tsx
import { Outlet } from 'react-router';

export default function MainLayout() {
  return (
    <div>
      <header><project-name></header>
      <Outlet />
    </div>
  );
}
```

**`src/main/frontend/index.html`** — Vaadin auto-generates a default the first time `prepare-frontend` runs if
none exists, but it's meant to be committed and customized afterward (the official Vaadin skeleton ships its own
with a real `<title>`), so write it once, up front, rather than letting the generic default land:

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title><project-name></title>
    <style>
      body {
        margin: 0;
        width: 100vw;
        height: 100vh;
      }
      #outlet {
        height: 100%;
      }
    </style>
    <!-- index.ts is included here automatically (either by the dev server or during the build) -->
  </head>
  <body>
    <div id="outlet"></div>
  </body>
</html>
```

Do **not** write `views/routes.tsx` or anything else under `generated/` — see the note at the top of this skill.

## Step 4 — `vitest.config.ts`, the sample test, and `@vitest/coverage-v8`

Write at `<web-module>/vitest.config.ts` (module root, next to `pom.xml` — **not** inside `src/main/frontend/`).
The one thing this file must get right, confirmed empirically: Vaadin's own generated Vite config
(`<web-module>/vite.generated.ts`, which both `vite.config.ts` and this file build on via its exported
`overrideVaadinConfig(customConfig: UserConfigFn)` helper — not `useVaadinConfig`, which doesn't exist) sets Vite's
`root` to `src/main/frontend`. Every glob and path below is written **relative to that root**, not to the module
root, or `vitest run` reports "No test files found" despite the tests being right there.

```ts
import { overrideVaadinConfig } from './vite.generated';

export default overrideVaadinConfig(() => ({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./test/setup.ts'],
    include: ['**/*.test.{ts,tsx}'],
    exclude: ['generated/**'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      // Vite's root is src/main/frontend; three levels up lands back at the module root, so the report ends
      // up at <web-module>/coverage/lcov.info — the exact path Sonar's sonar.javascript.lcov.reportPaths
      // and the CI workflow's coverage step both expect. Confirmed by an actual `vitest run --coverage`.
      reportsDirectory: '../../../coverage',
      include: ['**/*.{ts,tsx}'],
      exclude: ['generated/**', '**/*.test.{ts,tsx}', '**/*.story.tsx'],
    },
  },
}));
```

**`src/main/frontend/test/setup.ts`**:

```ts
import '@testing-library/jest-dom/vitest';
```

**`src/main/frontend/views/@index.test.tsx`** (sample test, colocated with the view it tests — the same
convention Hilla's own generator uses):

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import HomeView from './@index';

describe('HomeView', () => {
  it('renders the greeting', () => {
    render(<HomeView />);
    expect(screen.getByText('Hello from <project-name>')).toBeInTheDocument();
  });
});
```

A `.tsx` test file living directly under `views/` trips Vite's React plugin Fast Refresh boundary check (it logs
`... should contain a default export of a component` because a test module has no default export) — this is
purely a console warning; the test still runs and passes, confirmed by an actual run. Don't try to "fix" it by
moving the test elsewhere unless the user objects to the noise; it's the same layout Vaadin's own project
generator produces.

## Step 4b — UI contract: component gallery, screenshots and accessibility

The catalog's UI contract (repeated in `iru-typescript-code-one-task/reference/ui-contract.md`) makes every
component and screen test capture its states into `<repo root>/.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>.png`
and run axe at the level `.ui-quality.yml` sets. Verified with `@vaadin/react-components` 25.3.2, Vite 8 and
Playwright 1.64.1 outside a Maven build (the gallery, story and spec below, `Button` + `VirtualList` rendering, four
captures, axe clean); the Maven/Gradle-integrated run is unverified. Component and screen tests use Playwright's built-in `mount()`
fixture (`@playwright/test` ≥ 1.62) against a **gallery** page. Vaadin's own Vite config needs a completed
`prepare-frontend` and roots Vite at `src/main/frontend`, so the gallery runs on a small standalone Vite server
instead. Vitest browser mode stays the behaviour-test runner.

1. **`.ui-quality.yml`** at the **repository root** (not the web module), with `accessibility` from Step 0:

   ```yaml
   # .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
   accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: not run | none: opted out entirely
   directions: [ltr, rtl]      # screenshot matrix directions
   themes: [light, dark]       # screenshot matrix themes
   ```

   An existing file follows Step 1's existing-frontend answer (**Update** sets only `accessibility` when `args` or the
   manifest supplied it and adds missing keys; **Keep as is** reports a mismatch; **Replace** rewrites it).

2. **devDependencies** (resolved like Step 1's others; fallbacks `@playwright/test@1.64.1`,
   `@axe-core/playwright@4.14.0`): `@playwright/test`, `@axe-core/playwright`, and `@vitejs/plugin-react` only if
   Vaadin's generated `package.json` doesn't already list it (never change a version Vaadin pins). Add the script
   `"test:ui": "playwright test"`.

3. **`<web-module>/e2e/support/ui-screenshots.ts`** — exactly the content of this skill's
   `reference/ui-screenshots.ts`.

4. **The gallery server** — `<web-module>/playwright/vite.config.ts`:

   ```ts
   // Standalone Vite server for the component gallery (Vaadin's own config needs prepare-frontend and its root).
   import react from '@vitejs/plugin-react';
   import { fileURLToPath } from 'node:url';
   import { defineConfig } from 'vite';

   export default defineConfig({
     root: fileURLToPath(new URL('..', import.meta.url)),
     plugins: [react()],
     resolve: { alias: { Frontend: fileURLToPath(new URL('../src/main/frontend', import.meta.url)) } },
     server: { port: 5174, strictPort: true },
   });
   ```

   `<web-module>/playwright/gallery/index.html`:

   ```html
   <!doctype html>
   <html lang="en">
     <head>
       <meta charset="UTF-8" />
       <meta name="viewport" content="width=device-width, initial-scale=1.0" />
       <title>Component gallery</title>
     </head>
     <body>
       <main id="root"></main>
       <script type="module" src="./main.tsx"></script>
     </body>
   </html>
   ```

   `<web-module>/playwright/gallery/main.tsx` — import here whatever global stylesheet the app's entry imports (e.g.
   a `src/main/frontend/styles.css` with the Lumo theme), so stories look like the app:

   ```tsx
   // Component gallery for Playwright's built-in `mount()` fixture: renders one `*.story.tsx` export into #root.
   import { StrictMode, type ComponentType } from 'react';
   import { flushSync } from 'react-dom';
   import { createRoot, type Root } from 'react-dom/client';

   type StoryModule = Record<string, ComponentType<Record<string, unknown>>>;
   const stories = import.meta.glob<StoryModule>('../../src/main/frontend/**/*.story.tsx');
   const idOf = (file: string): string =>
     file.replace(/^(\.\.\/)+src\/main\/frontend\//, '').replace(/\.story\.tsx$/, '');

   async function resolve(storyId: string): Promise<ComponentType<Record<string, unknown>> | undefined> {
     const sep = storyId.lastIndexOf('/');
     const [storyPath, exportName] = [storyId.slice(0, sep), storyId.slice(sep + 1)];
     const file = Object.keys(stories).find((f) => idOf(f) === storyPath || idOf(f).endsWith(`/${storyPath}`));
     return file ? (await stories[file]())[exportName] : undefined;
   }

   const rootElement = document.getElementById('root') as HTMLElement;
   let root: Root | undefined;

   Object.assign(window, {
     mount: async ({ story, props }: { readonly story: string; readonly props?: Record<string, unknown> }) => {
       const Story = await resolve(story);
       if (!Story) throw new Error(`Unknown story: ${story}`);
       root ??= createRoot(rootElement); // reused, so update() reconciles and keeps state
       flushSync(() =>
         root?.render(
           <StrictMode>
             <Story {...props} />
           </StrictMode>,
         ),
       );
     },
     unmount: async () => {
       root?.unmount();
       root = undefined;
     },
   });
   ```

5. **A story for the sample view** — stories live **outside `views/`** (every `.tsx` under `views/` becomes a
   route): `src/main/frontend/stories/HomeView.story.tsx`:

   ```tsx
   import HomeView from 'Frontend/views/@index';

   /** The home view as first rendered. */
   export const Default = () => <HomeView />;
   ```

   (Vitest's coverage `exclude` above already leaves `*.story.tsx` out of the 80% bar.)

6. **`<web-module>/playwright.config.ts`**:

   ```ts
   import { defineConfig, devices } from '@playwright/test';

   export default defineConfig({
     testDir: './e2e',
     forbidOnly: !!process.env.CI,
     retries: process.env.CI ? 2 : 0,
     reporter: process.env.CI ? 'list' : 'html',
     projects: [
       {
         name: 'components',
         testDir: './e2e/components',
         use: {
           ...devices['Desktop Chrome'],
           baseURL: 'http://localhost:5174/playwright/gallery/index.html',
           serviceWorkers: 'block',
           reuseContext: true,
         },
       },
     ],
     webServer: {
       command: 'npx vite --config playwright/vite.config.ts',
       url: 'http://localhost:5174/playwright/gallery/index.html',
       reuseExistingServer: !process.env.CI,
     },
   });
   ```

   Screen and flow tests against the running Spring Boot app are a later addition (a second project whose
   `webServer` starts the app); the scaffold ships the component project only.

7. **The sample spec** — `<web-module>/e2e/components/HomeView.spec.ts`; Lumo's dark variant is the `theme="dark"`
   attribute, so the matrix sets it per cell:

   ```ts
   import { expect, test } from '@playwright/test';
   import { captureUiMatrix } from '../support/ui-screenshots';

   test('HomeView renders the greeting', async ({ mount }) => {
     const view = await mount('HomeView/Default');
     await expect(view.getByText('Hello from <project-name>')).toBeVisible();
   });

   test('HomeView default state across the matrix', async ({ page, mount }, testInfo) => {
     await captureUiMatrix(page, { kind: 'screen', id: 'HomeView', state: 'default' }, () => mount('HomeView/Default'), {
       accessibility: { testInfo, include: '#root' },
       onVariant: (p, _direction, theme) =>
         p.evaluate((dark) => document.documentElement.setAttribute('theme', dark ? 'dark' : ''), theme === 'dark'),
     });
   });
   ```

## Step 5 — `package.json` scripts, ESLint, and Prettier

**`package.json`** — merge these into whatever `vaadin:prepare-frontend` already wrote (it owns the
`dependencies`/`vaadin`/`overrides` blocks; never hand-edit those), adding only `scripts` and the devDependencies
below:

```json
{
  "scripts": {
    "test": "vitest run",
    "test:watch": "vitest",
    "coverage": "vitest run --coverage",
    "lint": "eslint src/main/frontend",
    "test:ui": "playwright test",
    "format": "prettier --check src/main/frontend/**/*.{ts,tsx}"
  },
  "devDependencies": {
    "vitest": "<resolved>",
    "@vitest/coverage-v8": "<resolved, matches vitest>",
    "@testing-library/react": "<resolved>",
    "@testing-library/jest-dom": "<resolved>",
    "jsdom": "<resolved>",
    "eslint": "<resolved>",
    "@eslint/js": "<resolved>",
    "typescript-eslint": "<resolved>",
    "eslint-plugin-react-hooks": "<resolved>",
    "eslint-config-prettier": "<resolved>",
    "prettier": "<resolved>",
    "@playwright/test": "<resolved>",
    "@axe-core/playwright": "<resolved>"
  }
}
```

`format` is scoped to `.ts`/`.tsx` under `src/main/frontend` deliberately — running Prettier over the whole
frontend directory also flags the framework-generated `index.html`, which is real but not useful noise.

**`<web-module>/eslint.config.js`** (flat config — this catalog's TypeScript skills all use flat config, not
`.eslintrc`):

```js
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import eslintConfigPrettier from 'eslint-config-prettier';

export default tseslint.config(
  { ignores: ['src/main/frontend/generated/**', 'node_modules/**', 'coverage/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/main/frontend/**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
    },
  },
  eslintConfigPrettier,
);
```

**`<web-module>/.prettierrc.json`**:

```json
{
  "singleQuote": true,
  "printWidth": 120,
  "trailingComma": "all"
}
```

**`<web-module>/.prettierignore`** — required: Prettier 3.x does not read `.gitignore` on its own, and running it
unignored produces the same generated-file noise the `format` script's own glob already avoids for `lint`:

```
src/main/frontend/generated/
node_modules/
coverage/
target/
```

## Step 6 — `.gitignore` additions

Confirmed against the official `vaadin/skeleton-starter-hilla-react` repository's own `.gitignore` — the frontend
adds exactly three entries beyond what this reactor's `.gitignore` already has for `target/`:

```
<web-module>/node_modules/
<web-module>/src/main/frontend/generated/
<web-module>/vite.generated.ts
<web-module>/test-results/
<web-module>/playwright-report/
# UI test screenshots (.ui-screenshots/), regenerated on every UI test run
.ui-screenshots/
```

The last three come from Step 4b's Playwright UI tests (Playwright's own output, and the UI contract's screenshot
directory at the repository root). `.ui-quality.yml` is committed, never ignored.

`vite.generated.ts` is regenerated ("This file will be overwritten on every run", per its own header comment) on
every `prepare-frontend` run, unlike `vite.config.ts` (which is written once and meant to be edited). Do **not**
ignore `package.json`, `package-lock.json`, `vite.config.ts`, `tsconfig.json`, or `types.d.ts` — all five are
generated once by the plugin, meant to be committed, and safe to hand-edit afterward (`tsconfig.json`/`types.d.ts`
say exactly this in their own generated header comments). `<web-module>/index.html` is likewise committed, not
ignored — Step 3 writes it deliberately so it isn't left at the generic default.

On Gradle the same three entries apply (verified: the `com.vaadin` plugin writes the same `vite.generated.ts`,
`node_modules/` and `src/main/frontend/generated/` into `boot/`); its other outputs land under `boot/build/`, which
the JVM `.gitignore` already ignores.

`.gitignore` is hand-maintained in this catalog's conventions (see `CLAUDE.md`), so show the diff and confirm
before writing.

## Step 7 — Sonar additions (only when `sonar.mode` isn't `none`)

Read `sonar.mode` from the manifest. When it's `none`, skip this step entirely — no `sonar.*` property should
reference a frontend that isn't analysed. Otherwise add to the root pom's existing `sonar.*` properties block
(written by `iru-setup-java-springboot-pom`) — **merge**, don't replace, `sonar.exclusions` in particular, since
`iru-setup-java-springboot-pom` already writes `**/generated-sources/**,**/generated/**` there and this addition
must not clobber it:

```xml
<sonar.sources>src/main/java,<web-module>/src/main/frontend</sonar.sources>
<sonar.tests>src/test/java,<web-module>/src/main/frontend</sonar.tests>
<sonar.test.inclusions>**/*.test.ts,**/*.test.tsx</sonar.test.inclusions>
<sonar.javascript.lcov.reportPaths>${maven.multiModuleProjectDirectory}/<web-module>/coverage/lcov.info</sonar.javascript.lcov.reportPaths>
<!-- Merge into the existing sonar.exclusions value — do not replace it. -->
<sonar.exclusions>**/generated-sources/**,**/generated/**,**/frontend/generated/**</sonar.exclusions>
```

For **Kotlin + Maven**, the server-side roots are `src/main/kotlin` and `src/test/kotlin` (keep `src/main/java`/
`src/test/java` as well if the pom skill listed them for mixed sources): `<sonar.sources>src/main/kotlin,src/main/java,<web-module>/src/main/frontend</sonar.sources>`
and the matching `sonar.tests`.

For **Kotlin + Gradle**, the Sonar Gradle plugin already derives each project's `sonar.sources`/`sonar.tests` from
its source sets, so only the frontend is added — in `boot/build.gradle.kts`, appending to rather than replacing the
derived lists (and merging `sonar.exclusions` with whatever the root `sonar {}` block set):

```kotlin
sonar {
    properties {
        property("sonar.sources", listOf("src/main/kotlin", "src/main/frontend").filter { file(it).exists() })
        property("sonar.tests", listOf("src/test/kotlin", "src/main/frontend").filter { file(it).exists() })
        property("sonar.test.inclusions", "**/*.test.ts,**/*.test.tsx")
        property("sonar.javascript.lcov.reportPaths", layout.projectDirectory.file("coverage/lcov.info").asFile.path)
        property("sonar.exclusions", "**/build/generated/**,**/generated/**,**/frontend/generated/**")
    }
}
```

(Not exercised against a Sonar server during verification — confirm the first analysis lists both the Kotlin and the
TypeScript files under the `boot` project.)

`<web-module>/coverage/lcov.info` is the exact path Step 4's `vitest.config.ts` produces — confirmed by an actual
`vitest run --coverage` run, not assumed. The path is the same on Gradle: it is relative to Vite's root, not to the
build tool's output directory.

## Step 8 — Verify

Delegate both checks to the `iru-gate-runner` agent so a Maven/npm log doesn't flood this context:

```
Agent({
  description: "Verify Hilla frontend build",
  subagent_type: "iru-gate-runner",
  prompt: "Run `mvn -q -pl <web-module> -am vaadin:prepare-frontend` from the repository root. Report only
    pass/fail and, on failure, the first real error.",
  run_in_background: false
})
```

then, in `<web-module>`:

```
Agent({
  description: "Verify Hilla frontend tests",
  subagent_type: "iru-gate-runner",
  prompt: "In <repository-root>/<web-module>, run `npm ci` then `npx vitest run --coverage`. Report only
    pass/fail, the test count, the coverage summary line, and whether <web-module>/coverage/lcov.info was
    produced.",
  run_in_background: false
})
```

then the UI tests (Step 4b), still in `<web-module>`:

```
Agent({
  description: "Verify Hilla UI tests",
  subagent_type: "iru-gate-runner",
  prompt: "In <repository-root>/<web-module>, run `CI=1 npx playwright test` with
    UI_SCREENSHOTS_DIR=<repository-root>/.ui-screenshots. Report only pass/fail per test, whether the failure was
    an environment problem (browser executable missing, web server not starting) rather than an assertion, the
    files written under .ui-screenshots/, and any accessibility violation rule ids.",
  run_in_background: false
})
```

A missing Chromium is **unverified** (tell the user `npx playwright install chromium`; never download it here),
not a failure.

On **Gradle**, the first gate's command is `./gradlew :boot:vaadinPrepareFrontend` (it is not part of
`./gradlew build`'s task graph, so it must be run by name); the second gate is unchanged.

**Ordering matters and is easy to get backwards**: `vitest.config.ts` (and `vite.config.ts`) import
`./vite.generated.ts`, which itself imports compiled helper scripts from `<web-module>/target/plugins/...` (Gradle:
`boot/build/plugins/...` — verified in the Gradle-generated `vite.generated.ts`) —
files that only exist after at least one Maven build has reached `vaadin:prepare-frontend`. Running `npx vitest`
before any Maven build (e.g. against a freshly cloned repository with only `target/` gitignored away) fails with a
module-not-found error that has nothing to do with the tests themselves. Always run the `mvn ... prepare-frontend`
gate before the `vitest` gate, in that order, both here and in the generated CI workflow (see
`iru-setup-java-springboot-github-workflows`).

If a production bundle build is wanted as part of verification, also run (via the same `iru-gate-runner` pattern)
`mvn -q -pl <web-module> -am package -Pproduction -DskipTests` (Gradle: `./gradlew :boot:build
-Pvaadin.productionMode=true -x test -x integrationTest`) — expect `npm install` to run for real the first
time views exist under `src/main/frontend/views/` (confirmed: with an empty `views/` directory the plugin decides
"a production mode bundle build is not needed" and skips npm entirely, which is correct but would give a false
sense that the toolchain works before any real frontend code exists to prove it).

## Step 9 — Report

Summarize:

- The language and build tool read from the manifest, and for Kotlin whether any `@BrowserCallable` service was
  written and that its TypeScript signatures came out with the expected required/optional fields.
- The web module written into (`boot`, unless Step 1 found a reason to use another; Gradle: `:boot`), and why.
- The resolved `vaadin.version` (Gradle: the catalog's `vaadin` version), and whether the root pom's / catalog's
  Vaadin wiring already existed (owned by
  `iru-setup-java-springboot-pom`) or had to be filled in by this skill.
- Every npm package version resolved, separating live lookups from the recorded fallbacks actually used.
- Whether Flow views were requested, and what that added.
- Whether the frontend was scaffolded from scratch or updated, and files written versus already present per Step
  1's answer (**created / updated / replaced / kept as is**), with each mismatch left in a kept file.
- Whether `sonar.mode` triggered the Sonar additions, and to which properties.
- The result of Step 8's gate runs, including the resolved `<web-module>/coverage/lcov.info` path and the UI-test
  run (captures under `.ui-screenshots/screen/HomeView/`, or **unverified** with the reason).
- `.ui-quality.yml` with its action (**created / updated / replaced / kept as is**) and the `accessibility` level
  written, and where it came from (`args`, the manifest, or the `enforce` default).
- Whether Node was found globally or the plugin downloaded its own into `~/.vaadin` (first-run CI time cost).

Warn explicitly:

- **Review every generated frontend file before committing** — `package.json`'s `dependencies`/`vaadin`/
  `overrides` blocks in particular are plugin-owned and will be silently rewritten on the next
  `vaadin:prepare-frontend`; don't hand-edit them expecting the edit to stick.
- If `sonar.mode` isn't `none`, the SonarCloud/SonarQube project must already exist for the new `sonar.sources`/
  `sonar.tests` to have somewhere to report into — this skill doesn't create it.
- `vaadin:build-frontend` needs Node; state plainly whether this run used a global install or triggered Vaadin's
  own download into `~/.vaadin`, since the latter adds real time to a machine's first build.
- A full `-Pproduction` build (real npm install + Vite production bundle) can take noticeably longer than a plain
  `mvn verify` — mention this if it wasn't run as part of Step 8, so the user isn't surprised the first time CI
  does run it.

## Resolution table

| Placeholder | Resolved from |
|---|---|
| `<web-module>` | The manifest's `modules:` entry for `boot` (Step 1) |
| `<project-name>` | `springboot-stack.yml`'s `project.artifactId` (or `project.description`, whichever reads better as UI copy) |
| `<base-package>` | `springboot-stack.yml`'s `project.basePackage` (only used if Flow views were requested) |
| `<resolved>` (npm versions) | Live npm registry lookups in Step 1, falling back to the pinned versions listed there |
| `${vaadin.version}` | The root pom's `<vaadin.version>` property (Step 1: read if present, written if not — see `iru-setup-java-springboot-pom`); on Gradle the catalog's `vaadin` version |
