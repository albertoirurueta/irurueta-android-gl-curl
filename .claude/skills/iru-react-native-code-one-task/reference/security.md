# Security

A React Native app ships its whole JavaScript bundle, its config, and its assets to every user's device, where
they can be extracted, read, and modified. Design every change as if the client were hostile: the server is the
only place a secret can live and the only place authorization can be enforced. (OWASP MASVS is the reference
checklist.)

## No secrets in the bundle

- **Nothing in JS, `app.json`/`app.config.*` `extra`, `EXPO_PUBLIC_*` variables, `react-native-config`/`.env` values
  baked into the build, `Info.plist`, `strings.xml`, or `BuildConfig` fields is secret.** All of it is readable from
  the installed app. API keys that must stay private (payment secrets, admin tokens, third-party server keys) belong
  on a backend the app calls with the user's own credentials.
- Public, restrictable client keys (a maps key restricted to the bundle id, a Sentry DSN, a publishable payments
  key) may be in config — say in the report that the key is public by design.
- **Hermes bytecode is not protection.** Release bundles compiled to Hermes bytecode decompile readily; minification
  and ProGuard/R8 only slow a reader down. Never rely on them to hide logic, keys, or feature flags.
- Never commit `.env` files with real values, keystores, `google-services.json`/`GoogleService-Info.plist` for
  production projects (unless the project already does by design), or signing credentials. If the task needs a new
  config value, read it from the project's existing config mechanism and add a placeholder to the example file.

## Storing sensitive data

- Tokens, refresh tokens, credentials, and keys go into the platform keystore: **`expo-secure-store`** (expo — iOS
  Keychain / Android Keystore-backed encryption; values are size-limited, keep them small) or
  **`react-native-keychain`** (metro) — whichever is installed; otherwise that's a dependency decision to report.
  Choose the strictest accessibility the feature allows (`WHEN_UNLOCKED_THIS_DEVICE_ONLY` / `ACCESSIBLE.WHEN_UNLOCKED_THIS_DEVICE_ONLY`),
  and `requireAuthentication`/biometric access control only when the task asks for it.
- **Never** put tokens or PII in AsyncStorage, unencrypted MMKV, Redux-persist/TanStack-persist storage, files, or
  navigation state — all are plain files on disk and in device backups.
- Keep secrets out of React state that is logged, persisted, or sent to analytics; clear them on sign-out (keystore
  item, in-memory caches, query cache).
- Sensitive screens: hide content in the app switcher where the task requires it (`expo-screen-capture` /
  `FLAG_SECURE` via the project's existing mechanism).

## Deep links, universal links, and intents

- Every route reachable by a deep link (`scheme://…`, universal/app links, push-notification payloads, Android
  intents) is **public input**. Parse and validate params (type, range, allow-list) before use; never trust an id
  from a link to imply authorization — the API checks ownership.
- Never let a link trigger a state-changing action without user confirmation (no `myapp://transfer?to=…&amount=…`
  that executes on open) and never put tokens or one-time codes in link query strings that end up in logs/history;
  OAuth redirects use PKCE (`expo-auth-session` / `react-native-app-auth`) with the system browser, not an embedded
  WebView.
- Opening external URLs: allow-list schemes (`https:`, `mailto:`, `tel:`) before `Linking.openURL`; never open a URL
  taken unvalidated from a link, push payload, or API.
- Prefer verified **universal links / Android App Links** (associated domains / `autoVerify` intent filters) over a
  custom scheme for anything sensitive — a custom scheme can be claimed by another app. Android: don't export
  components (`android:exported`) the task doesn't need exported.

## WebView hardening (`react-native-webview`)

- Load only allow-listed origins (`originWhitelist={["https://*.example.com"]}`), and intercept navigation
  (`onShouldStartLoadWithRequest`) to keep the user inside the allow-list and hand other URLs to the system browser.
- Keep `javaScriptEnabled` off unless needed; never enable `allowFileAccess`, `allowFileAccessFromFileURLs`,
  `allowUniversalAccessFromFileURLs`, or `mixedContentMode="always"`.
- The `postMessage` bridge is an attack surface: validate `event.nativeEvent.origin`/`url` and the message shape in
  `onMessage`; expose the smallest possible API through `injectedJavaScript`; never inject secrets/tokens into a page.
- Never render untrusted HTML with `source={{ html }}` without sanitizing it.

## Network and TLS

- HTTPS only. Don't add `NSAllowsArbitraryLoads`/`NSExceptionDomains` exceptions (iOS ATS) or
  `android:usesCleartextTraffic="true"`/a permissive `network_security_config.xml` (Android) — except the
  development-only localhost exceptions the templates already have.
- **Certificate/public-key pinning** only when the task asks for it, through the project's chosen mechanism
  (`react-native-ssl-public-key-pinning`, a TrustKit/OkHttp `CertificatePinner` setup, an Expo config plugin): pin
  public keys (SPKI hashes), include a backup pin, and plan rotation — a wrong pin bricks networking for every
  installed copy until users update.
- Send credentials in headers (`Authorization: Bearer …`), never in URLs; handle 401 by refreshing or signing out,
  never by retrying with stale credentials in a loop.

## Logging hygiene

- No `console.log` of tokens, credentials, PII, full request/response bodies, or deep-link URLs with parameters —
  release builds keep `console.*` unless stripped (`babel-plugin-transform-remove-console` or the project's logger),
  and device logs (`adb logcat`, Console.app) are readable by other tools.
- Crash/analytics reporting (Sentry, Crashlytics, …): scrub sensitive fields with the SDK's `beforeSend`/scrubbers the
  project already has; never attach the auth state or storage contents to a report.
- Error messages shown to users don't reveal internals (stack traces, SQL, endpoint paths).

## Permissions and platform features

- Request the minimum permissions, at the moment of use, with an accurate usage description (`Info.plist`
  `NS…UsageDescription` / `app.json` plugin options); remove permissions a change stops needing. Handle denial.
- Biometric checks (`expo-local-authentication`, `react-native-biometrics`) gate local UX only — a server-side
  operation still needs server-side authorization, and a "biometric passed" boolean is never sent as proof.
- Clipboard: don't copy secrets; if the task requires it, clear it after a short time.

## Client-side checks are UX, not security

Route guards (`Stack.Protected`, a redirect in a layout, a hidden button) and client-side validation improve UX; the
backend must still authenticate, authorize, and validate every request. Jailbreak/root detection and obfuscation
raise cost, never guarantee anything — don't present them as security controls in code comments or docs.
