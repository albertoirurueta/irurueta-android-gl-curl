# Security

Conventions for any task touching credentials, sensitive data at rest, networking, logging, untrusted input, or
release-build configuration. Shared code runs on two platforms with different security primitives — the rule is
always: **the contract in `commonMain`, the mechanism in each platform's own secure facility.**

## Secrets never live in the app's source or configuration

- No API key, client secret, password, private signing material, or service credential in any source set,
  `BuildConfig` field, `local.properties` committed to git, `gradle.properties` committed to git, `Info.plist`,
  `Config.xcconfig`, Compose resource, or test fixture that mirrors a real value. Anything shipped inside an app
  binary is extractable — `BuildConfig` and `Info.plist` are plain text to anyone with the APK/IPA.
- Values that must reach the build (e.g. a public analytics key) come from CI secrets or an untracked local file
  injected at build time — and are treated as public. Real secrets stay server-side; the app authenticates the
  *user* and receives short-lived tokens.
- `.gitignore` already covers `local.properties`; don't add a new file holding secrets without making sure it is
  ignored, and never print one in a test or log.
- A task that seems to need an embedded secret is a blocker to report (the design needs a backend), not something to
  implement.

## Sensitive data at rest — `expect`/`actual` or interface over the platform's secure store

Tokens, refresh tokens, and personal data needing confidentiality go through a common interface such as:

```kotlin
/** Stores small secrets in the platform's hardware-backed secure storage. */
public interface SecureStore {
    /** @return the stored value for [key], or `null` when absent. */
    public suspend fun get(key: String): String?
    public suspend fun put(key: String, value: String)
    public suspend fun remove(key: String)
}
```

implemented per platform (`project-structure.md` explains interface vs `expect`/`actual`):

- **iOS** (`iosMain`, `platform.Security`): Keychain items (`SecItemAdd`/`SecItemCopyMatching`/`SecItemUpdate`/
  `SecItemDelete`) with an explicit accessibility class — `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` or
  `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` for tokens (the `ThisDeviceOnly` variants keep them out of backups).
  Check every `OSStatus`; `errSecItemNotFound` maps to `null`, everything else to an error.
- **Android** (`androidMain`): a key in the **Android Keystore** (`KeyGenParameterSpec`, AES-GCM, non-exportable)
  encrypting values persisted in DataStore/a private file — or the project's existing wrapper. Jetpack Security's
  `EncryptedSharedPreferences` (`androidx.security:security-crypto`) is deprecated upstream; keep using it only where
  the project already does, and don't introduce it for new code.
- **A vetted KMP library** the project already depends on (e.g. one wrapping Keychain + Keystore) is fine — use it
  instead of a hand-rolled store. Don't add one for a task.
- Never store secrets in plain DataStore/`NSUserDefaults`/SQLDelight/Room, in Compose `rememberSaveable` state, or in
  a `SavedStateHandle` (those end up in plain files or bundles).
- Tests: `commonTest` uses an in-memory fake `SecureStore`; the real iOS Keychain implementation can be exercised in
  `iosTest` (simulator keychain, cleaned up after); the Android Keystore one needs a device or Robolectric support the
  project already has — otherwise keep it thin and say so.

## Networking

- **HTTPS only.** No `http://` endpoints in production configuration.
- **iOS App Transport Security**: never add `NSAllowsArbitraryLoads` to `Info.plist`/`project.yml`. A genuinely
  required exception is a per-domain `NSExceptionDomains` entry with a reason, and that's a decision to report.
- **Android**: no `android:usesCleartextTraffic="true"`; per-domain exceptions only via a network security config,
  reported.
- **Certificate pinning** (only when the task asks or the project already pins): configure it on each Ktor engine —
  OkHttp engine `config { certificatePinner(CertificatePinner.Builder().add(host, "sha256/…").build()) }` in
  `androidMain`; Darwin engine `handleChallenge(CertificatePinner.Builder().add(host, "sha256/…").build())`
  (`io.ktor.client.engine.darwin.certificates.CertificatePinner`) in `iosMain`. Pin keys (SPKI hashes) with a backup
  pin, both from configuration constants; document the rotation plan in KDoc.
- Don't disable certificate validation in any build type, "temporarily" or in debug.
- Tokens go in an `Authorization` header via Ktor's `Auth` plugin (bearer with refresh) or the project's
  interceptor — never in URLs/query strings (they end up in logs).

## Input validation

- Validate everything from outside the process at the boundary where it enters shared code: deep links
  (`androidApp` intents, iOS `onOpenURL`), push payloads, clipboard, server responses, and values Swift passes in.
  `require(...)` with a clear message, or map to a domain error.
- Deserialize into typed `@Serializable` classes; `ignoreUnknownKeys` is fine, but don't trust field contents
  (lengths, ranges, URLs) without checks.
- Deep-link parameters never trigger privileged actions without an authenticated session and, for destructive
  actions, user confirmation.
- No string-built SQL — SQLDelight/Room bind parameters.
- WebViews: no JavaScript bridges exposing sensitive functions; load only trusted origins.

## Logging hygiene

- Never log tokens, passwords, full request/response bodies with personal data, or `Authorization` headers. Ktor's
  `Logging` plugin: `LogLevel.INFO`/`HEADERS` at most, with `sanitizeHeader { it == HttpHeaders.Authorization }`, and
  only in debug builds.
- Release builds log at warning/error level only; no `println`.

## Release-build hardening

- **Android R8**: `isMinifyEnabled = true` for the `androidApp` release build (don't turn it off to fix a crash —
  add the precise keep rule). Reflection-reached classes need `-keep` rules in `androidApp/proguard-rules.pro`;
  kotlinx.serialization, Ktor, and Koin ship their own consumer rules — don't duplicate them. A shared module that
  needs keep rules for its own reflective code ships them as consumer rules (`consumerKeepRules`/`consumerProguardFiles`
  per the Android plugin in use).
- **iOS**: no debug-only settings (`NSAllowsArbitraryLoads`, verbose logging, test endpoints) leaking into the Release
  configuration of `project.yml`/`Config.xcconfig`.
- `android:allowBackup`/backup rules: exclude files holding tokens from backup (`dataExtractionRules`) when the task
  adds such a file.
