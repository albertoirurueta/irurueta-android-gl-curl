# Shared logic — coroutines, networking, serialization, dates, persistence, DI, ViewModels, resources

Conventions for non-UI code in `commonMain`. **Use only libraries the module already depends on** (check
`gradle/libs.versions.toml` and the module's `sourceSets` block); adding one is a blocker to report
(`mandatory-rules.md`). This file tells you how to use each well, not which to add.

## Coroutines and `Flow`

- `kotlinx-coroutines-core` is multiplatform; everything below works in `commonMain`.
- **Dispatchers**:
  - `Dispatchers.Default` — CPU-bound work, every target.
  - `Dispatchers.IO` — blocking I/O. Available in common code (kotlinx.coroutines 1.7+) on JVM/Android and Native,
    but on Native it is an *extension* property: write `import kotlinx.coroutines.IO` alongside
    `import kotlinx.coroutines.Dispatchers` in `commonMain` (verified: compiles for Android and iOS). It does not exist
    on JS/Wasm — if the module ever declares a web target, inject the dispatcher instead.
  - `Dispatchers.Main` — the UI thread (Android main looper; iOS main queue). Not available in JVM/Android host
    tests without `Dispatchers.setMain(testDispatcher)` (see `testing.md`).
  - **Inject** the dispatcher (`private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO`) into any class that
    calls `withContext`/`flowOn`, so tests pass a `TestDispatcher`.
- **Scopes**: `viewModelScope` inside a `ViewModel`; an injected `CoroutineScope` for longer-lived services (an
  application-scoped scope created once at startup and passed in). Never `GlobalScope`, never `runBlocking` in
  production code.
- **Main-safety**: a `suspend` function must be safe to call from the main thread — it switches to `Dispatchers.IO`/
  `Default` internally for blocking or heavy work. This matters doubly on iOS, where Swift calls `suspend` functions
  from the main actor.
- **Streams**: a single async result is a `suspend fun`; a stream of values over time is a `Flow`. Hot UI state is a
  `StateFlow` (with an initial value); one-off events are a `Channel` received as a `Flow` (`receiveAsFlow()`) or
  state flags the UI acknowledges — not a `SharedFlow` with no replay that drops events emitted before collection.
  Convert cold flows to state with `stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)` (name the
  timeout constant).
- **Threading on Kotlin/Native**: the new memory model (default since Kotlin 1.7.20) allows sharing objects across
  threads freely; no `freeze()`. Mutable state shared between coroutines still needs synchronization — a `Mutex`, an
  atomic (`kotlinx.atomicfu` or `kotlin.concurrent.atomics` where the project's Kotlin version has them stable), or
  confinement to one dispatcher. `synchronized`/`@Volatile` from `kotlin.jvm` are not common APIs.

## Networking — Ktor client

- One `HttpClient` per app (or per API), created in common code and injected; closed when its owner is.
- **Engines are platform dependencies**: the module declares e.g. `ktor-client-okhttp` (or `-android`) in
  `androidMain.dependencies` and `ktor-client-darwin` in `iosMain.dependencies`. Common code calls `HttpClient { … }`
  without naming an engine — Ktor picks the one on the target's classpath — or receives an `HttpClientEngine` built
  by an `expect fun`/injected factory when engine configuration (timeouts, certificate pinning — `security.md`) is
  platform-specific.
- **Content negotiation**: `install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }` with
  `ktor-serialization-kotlinx-json`. Configure the `Json` instance once and share it.
- Map transport DTOs (`@Serializable` data classes mirroring the wire format) to domain types at the repository
  boundary; don't leak DTOs into UI state.
- Errors: `expectSuccess = true` (throws `ResponseException` subclasses) or explicit status checks — match the
  module — and translate to the domain's result type at the repository boundary. Timeouts via `HttpTimeout` plugin
  with named constants.
- Base URLs come from configuration (build config / injected), never a literal scattered through call sites.
- Test with `MockEngine` (`ktor-client-mock` in `commonTest`) — see `testing.md`.

## Serialization — kotlinx.serialization

- `@Serializable` on DTOs, requires the `org.jetbrains.kotlin.plugin.serialization` plugin on the module.
- `@SerialName("snake_case")` where the wire name differs; defaults for optional fields; `ignoreUnknownKeys = true`
  on the shared `Json` so a server adding a field doesn't crash old clients.
- Polymorphism: sealed hierarchies with `@Serializable` on each subclass and a class discriminator — explicit, no
  reflection (reflection isn't available on Native).
- Never use Gson/Moshi/Jackson in common code (JVM-only).

## Dates and time

- **kotlinx-datetime** for calendar types (`LocalDate`, `LocalDateTime`, `TimeZone`, `DateTimePeriod`); stdlib
  `kotlin.time` for `Duration` and — on recent Kotlin/kotlinx-datetime (0.7+) — `kotlin.time.Instant` and
  `kotlin.time.Clock` (older code uses `kotlinx.datetime.Instant`/`Clock`; match what the module uses; opt in to
  `ExperimentalTime` where the project's Kotlin version still requires it).
- **Inject a `Clock`** into anything that reads "now", so tests control time. Never call `Clock.System.now()` deep in
  logic.
- Store and transmit instants in UTC; convert to a `TimeZone` only for display.
- Formatting for display: kotlinx-datetime's format builders, or platform formatters behind an interface when
  locale-aware formatting matters (`DateTimeFormatter` / `NSDateFormatter`).

## Persistence

Use the store the project already has:

- **SQLDelight 2** — `.sq` files under `src/commonMain/sqldelight/<package>/`; the plugin generates typed queries
  (generated code: never edit). The `SqlDriver` is platform-specific: `AndroidSqliteDriver` in `androidMain`,
  `NativeSqliteDriver` in `iosMain`, built by a `DriverFactory` (interface or `expect class`) and injected. Schema
  changes need a migration (`.sqm`) — a changed `.sq` table definition without a migration breaks existing installs.
  Flows via `asFlow().mapToList(dispatcher)` from the coroutines extensions.
- **Room (KMP, `androidx.room` 2.7+)** — entities/DAOs/`@Database` in `commonMain`; `@ConstructedBy(AppDatabaseConstructor::class)`
  with `expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>` (the compiler generates the
  actuals via KSP — don't hand-write them); `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`); the database
  builder is platform-specific (needs a `Context` on Android, a documents-directory path on iOS). DAO functions in
  common code must be `suspend` or return `Flow`. KSP is configured per target (`kspAndroid`, `kspIosArm64`,
  `kspIosSimulatorArm64`) in the build — a new DAO needs no build change if those exist. Schema changes need a
  version bump and a `Migration` (or `AutoMigration`).
- **DataStore (KMP)** — `androidx.datastore:datastore-preferences-core` (1.1+); create with
  `PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })`, path from a platform-specific
  function (Android `context.filesDir`, iOS `NSDocumentDirectory`). One `DataStore` instance per file per process —
  make it a singleton in DI. For key-value preferences, **not** for secrets (`security.md`).
- Whatever the store, keep it behind a repository interface in `commonMain` so `commonTest` can use a fake or an
  in-memory implementation.

## Dependency injection

- **Koin** (if the project uses it): modules declared in `commonMain` (`module { singleOf(::SessionRepository) }`),
  platform modules in `androidMain`/`iosMain` (an `expect val platformModule: Module` or a function per platform),
  `startKoin { modules(...) }` called once from each platform's entry point (`Application.onCreate`, and from Swift
  via a Kotlin `fun initKoin()` helper in `iosMain`). `koinViewModel()` / `viewModelOf(::X)` with the
  `koin-compose-viewmodel` artifact for Compose. Prefer constructor injection (`singleOf`, `factoryOf`) over
  `get()` calls inside classes — a class that calls `KoinComponent.get()` is hard to test.
- **Manual DI** (the default when no framework is present): an `AppContainer`/`AppGraph` class in `commonMain` that
  constructs the object graph lazily from platform-provided dependencies (passed in by each platform's entry point).
  Don't introduce a DI framework to solve a task.
- Either way, business classes take dependencies as constructor parameters and never reach for a global.

## ViewModel and lifecycle (multiplatform)

- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel` (and `-viewmodel-compose` for Compose Multiplatform)
  provides `androidx.lifecycle.ViewModel` and `viewModelScope` in `commonMain`. On Android the artifact resolves to
  the AndroidX one, so the same class works in `androidApp`.
- A `ViewModel` exposes `StateFlow<UiState>` and plain functions for user intents (`fun onRefresh()`), launches work
  in `viewModelScope`, and never holds a reference to a UI object (`Context`, a `View`, a `UIViewController`).
- `viewModelScope` uses `Dispatchers.Main.immediate` — tests must `Dispatchers.setMain(...)` (`testing.md`).
- For UI, the view model owns the **whole** screen state, including a collection's full item list (each item a
  small immutable state with a stable `id`), paging flags and selection keyed by id; derived display values are
  computed here, not in the views (`ui-implementation.md`).
- With `ui: native`, the iOS app owns the `ViewModel`'s lifetime and must call `clear()`-equivalent cleanup when the
  screen goes away — see `native-ui.md`.
- `SavedStateHandle` is available in the multiplatform lifecycle artifacts (`lifecycle-viewmodel-savedstate`) — use
  it only if the module already does.

## Resources outside Compose

- UI strings and images for `ui: compose` live in Compose Multiplatform resources (`compose-multiplatform.md`).
- With `ui: native`, user-facing strings live in each platform's own resources (`androidApp` `res/values/strings.xml`,
  `iosApp` String Catalog) — shared logic returns typed values/enums/error codes and the UI layer maps them to
  localized text. Don't build user-facing English strings in `commonMain` logic.
- Raw files needed by shared logic (a bundled JSON seed) go in `composeResources/files/` when the module uses Compose
  resources (`Res.readBytes("files/…")`), otherwise behind a platform `ResourceReader` interface.
