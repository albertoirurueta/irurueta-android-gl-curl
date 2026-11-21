# Security for Windows desktop apps

Read this when a task touches credentials, settings, files, external input, process launch, elevation, or MSIX
capabilities.

## Secrets

- **Never** commit or embed secrets — API keys, connection strings with passwords, signing material — in source,
  `appsettings*.json`, `App.config`, resources, or the MSIX. A desktop binary is in the user's hands; anything in it is
  public. A task that requires an embedded secret is a design problem to report as a blocker.
- Per-user secrets at run time (tokens, saved passwords): DPAPI via
  `System.Security.Cryptography.ProtectedData.Protect(bytes, entropy, DataProtectionScope.CurrentUser)` (package
  `System.Security.Cryptography.ProtectedData`, Windows-only — throws `PlatformNotSupportedException` elsewhere), or
  the Windows Credential Locker (`Windows.Security.Credentials.PasswordVault`) for packaged apps. Implement it in the
  app project behind a Core `ISecretStore`; Core tests use a fake.
- Settings files hold preferences only. Store them under `Environment.GetFolderPath(SpecialFolder.LocalApplicationData)`
  `/<Company>/<App>/` (packaged apps are virtualized into the package's own AppData automatically), never next to the
  executable (Program Files is read-only and shared).
- Logs never contain tokens, passwords, or personal data beyond what diagnosing needs.

## External input

- **Never load XAML from untrusted input** (`XamlReader.Load`/`Parse` on files, the clipboard, the network): XAML can
  instantiate arbitrary types and run code. Load only XAML compiled into the app.
- No `BinaryFormatter` (removed from .NET; also off the clipboard/drag-drop path in .NET 10) — `System.Text.Json` with
  explicit types for anything deserialized.
- Validate and normalize file paths the user or a file supplies (`Path.GetFullPath`, reject paths outside the
  intended folder), open files with the least access needed, and handle `IOException`/`UnauthorizedAccessException`.
- Launching processes or URLs: `Process.Start(new ProcessStartInfo(url) { UseShellExecute = true })` only for
  `https://` URLs you validated (`Uri.TryCreate` + scheme check); never build a command line from user text.
- `HttpClient`: one shared instance (or `IHttpClientFactory` from the host), HTTPS only, default certificate validation
  — never `ServerCertificateCustomValidationCallback = (…) => true`.
- WebView2 content: treat it as a browser — restrict navigation, don't expose host objects to untrusted pages.

## Privilege and packaging

- `app.manifest` keeps `<requestedExecutionLevel level="asInvoker" uiAccess="false" />`. Elevation
  (`requireAdministrator`) is a blocker to confirm — split the privileged operation out instead.
- MSIX capabilities minimal (`packaging.md`); don't add `broadFileSystemAccess` or other restricted capabilities
  without the task asking.
- Update channels (App Installer feed, WinGet, Store) are configured in the release workflow; a task never points the
  app at an unsigned or HTTP update source.

## Native interop

P/Invoke through CsWin32 (`Microsoft.Windows.CsWin32`) or `[LibraryImport]` — only if the project already uses it or
the task asks; validate buffer sizes, use `SafeHandle`s, never hand-roll `IntPtr` lifetime management.
