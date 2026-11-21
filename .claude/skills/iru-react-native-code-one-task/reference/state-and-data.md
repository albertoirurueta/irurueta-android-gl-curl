# State and data

Read when the task adds or changes state, a hook, context, data fetching, persistence, or offline behaviour. The
rules are the catalog's React state rules (`code-style.md`) applied to a device that suspends, loses network, and
gets killed in the background.

## Where state lives — follow what's there

1. **Local UI state** (`useState`/`useReducer`) — form fields, toggles, the selected tab. Colocated, derived values
   computed at render, `useReducer` once transitions depend on each other.
2. **Navigation state** — the current screen and its params belong to Expo Router / React Navigation; don't mirror
   them into state or context. Pass ids in params, load the entity on the screen.
3. **Server state** — whatever the project already uses: **TanStack Query** (`@tanstack/react-query`), SWR, RTK
   Query, Apollo, or a hand-written hook layer. Use it for new reads/writes: `useQuery` with a stable, serializable
   `queryKey` (`["orders", orderId]`), `useMutation` + `queryClient.invalidateQueries` (or a targeted
   `setQueryData`) on success. Don't copy query results into `useState`. Don't add a server-state library for one
   task — with none installed, write a small typed hook returning a discriminated union (`{ status: "loading" } |
   { status: "success"; data } | { status: "error"; error }`) with an `AbortController` cancelled on unmount.
   For a list screen, the view model hook exposes the **whole list** as immutable item UI states with stable ids
   plus paging flags (`{ items, loadingMore, endReached }`) — `useInfiniteQuery` + `select`, or the store — and
   keeps selection/expansion keyed by id there, never inside a row (`ui-implementation.md`, "Collections").
4. **Shared client state** — React context for low-frequency values (theme, session, feature flags), or the
   project's store (Zustand, Redux Toolkit, Jotai, MobX). Don't introduce a store; don't put high-frequency values
   (scroll position, text input) in a context that re-renders the whole tree — split the context or use a
   selector-based store already present.

## React Native specifics

- **App lifecycle**: an app can be backgrounded and resumed hours later. Refetch-on-focus behaviour is wired via
  `AppState` (`focusManager.setEventListener` for TanStack Query; the template's `useAppState` hook if any);
  screen-level focus via `useFocusEffect` (React Navigation / Expo Router) — not `useEffect`, which doesn't re-run
  when a stacked screen regains focus.
- **Network state**: if `@react-native-community/netinfo` is installed, use it (`onlineManager` for TanStack Query)
  rather than inferring from a failed fetch. Treat every request as able to fail: timeouts (`AbortSignal.timeout`),
  retries only for idempotent requests.
- **Effects clean up**: every subscription, listener (`AppState.addEventListener` returns `.remove()`), timer, and
  in-flight request started in an effect is released in its cleanup — an unmounted screen that still calls
  `setState` is a leak and a test failure under fake timers.
- **Validate API responses at the boundary** (a schema library the project already uses, or a type guard) — the JS
  bundle is long-lived on users' devices and talks to backends that change underneath it.

## Persistence

| Need | Use (if installed) | Not for |
|---|---|---|
| Small non-sensitive key/value (preferences, last tab, onboarding flag) | `@react-native-async-storage/async-storage` (async) or `react-native-mmkv` (sync, fast; `createMMKV()` in v4) — whichever the project has | Tokens, passwords, PII — unencrypted on disk. |
| Secrets (tokens, refresh tokens, keys) | `expo-secure-store` (expo) or `react-native-keychain` (metro) — see `security.md` | Large data (SecureStore values are size-limited; keychain items are slow). |
| Structured / queryable / offline-first data | `expo-sqlite`, `op-sqlite`, WatermelonDB, Realm — whatever exists | Ad hoc JSON blobs in AsyncStorage that grow unbounded. |
| Files, downloads, caches | `expo-file-system` / `react-native-fs` / `react-native-blob-util` | — |
| Persisted server cache | TanStack Query's `persistQueryClient` with the project's storage adapter | Anything sensitive. |

- **Version persisted shapes.** Data written today is read by the next app version: store a `version` field (or use
  the persister's `buster`) and migrate or drop on mismatch; never assume the stored JSON matches the current type —
  parse and validate it like an API response.
- Keys are namespaced constants (`STORAGE_KEYS.lastTab = "app.lastTab"`), not string literals scattered across files.
- Wrap storage behind a small typed module (`src/lib/storage.ts`) so tests mock one boundary and the backing store
  can change.

## Offline

Only when the task asks for offline behaviour:

- Decide per mutation: queue-and-retry (idempotent, ordered — TanStack Query's paused mutations with a persister,
  or the project's queue) vs. block with a clear offline message.
- Optimistic updates roll back on failure and reconcile with the server response; show stale/offline state in the
  UI rather than pretending data is fresh.
- Conflicts are a product decision — if the task doesn't say what wins, that's a question for the user (Step 5 of
  `SKILL.md`).

## Testing state and data

- Hooks with `renderHook` from RNTL (`await renderHook(...)` on RNTL 14+, `testing.md`), wrapped in the providers
  they need (a fresh `QueryClient` per test with `retry: false`).
- Mock the network at the client boundary (the project's API module, or `fetch` via `jest.spyOn(global, "fetch")`
  / MSW if already used) and storage at the module boundary. AsyncStorage ships an official Jest mock —
  `jest.mock("@react-native-async-storage/async-storage", () => require("@react-native-async-storage/async-storage/jest"))`
  on v3 (the `./jest` export), `…/async-storage/jest/async-storage-mock` on v1/v2; `react-native-mmkv` v4's
  `createMMKV()` returns an in-memory mock automatically under Jest (`JEST_WORKER_ID` set), so it needs no mock.
- Cover the loading, success, error, and empty states, plus the offline/stale branch when the task adds one.
