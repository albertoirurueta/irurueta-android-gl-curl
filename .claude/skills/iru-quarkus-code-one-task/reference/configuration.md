# Configuration

Read this when the task adds or reads a configuration property, a `@ConfigMapping`, or a profile-specific value.

## Where configuration lives

- `src/main/resources/application.properties` **or** `application.yaml` (`quarkus-config-yaml`) — use the one the
  module has; never add the other format alongside it.
- Test-only overrides: `src/test/resources/application.properties` (replaces the main file's keys for tests) or
  `%test.`-prefixed keys in the main file — follow the module.
- Precedence (high → low): system properties, environment variables (`QUARKUS_DATASOURCE_JDBC_URL` ←
  `quarkus.datasource.jdbc.url`; quoted segments and dashes become `_`), `.env`, `config/application.properties`,
  `application.properties`. Secrets come from the environment or a secrets source — **never** commit a real
  credential into `application.properties`, not even under `%dev`.

## Profiles

- `%dev.`, `%test.`, `%prod.` prefixes (or `application-<profile>.properties`); custom profiles via
  `quarkus.profile`/`QUARKUS_PROFILE`; `quarkus.config.profile.parent` for inheritance.
- Put the unprefixed key at the production-safe value and override per profile; don't leave production depending
  on a `%prod` key that a deployment might forget.
- Dev Services only start when the service's URL is **not** configured for the active profile — a `%test` URL
  turns them off for tests (and then the tests need that external service). Don't add a test URL to "fix" a
  test; that moves the dependency, it doesn't remove it.

## Injecting configuration

- **`@ConfigMapping` interfaces** for anything with more than one related key — new code's default:

  ```java
  @ConfigMapping(prefix = "orders.export")
  public interface OrderExportConfig {
      /** Directory the export files are written to. */
      String directory();
      /** Maximum rows per file. */
      @WithDefault("1000")
      int batchSize();
      /** Optional SFTP target; absent disables upload. */
      Optional<String> sftpHost();
  }
  ```

  It's generated at build time, needs no scope annotation, is injected by type, validates at startup (missing
  required key → startup failure), and supports nesting, `Optional`, `List`/`Map`, `@WithName`,
  `@WithDefault`, and Bean Validation (`quarkus-hibernate-validator`). In Kotlin it's an `interface` with `fun`
  members (`fun batchSize(): Int`), not a data class.
- `@ConfigProperty(name = "…", defaultValue = "…")` for a single value, when the module already uses it.
- Never use the legacy `io.quarkus.arc.config.ConfigProperties`; never read config with
  `ConfigProvider.getConfig()` inside business code (it hides the dependency and breaks component tests).
- Names: kebab-case keys under an application-specific prefix (`orders.export.batch-size`), never under
  `quarkus.*` for your own settings.
- Every new required key gets a value (or a default) in `application.properties` so the app still starts, and —
  if the module documents its configuration (README, Antora page, `.env.example`) — an entry there.

## Build-time-fixed vs runtime properties

Some properties are read during **build-time augmentation** and baked into the artifact: `quarkus.datasource.db-kind`,
which extensions/security providers are active, `quarkus.hibernate-orm.*` mapping settings, `quarkus.package.*`,
`@IfBuildProperty`/`@IfBuildProfile` conditions, and anything the all-config reference marks *Fixed at build time*.
Overriding one at runtime fails startup with a `ConfigValidationException`. Consequences:

- Don't design a feature toggle around a build-time property; use a runtime property and a branch (or a
  `@LookupIfProperty` bean) instead.
- A task that must change a build-time property changes it in `application.properties` and requires a rebuild —
  say so in the report.

## Testing configuration

- Plain unit tests construct the bean with explicit values (constructor injection) or a Mockito/MockK mock of the
  `@ConfigMapping` interface.
- `@QuarkusComponentTest` supplies values with `@TestConfigProperty(key = …, value = …)` (or the extension's
  `configProperty(...)` builder); `@ConfigMapping` interfaces are supported there.
- A different full-app configuration needs a `QuarkusTestProfile` + `@TestProfile` — each distinct profile
  **restarts the application** in `@QuarkusTest`, so reuse an existing profile class before writing a new one.
