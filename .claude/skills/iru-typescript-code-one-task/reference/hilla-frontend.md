# Vaadin Hilla frontend

Conventions specific to the TypeScript/React frontend of a Vaadin Hilla project (`src/main/frontend/`, hosted
inside a Spring Boot Maven module). Everything in `code-style.md`, `tsdoc.md`, `testing.md`, and `react.md`
(function components, hooks rules, state colocation) still applies — Hilla's frontend is React underneath; this
file adds what's specific to Hilla's file routing, generated clients, and its Vaadin component library.

## File routing: `src/main/frontend/views/*.tsx`

- **A view is a file under `src/main/frontend/views/`; the file's path is its route**, the same file-based-
  routing idea as Expo Router but for Hilla's own file-system router (`@vaadin/hilla-file-router`) —
  `views/orders/@index.tsx` is `/orders`, `views/orders/@{id}.tsx` is the dynamic `/orders/:id` route,
  `views/orders/layout.tsx` wraps everything under `orders/` in a shared layout.
- A view file's **default export is the view component**, matching Hilla's file-router convention (the same
  documented default-export exception `react-native.md` makes for Expo Router route files) — export any
  supporting type/helper from the same file as a named export instead.
- **Route metadata** (title, requires-login/roles, menu placement) is attached via the file router's `config`
  export (`export const config: ViewConfig = { title: "Orders", loginRequired: true };`) next to the default
  export — don't hardcode navigation/menu entries elsewhere when the file router already derives them from this.
- Shared UI, hooks, and non-view logic live outside `views/` (e.g. `src/main/frontend/components/`,
  `src/main/frontend/hooks/`) and are imported into the view file, keeping the view itself thin — the same
  "route file stays thin, logic lives in a tested module" principle as `react-native.md`.

## Generated endpoint clients (`generated/`) are read-only

- **Never hand-edit anything under `src/main/frontend/generated/`.** It's produced by the Hilla Maven/Gradle
  plugin from the backend's `@BrowserCallable`/`@Endpoint` Java services at build time and is regenerated on
  every backend build — any change made by hand is silently overwritten. If the generated client is missing a
  method or has the wrong shape, the fix is on the **backend** endpoint (its Java signature, its DTOs), not the
  generated TypeScript.
- **Import the generated client as-is** (`import { OrderEndpoint } from "Frontend/generated/endpoints";` or the
  project's actual generated import path) and call its methods like any other typed async function — Hilla
  generates full TypeScript types from the Java method signatures and DTOs, so the call site gets the same
  strict typing `code-style.md` requires without extra casting.
- A task that needs a *new* backend-exposed operation is a backend (Java) task, not a frontend one — report it as
  out of scope (Step 5 of `SKILL.md`) rather than stubbing a fake client method to unblock the frontend.
- The generated barrel typically also exposes typed **endpoint subscription** helpers for a `Flux`-backed reactive
  endpoint; treat those the same as any other async data source in `react.md`'s "server/remote state is not
  component state" guidance — subscribe in a dedicated hook, not ad hoc inside a component body.

## `@vaadin/react-components`

- **Use Vaadin's own React component wrappers** (`import { Button, Grid, TextField, Notification } from
  "@vaadin/react-components";`) for UI elements this catalog's Hilla scaffold ships with, instead of bare HTML
  elements or a different component library — they match the rest of a Hilla/Vaadin application's look and carry
  built-in accessibility and theming support.
- **`<Grid>` bound to a generated endpoint's list method** via Hilla's own data-provider pattern
  (`GridDataProviderCallback`/the `@vaadin/hilla-react-crud` `useGridDataProvider` or the equivalent the project
  already uses) rather than fetching a full list into component state and paginating it client-side — Hilla's
  grid integration is built around lazy, server-paginated loading. Non-tabular lists use `<VirtualList>` with one
  item component (UI contract §5) — never a plain `map` over endpoint data.
- Views stay thin screens; reusable presentational components live in `components/` with props in and
  `on<Intent>` callbacks out, so they can be rendered from stories (`ui-implementation.md`, Hilla section).
- Form binding: prefer Hilla's `useForm`/`Binder` integration (`@vaadin/hilla-react-form`) bound to the endpoint's
  generated DTO model for a form backed by an endpoint call, so client-side validation stays in sync with the
  backend's Java Bean Validation annotations, instead of hand-rolling field-by-field `useState` + manual
  validation.

## Testing: Vitest browser-mode config from Vaadin docs

- **This catalog's Hilla scaffold configures Vitest in browser mode** (`test.browser.enabled: true` in
  `vitest.config.ts`, per Vaadin's own documented setup for testing `@vaadin/react-components`) rather than the
  default jsdom/happy-dom environment — Vaadin's web components rely on real custom-element and Shadow DOM
  behavior that a simulated DOM doesn't fully reproduce. Don't switch a Hilla project's test environment back to
  jsdom to "simplify" a test; follow the project's own `vitest.config.ts` and Vaadin's published guidance if the
  config needs to change.
- Query into a Vaadin web component's Shadow DOM the way Testing Library's browser-mode setup expects (per the
  project's existing tests) — a query that works for a plain HTML element doesn't automatically pierce a custom
  element's shadow root, and `react.md`/`testing.md`'s `getByRole` guidance still applies where the component
  exposes the right ARIA role.
- **Mock the generated endpoint client** for a unit test that exercises a view/component without needing a real
  backend running (`vi.mock("Frontend/generated/endpoints", () => ({ OrderEndpoint: { list: vi.fn() } }))`),
  matching `testing.md`'s "mock what you don't own" rule — never point a unit test at a live Spring Boot backend.
- A genuinely end-to-end Hilla test (real backend, real generated client) belongs to the project's integration/E2E
  suite, not this skill's unit tests — writing one is out of scope unless the task explicitly asks for it.
- **UI tests with screenshots** are Playwright component tests against a standalone Vite gallery (stories under
  `src/main/frontend/stories/`, never under `views/`), capturing the `.ui-screenshots/` matrix with axe checks —
  `ui-implementation.md`. Vitest browser mode stays for behaviour tests.
