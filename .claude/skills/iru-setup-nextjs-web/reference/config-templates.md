# Configuration templates (Steps 4–8)

Same `File:` / `when:` convention as `app-router-samples.md`. Extra tags used here:

| Tag | Applies when |
|---|---|
| `app` / `pages` | `router: app` / `router: pages` |
| `server` / `static` | `output: server` / `output: static` |
| `cc` | `cache-components: yes` |
| `internal` | `output: server` **and** `distribution: internal` (container image) |
| `e2e` | `e2e: playwright` |
| `typedoc` | `docs-tool: typedoc` |
| `sonar` | `sonar: cloud` or `self-hosted` |

## `next.config.ts` (Step 4)

Replace the scaffold's `next.config.ts`, **keeping** whatever `create-next-app` itself put there for the chosen
styling (16.4's Tailwind setup writes a `turbopack.rules` block for `@tailwindcss/turbopack` — merge it into the
object below rather than dropping it; with `--no-tailwind` there is nothing to keep). `--cache-components` writes
`cacheComponents: true` plus `partialPrefetching: true`; keep both exactly as written when `cache-components: yes`.

File: `next.config.ts` — when: `server`, `!internal`

```ts
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
};

export default nextConfig;
```

File: `next.config.ts` — when: `internal`

```ts
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Self-contained server in .next/standalone for the Docker image (Step 8).
  output: "standalone",
  poweredByHeader: false,
};

export default nextConfig;
```

File: `next.config.ts` — when: `static`

```ts
import type { NextConfig } from "next";

// Set by the GitHub Pages deploy job (actions/configure-pages `base_path`): "/<repository>" for a project site,
// empty for <owner>.github.io or a custom domain. Unset locally and in tests, so the app serves from "/".
const basePath = process.env.PAGES_BASE_PATH ?? "";

const nextConfig: NextConfig = {
  output: "export",
  basePath,
  // The default image optimizer needs a server; an export must ship images as-is.
  images: { unoptimized: true },
  // Writes books/index.html instead of books.html, which every static host resolves for /books/.
  trailingSlash: true,
  // <Link>, router navigation and /_next assets get basePath automatically; next/image `src` strings and raw
  // public/ URLs do not — prefix those with process.env.NEXT_PUBLIC_BASE_PATH.
  env: { NEXT_PUBLIC_BASE_PATH: basePath },
};

export default nextConfig;
```

`assetPrefix` is deliberately not set: `basePath` already prefixes `/_next/` assets, and `assetPrefix` is only for
serving those assets from a different origin (a CDN).

## Unit tests — Vitest (Step 5)

File: `vitest.config.mts`

```ts
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  // Vite 8 resolves the tsconfig "paths" alias (@/*) natively.
  resolve: { tsconfigPaths: true },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./vitest.setup.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov", "json-summary"],
      reportsDirectory: "coverage",
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/**/*.test.{ts,tsx}",
        "src/**/*.d.ts",
        // Framework wiring: next/font and <html>/<body> can't render under jsdom; `next build` and the
        // Playwright suite exercise it.
        "src/app/layout.tsx",
        "src/pages/_app.tsx",
        "src/pages/_document.tsx",
      ],
      thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 },
    },
  },
});
```

File: `vitest.setup.ts`

```ts
import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// The real package throws outside a React Server environment; Server Component modules import it.
vi.mock("server-only", () => ({}));

// Client hooks need a mounted App Router; tests override this with their own vi.mock when they assert calls.
vi.mock("next/navigation", async (importOriginal) => ({
  ...(await importOriginal<typeof import("next/navigation")>()),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

afterEach(() => {
  cleanup();
});
```

`notFound()`/`redirect()` stay real (spread from `importOriginal`), so tests assert their `digest`
(`NEXT_HTTP_ERROR_FALLBACK;404`, `NEXT_REDIRECT;...`) as `iru-nextjs-code-one-task/reference/testing.md` describes.

## Lint and format (Step 6)

File: `eslint.config.mjs`

```js
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier/flat";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Last, so formatting rules that would fight Prettier are switched off.
  prettier,
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Generated reports and docs:
    "coverage/**",
    "playwright-report/**",
    "test-results/**",
    // Component-gallery glue (type-checked by tsc, not linted):
    "playwright/**",
    "docs/**",
  ]),
]);

export default eslintConfig;
```

File: `.prettierrc.json`

```json
{
  "printWidth": 100
}
```

File: `.prettierignore`

```
.next
out
build
coverage
playwright-report
test-results
playwright
docs
next-env.d.ts
package-lock.json
pnpm-lock.yaml
# Rewritten by `next dev` (create-next-app's agent rules block) — formatting it only creates churn.
AGENTS.md
CLAUDE.md
```

## Playwright (Step 7)

The `webServer` always runs the **production** build (never `next dev`), and under `CI` never reuses a server
already listening on the port. Port 3100 keeps it clear of a developer's `next dev` on 3000.

File: `playwright.config.ts` — when: `e2e`, `server`

```ts
import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.E2E_PORT ?? 3100);
const baseURL = `http://localhost:${port}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: { baseURL, trace: "on-first-retry" },
  // Chromium only: `npx playwright install --with-deps chromium` is what CI provisions.
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run build && npm run start -- --port ${port}`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 240_000,
  },
});
```

File: `playwright.config.ts` — when: `e2e`, `static`

```ts
import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.E2E_PORT ?? 3100);
const baseURL = `http://localhost:${port}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: { baseURL, trace: "on-first-retry" },
  // Chromium only: `npx playwright install --with-deps chromium` is what CI provisions.
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    // The static export served as files, exactly as Pages serves it (PAGES_BASE_PATH unset => root).
    command: `npm run build && npm run start -- --listen ${port}`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 240_000,
  },
});
```

Replace `npm run` with `pnpm run`/`yarn` per `package-manager`.

## UI contract (Step 7, `e2e: playwright`)

Verified on a `create-next-app` 16.4.2 app (React 19.3, Tailwind 4, Vite 8, `@playwright/test` 1.64.1,
`@axe-core/playwright` 4.14.0): the component project, the screen spec and `next typegen && tsc --noEmit` pass.

File: `.ui-quality.yml` (repository root) — when: always

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

`accessibility` is the resolved value (default `enforce`).

File: `e2e/support/ui-screenshots.ts` — when: `e2e` — exactly this skill's `reference/ui-screenshots.ts`, then
`npx prettier --write e2e/support/ui-screenshots.ts`.

`playwright.config.ts` — when: `e2e`: add the `components` project before `chromium`, give `chromium`
`testIgnore: "components/**"`, and make `webServer` an array whose first entry is the gallery (port 3200 keeps it
clear of the production server on 3100 and a developer's `next dev` on 3000):

```ts
  projects: [
    {
      name: "components",
      testDir: "./e2e/components",
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://localhost:3200/playwright/gallery/index.html",
        serviceWorkers: "block",
        reuseContext: true,
      },
    },
    { name: "chromium", testIgnore: "components/**", use: { ...devices["Desktop Chrome"] } },
  ],
  webServer: [
    {
      command: "npx vite --config playwright/vite.config.ts",
      url: "http://localhost:3200/playwright/gallery/index.html",
      reuseExistingServer: !process.env.CI,
    },
    {
      // …the existing production-build entry, unchanged
    },
  ],
```

File: `playwright/vite.config.ts` — when: `e2e` (drop the `tailwindcss` import and plugin for
`styling: css-modules`)

```ts
// Standalone Vite server for the component gallery: Next.js has no Vite dev server to host it.
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const here = (path: string): string => fileURLToPath(new URL(path, import.meta.url));

export default defineConfig({
  root: here(".."),
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: [
      { find: /^@\//, replacement: here("../src/") },
      // The gallery has no Next.js router or image optimizer: stand-ins record navigation instead.
      { find: /^next\/(link|navigation)$/, replacement: here("./gallery/next-stubs.tsx") },
      { find: /^next\/image$/, replacement: here("./gallery/next-image.tsx") },
    ],
  },
  server: { port: 3200, strictPort: true },
});
```

File: `playwright/gallery/index.html` — when: `e2e`

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

File: `playwright/gallery/main.tsx` — when: `e2e` (`router: pages`: import `../../src/styles/globals.css`)

```tsx
/// <reference types="vite/client" />
// Component gallery for Playwright's built-in `mount()` fixture: renders one `*.story.tsx` export into #root.
import { StrictMode, type ComponentType } from "react";
import { flushSync } from "react-dom";
import { createRoot, type Root } from "react-dom/client";
import "../../src/app/globals.css";

type StoryModule = Record<string, ComponentType<Record<string, unknown>>>;
const stories = import.meta.glob<StoryModule>("../../src/**/*.story.tsx");
const idOf = (file: string): string =>
  file.replace(/^(\.\.\/)+src\//, "").replace(/\.story\.tsx$/, "");

async function resolve(storyId: string): Promise<ComponentType<Record<string, unknown>> | undefined> {
  const sep = storyId.lastIndexOf("/");
  const [storyPath, exportName] = [storyId.slice(0, sep), storyId.slice(sep + 1)];
  const file = Object.keys(stories).find((f) => idOf(f) === storyPath || idOf(f).endsWith(`/${storyPath}`));
  return file ? (await stories[file]())[exportName] : undefined;
}

const rootElement = document.getElementById("root") as HTMLElement;
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

File: `playwright/gallery/next-stubs.tsx` — when: `e2e`

```tsx
// Stand-ins for Next.js modules that need the Next router or server, aliased by playwright/vite.config.ts.
// Navigation is recorded on <body data-last-navigation> so a spec can assert it without leaving the gallery.
import type { AnchorHTMLAttributes, ImgHTMLAttributes, JSX, MouseEvent } from "react";

type Href = string | { readonly pathname?: string | null };
const pathOf = (href: Href): string => (typeof href === "string" ? href : (href.pathname ?? ""));
const record = (href: Href): void => {
  document.body.dataset.lastNavigation = pathOf(href);
};

interface LinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href"> {
  readonly href: Href;
  readonly prefetch?: boolean | null;
  readonly replace?: boolean;
  readonly scroll?: boolean;
}

/** `next/link`: a plain anchor that records the navigation instead of performing it. */
export default function Link({ href, prefetch, replace, scroll, onClick, ...rest }: LinkProps): JSX.Element {
  void prefetch;
  void replace;
  void scroll;
  const handle = (event: MouseEvent<HTMLAnchorElement>): void => {
    event.preventDefault();
    onClick?.(event);
    record(href);
  };
  return <a href={pathOf(href)} onClick={handle} {...rest} />;
}

/** `next/navigation` hooks. */
export const useRouter = () => ({
  push: record,
  replace: record,
  back: () => record("back"),
  forward: () => record("forward"),
  refresh: () => undefined,
  prefetch: () => undefined,
});
export const usePathname = (): string => "/";
export const useSearchParams = (): URLSearchParams => new URLSearchParams();
export const useParams = (): Record<string, string> => ({});
export const redirect = record;
export const notFound = (): never => {
  throw new Error("notFound() called in a story");
};

type ImageProps = Omit<ImgHTMLAttributes<HTMLImageElement>, "src"> & {
  readonly src: string | { readonly src: string };
  readonly alt: string;
  readonly fill?: boolean;
  readonly priority?: boolean;
  readonly quality?: number;
};

/** `next/image`: a plain `<img>`. */
export function Image({ src, alt, fill, priority, quality, ...rest }: ImageProps): JSX.Element {
  void fill;
  void priority;
  void quality;
  return <img src={typeof src === "string" ? src : src.src} alt={alt} {...rest} />;
}
```

File: `playwright/gallery/next-image.tsx` — when: `e2e`

```tsx
export { Image as default } from "./next-stubs";
```

File: `src/components/like-button.story.tsx` — when: `e2e`

```tsx
import { LikeButton } from "./like-button";

/** Not liked yet. */
export const Default = () => <LikeButton label="Refactoring" />;
```

File: `e2e/components/like-button.spec.ts` — when: `e2e`

```ts
import { expect, test } from "@playwright/test";

import { captureUiMatrix } from "../support/ui-screenshots";

test("LikeButton toggles its pressed state", async ({ mount }) => {
  const component = await mount("like-button/Default");
  const button = component.getByRole("button", { name: "Like Refactoring" });
  await expect(button).toHaveAttribute("aria-pressed", "false");
  await button.click();
  await expect(button).toHaveAttribute("aria-pressed", "true");
});

test("LikeButton default state across the matrix", async ({ page, mount }, testInfo) => {
  await captureUiMatrix(page, { kind: "component", id: "LikeButton", state: "default" }, () => mount("like-button/Default"), {
    accessibility: { testInfo, include: "#root" },
  });
});
```

File: `e2e/home.spec.ts` — when: `e2e` (static export: the same — `/` is exported)

```ts
import { expect, test } from "@playwright/test";

import { captureUiMatrix } from "./support/ui-screenshots";

test("Home across the matrix", async ({ page }, testInfo) => {
  await captureUiMatrix(
    page,
    { kind: "screen", id: "Home", state: "default" },
    async () => {
      await page.goto("/");
      await expect(page.getByRole("heading", { level: 1, name: "Welcome" })).toBeVisible();
      return page;
    },
    { accessibility: { testInfo } },
  );
});
```

The screen capture uses the home page because it is deterministic: the book list changes while `books.spec.ts`
adds books through the Server Action in parallel.

## TypeDoc (Step 8, `docs-tool: typedoc`)

File: `typedoc.json` — when: `typedoc`

```json
{
  "$schema": "https://typedoc.org/schema.json",
  "entryPoints": ["src"],
  "entryPointStrategy": "expand",
  "exclude": ["**/*.test.ts", "**/*.test.tsx"],
  "out": "docs/api",
  "name": "<App title> API",
  "validation": { "notExported": false }
}
```

`validation.notExported: false` is required on Next.js: the global `PageProps`/`LayoutProps`/`RouteContext` helpers
live in `.next/types/routes.d.ts` and TypeDoc otherwise warns that they are referenced but not documented.

## Sonar (Step 8, `sonar` ≠ `none`)

File: `sonar-project.properties` — when: `sonar`

```properties
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.host.url=<sonar-host-url>
sonar.sources=src
sonar.tests=src
sonar.test.inclusions=**/*.test.ts,**/*.test.tsx
sonar.exclusions=**/node_modules/**,**/.next/**,**/out/**
sonar.coverage.exclusions=src/app/layout.tsx,src/pages/_app.tsx,src/pages/_document.tsx
sonar.javascript.lcov.reportPaths=coverage/lcov.info
```

Omit `sonar.organization` for a self-hosted server without organizations. `sonar.coverage.exclusions` mirrors the
Vitest `coverage.exclude` wiring files so Sonar and the local gate agree.

## Container image (Step 8, `internal`)

File: `Dockerfile` — when: `internal`

```dockerfile
# syntax=docker/dockerfile:1
ARG NODE_VERSION=24

FROM node:${NODE_VERSION}-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:${NODE_VERSION}-slim AS builder
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:${NODE_VERSION}-slim AS runner
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
# The standalone server plus the assets it does not copy itself, owned by the image's non-root `node` user so
# the server can write its .next/cache (ISR, image cache).
COPY --from=builder --chown=node:node /app/public ./public
COPY --from=builder --chown=node:node /app/.next/standalone ./
COPY --from=builder --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:' + process.env.PORT + '/api/health').then((r) => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"]
CMD ["node", "server.js"]
```

`<package-manager>` other than npm: copy `pnpm-lock.yaml`/`yarn.lock` instead and run `corepack enable &&
pnpm install --frozen-lockfile` (or `yarn install --immutable`) in `deps`, `pnpm run build`/`yarn build` in
`builder` (unverified here — only npm was exercised). `NODE_VERSION` defaults to the resolved `node-version`.

File: `.dockerignore` — when: `internal`

```
.git
.github
node_modules
.next
out
coverage
playwright-report
test-results
docs
e2e
.env*
!.env.example
Dockerfile
.dockerignore
*.md
```
