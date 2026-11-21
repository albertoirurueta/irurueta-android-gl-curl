# UI-test templates — `ui-tests: flaui`

`tests/__AppName__.UITests`: MSTest 4 + **FlaUI** (`FlaUI.UIA3`, a third-party UI Automation wrapper — Microsoft has
no current desktop UI-test recommendation: WinAppDriver has had no release since 2020 and is effectively
unmaintained; the Appium Windows Driver is the other option). Every test class is `[TestCategory("UI")]` so ordinary
runs exclude it (`--filter "TestCategory!=UI"`) and the UI gate selects it (`--filter "TestCategory=UI"`); the
assembly is `[DoNotParallelize]` because UI automation drives the real desktop session, and every run must hold the
repository-wide `.integration-test.lock`. `[OSCondition(OperatingSystems.Windows)]` makes MSTest report every test as
skipped (ignored) off Windows — never passed; MSTest's analyzer (MSTEST0061) prefers it to a runtime
`OperatingSystem.IsWindows()` check plus `Assert.Inconclusive`.

The app under test is located through, in order: `APP_AUMID` (a deployed MSIX package's AppUserModelID,
`<PackageFamilyName>!App` — required for `ui: winui`, whose packaged exe cannot start without package identity),
`APP_EXE` (an explicit exe path), or the newest `src/__AppName__/bin/{Release,Debug}/**/__AppName__.exe`
(`ui: wpf|winforms` only). Automation ids are shared by every `ui`: `nameTextBox`, `greetButton`, `messageLabel`
(WPF/WinUI set `AutomationProperties.AutomationId`; WinForms exposes the control's `Name`).

The catalog's UI contract (`.ui-quality.yml`, written by `common-templates.md`): `MainWindowTests` also renders the
main screen in every direction × theme pair of `.ui-quality.yml` — `AppLauncher.Launch("--ui-direction=rtl
--ui-theme=dark")`, applied by the app's `UiTestOptions` — captures the `default` and `typical-data` states through
`UiScreenshots.Save` into `<UI_SCREENSHOTS_DIR or repo root/.ui-screenshots>/screen/<MainWindow|MainForm>/<state>--<dir>--<theme>.png`,
and runs **Axe.Windows** (Microsoft's automated accessibility rules for Windows apps, `Axe.Windows` NuGet) after each
capture through `AccessibilityCheck` at the `.ui-quality.yml` level (`enforce` fails the test, `report` appends to
`.ui-screenshots/a11y-report.txt`, `off` skips the scan). `UiQuality` reads the file once (missing → `enforce`,
`[ltr, rtl]`, `[light, dark]`).

Compiled on macOS (`EnableWindowsTargeting`, FlaUI.UIA3 5.0.0, Axe.Windows 2.4.2, MSTest 4.5.1, warnings as errors,
`dotnet format` clean) on 2026-10-16 for `ui: wpf` and `ui: winforms` (mvc, mvvm); executing the tests — launching
the app, the captures and the Axe.Windows scans — is unverified locally (needs an interactive Windows desktop).

### `tests/__AppName__.UITests/__AppName__.UITests.csproj` (flaui)

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0-windows</TargetFramework>
    <RootNamespace>__RootNamespace__.UITests</RootNamespace>
    <IsPackable>false</IsPackable>
    <!-- FlaUI needs the Microsoft.WindowsDesktop.App runtime, which exists only on Windows: elsewhere the project
         still compiles but is not a test project, so a solution-wide dotnet test does not abort on it. -->
    <IsTestProject Condition="!$([MSBuild]::IsOSPlatform('Windows'))">false</IsTestProject>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="MSTest" />
    <PackageReference Include="FlaUI.UIA3" />
    <PackageReference Include="Axe.Windows" />
  </ItemGroup>

  <ItemGroup>
    <Using Include="Microsoft.VisualStudio.TestTools.UnitTesting" />
  </ItemGroup>
</Project>
```

### `tests/__AppName__.UITests/MSTestSettings.cs` (flaui)

```csharp
// UI automation drives the one interactive desktop session: never run these tests in parallel.
[assembly: DoNotParallelize]
```

### `tests/__AppName__.UITests/AppLauncher.cs` (flaui)

```csharp
using FlaUI.Core;

namespace __RootNamespace__.UITests;

/// <summary>
/// Starts the app under test: <c>APP_AUMID</c> (deployed MSIX), then <c>APP_EXE</c>, then the newest built exe.
/// </summary>
internal static class AppLauncher
{
    private const string AppName = "__AppName__";

    /// <summary>Starts the app, passing <paramref name="arguments"/> (e.g. <c>--ui-direction=rtl --ui-theme=dark</c>).</summary>
    /// <param name="arguments">The command line arguments, or empty for none.</param>
    /// <returns>The launched application.</returns>
    public static Application Launch(string arguments = "")
    {
        var aumid = Environment.GetEnvironmentVariable("APP_AUMID");
        if (!string.IsNullOrWhiteSpace(aumid))
        {
            return Application.LaunchStoreApp(aumid, arguments);
        }

        var exe = Environment.GetEnvironmentVariable("APP_EXE");
        if (!string.IsNullOrWhiteSpace(exe))
        {
            return Application.Launch(exe, arguments);
        }

        // >>> ui: winui
        throw new InvalidOperationException(
            "Set APP_AUMID to the deployed package's AppUserModelID (<PackageFamilyName>!App) — a packaged WinUI " +
            "exe cannot start without package identity — or APP_EXE to an unpackaged build.");
        // <<< ui: winui
        // >>> ui: wpf|winforms
        return Application.Launch(FindBuiltExe(), arguments);
        // <<< ui: wpf|winforms
    }
    // >>> ui: wpf|winforms

    private static string FindBuiltExe()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !File.Exists(Path.Combine(directory.FullName, $"{AppName}.slnx")))
        {
            directory = directory.Parent;
        }

        if (directory is null)
        {
            throw new InvalidOperationException($"Could not find {AppName}.slnx above {AppContext.BaseDirectory}.");
        }

        var bin = new DirectoryInfo(Path.Combine(directory.FullName, "src", AppName, "bin"));
        var newest = bin.Exists
            ? bin.EnumerateFiles($"{AppName}.exe", SearchOption.AllDirectories)
                .OrderByDescending(file => file.LastWriteTimeUtc)
                .FirstOrDefault()
            : null;
        return newest?.FullName
            ?? throw new FileNotFoundException(
                $"Build the app first (dotnet build src/{AppName} -c Release) or set APP_EXE.", $"{AppName}.exe");
    }
    // <<< ui: wpf|winforms
}
```

### `tests/__AppName__.UITests/UiQuality.cs` (flaui)

```csharp
namespace __RootNamespace__.UITests;

/// <summary>
/// The repository's <c>.ui-quality.yml</c>: the accessibility level and the screenshot matrix. A missing file or key
/// means the defaults — <c>enforce</c>, <c>[ltr, rtl]</c>, <c>[light, dark]</c>.
/// </summary>
internal sealed record UiQuality(string Accessibility, IReadOnlyList<string> Directions, IReadOnlyList<string> Themes)
{
    private static readonly Lazy<string> Root = new(FindRepositoryRoot);

    private static readonly Lazy<UiQuality> Loaded = new(() => Parse(Path.Combine(Root.Value, ".ui-quality.yml")));

    /// <summary>Gets the repository root: the nearest directory above the test binaries holding <c>.git</c> or a <c>*.slnx</c>.</summary>
    public static string RepositoryRoot => Root.Value;

    /// <summary>Gets the settings read once per test run.</summary>
    public static UiQuality Current => Loaded.Value;

    /// <summary>Gets every (direction, theme) pair to capture, for <c>[DynamicData]</c>.</summary>
    public static IEnumerable<object[]> Matrix =>
        from direction in Current.Directions
        from theme in Current.Themes
        select new object[] { direction, theme };

    private static UiQuality Parse(string path)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        if (File.Exists(path))
        {
            foreach (var raw in File.ReadLines(path))
            {
                var line = raw.Split('#')[0];
                var colon = line.IndexOf(':', StringComparison.Ordinal);
                if (colon > 0)
                {
                    values[line[..colon].Trim()] = line[(colon + 1)..].Trim();
                }
            }
        }

        return new UiQuality(
            values.TryGetValue("accessibility", out var level) && level.Length > 0 ? level : "enforce",
            ListOrDefault(values, "directions", ["ltr", "rtl"]),
            ListOrDefault(values, "themes", ["light", "dark"]));
    }

    private static string[] ListOrDefault(Dictionary<string, string> values, string key, string[] fallback) =>
        values.TryGetValue(key, out var value) && value.StartsWith('[')
            ? value.Trim('[', ']').Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            : fallback;

    private static string FindRepositoryRoot()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            if (Directory.Exists(Path.Combine(directory.FullName, ".git"))
                || File.Exists(Path.Combine(directory.FullName, ".git"))
                || directory.EnumerateFiles("*.slnx").Any())
            {
                return directory.FullName;
            }
        }

        throw new InvalidOperationException($"No repository root (.git or *.slnx) above {AppContext.BaseDirectory}.");
    }
}
```

### `tests/__AppName__.UITests/UiScreenshots.cs` (flaui)

```csharp
using System.Text.RegularExpressions;
using FlaUI.Core.AutomationElements;

namespace __RootNamespace__.UITests;

/// <summary>
/// Writes UI-test captures to <c>&lt;UI_SCREENSHOTS_DIR or repo root/.ui-screenshots&gt;/&lt;kind&gt;/&lt;Id&gt;/&lt;state&gt;--&lt;direction&gt;--&lt;theme&gt;[--&lt;variant&gt;…].png</c>.
/// </summary>
internal static partial class UiScreenshots
{
    private static readonly string[] Kinds = ["component", "screen", "flow"];

    /// <summary>Gets the capture root: <c>UI_SCREENSHOTS_DIR</c> when set, else <c>.ui-screenshots</c> at the repository root.</summary>
    public static string RootDirectory { get; } =
        Environment.GetEnvironmentVariable("UI_SCREENSHOTS_DIR") is { Length: > 0 } configured
            ? configured
            : Path.Combine(UiQuality.RepositoryRoot, ".ui-screenshots");

    /// <summary>Captures <paramref name="element"/> (a window or a single control) as a PNG and returns its path.</summary>
    /// <param name="element">The element to capture.</param>
    /// <param name="kind"><c>component</c>, <c>screen</c> or <c>flow</c>.</param>
    /// <param name="id">The component/screen type name as in code, or the kebab-case flow name.</param>
    /// <param name="state">The kebab-case visual state (<c>default</c>, <c>empty</c>, <c>01-cart</c>, …).</param>
    /// <param name="direction"><c>ltr</c> or <c>rtl</c>.</param>
    /// <param name="theme"><c>light</c>, <c>dark</c> or <c>high-contrast</c>.</param>
    /// <param name="variants">Optional extra segments (<c>large-text</c>, <c>w1280</c>, …).</param>
    /// <returns>The written file's path.</returns>
    public static string Save(
        AutomationElement element, string kind, string id, string state, string direction, string theme, params string[] variants)
    {
        string[] segments = [state, direction, theme, .. variants];
        if (!Kinds.Contains(kind) || !IdPattern().IsMatch(id)
            || segments.Any(segment => !KebabPattern().IsMatch(segment)))
        {
            throw new ArgumentException($"Not a contract screenshot name: {kind}/{id}/{string.Join("--", segments)}.png");
        }

        var directory = Path.Combine(RootDirectory, kind, id);
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, string.Join("--", segments) + ".png");
        using var image = FlaUI.Core.Capturing.Capture.Element(element);
        image.ToFile(path);
        return path;
    }

    [GeneratedRegex("^[a-z0-9]+(-[a-z0-9]+)*$")]
    private static partial Regex KebabPattern();

    [GeneratedRegex("^([A-Z][A-Za-z0-9]*|[a-z0-9]+(-[a-z0-9]+)*)$")]
    private static partial Regex IdPattern();
}
```

`FlaUI.Core.Capturing.Capture` stays fully qualified: `System.Text.RegularExpressions.Capture` makes the short name
ambiguous (CS0104 — verified).

### `tests/__AppName__.UITests/AccessibilityCheck.cs` (flaui)

```csharp
using Axe.Windows.Automation;
using Axe.Windows.Automation.Data;

namespace __RootNamespace__.UITests;

/// <summary>
/// Runs the Axe.Windows rules against the app under test at the <c>.ui-quality.yml</c> level: <c>enforce</c> fails
/// the test on any violation, <c>report</c> appends them to <c>&lt;screenshots dir&gt;/a11y-report.txt</c>, <c>off</c>
/// skips the scan.
/// </summary>
internal static class AccessibilityCheck
{
    /// <summary>Scans every top-level window of <paramref name="processId"/>.</summary>
    /// <param name="processId">The app's process id (<c>Application.ProcessId</c>).</param>
    /// <param name="context">What was on screen, e.g. <c>MainWindow default rtl dark</c>.</param>
    public static void Run(int processId, string context)
    {
        var level = UiQuality.Current.Accessibility;
        if (level is "off" or "none")
        {
            return;
        }

        var config = Config.Builder.ForProcessId(processId).WithOutputFileFormat(OutputFileFormat.None).Build();
        var output = ScannerFactory.CreateScanner(config).Scan(new ScanOptions());
        var violations = output.WindowScanOutputs
            .SelectMany(window => window.Errors)
            .Select(error => $"{error.Rule.ID}: {error.Rule.Description}")
            .ToList();
        if (violations.Count == 0)
        {
            return;
        }

        if (level == "enforce")
        {
            Assert.Fail($"[a11y] {context}: {violations.Count} Axe.Windows violation(s): {string.Join("; ", violations)}");
        }

        Directory.CreateDirectory(UiScreenshots.RootDirectory);
        File.AppendAllLines(
            Path.Combine(UiScreenshots.RootDirectory, "a11y-report.txt"),
            violations.Select(violation => $"{context}\t{violation}"));
    }
}
```

### `tests/__AppName__.UITests/MainWindowTests.cs` (flaui)

```csharp
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Tools;
using FlaUI.UIA3;

namespace __RootNamespace__.UITests;

[TestClass]
[TestCategory("UI")]
[OSCondition(OperatingSystems.Windows)]
public sealed class MainWindowTests
{
    // >>> ui: wpf|winui
    private const string ScreenId = "MainWindow";
    // <<< ui: wpf|winui
    // >>> ui: winforms
    private const string ScreenId = "MainForm";
    // <<< ui: winforms

    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(30);

    public static IEnumerable<object[]> Matrix => UiQuality.Matrix;

    [TestMethod]
    public void Greet_ShowsTheGreetingForTheTypedName()
    {
        using var app = AppLauncher.Launch();
        using var automation = new UIA3Automation();
        try
        {
            var window = app.GetMainWindow(automation, Timeout)
                ?? throw new AssertFailedException("The main window did not appear.");
            var nameTextBox = Find(window, "nameTextBox").AsTextBox();
            var greetButton = Find(window, "greetButton").AsButton();
            var messageLabel = Find(window, "messageLabel");

            nameTextBox.Enter("Ada");
            Retry.WhileFalse(() => greetButton.IsEnabled, Timeout, throwOnTimeout: true);
            greetButton.Invoke();

            var shown = Retry.WhileFalse(() => messageLabel.Name == "Hello, Ada!", Timeout);
            Assert.IsTrue(shown.Result, $"Expected the greeting, found '{messageLabel.Name}'.");
        }
        finally
        {
            app.Close();
        }
    }

    [TestMethod]
    [DynamicData(nameof(Matrix))]
    public void MainWindow_CapturesEveryStateAndPassesTheAccessibilityScan(string direction, string theme)
    {
        using var app = AppLauncher.Launch($"--ui-direction={direction} --ui-theme={theme}");
        using var automation = new UIA3Automation();
        try
        {
            var window = app.GetMainWindow(automation, Timeout)
                ?? throw new AssertFailedException("The main window did not appear.");
            UiScreenshots.Save(window, "screen", ScreenId, "default", direction, theme);
            AccessibilityCheck.Run(app.ProcessId, $"{ScreenId} default {direction} {theme}");

            Find(window, "nameTextBox").AsTextBox().Enter(direction == "rtl" ? "سارة" : "Ada");
            var greetButton = Find(window, "greetButton").AsButton();
            Retry.WhileFalse(() => greetButton.IsEnabled, Timeout, throwOnTimeout: true);
            greetButton.Invoke();
            var messageLabel = Find(window, "messageLabel");
            Retry.WhileTrue(() => string.IsNullOrEmpty(messageLabel.Name), Timeout, throwOnTimeout: true);
            UiScreenshots.Save(window, "screen", ScreenId, "typical-data", direction, theme);
            AccessibilityCheck.Run(app.ProcessId, $"{ScreenId} typical-data {direction} {theme}");
        }
        finally
        {
            app.Close();
        }
    }

    private static AutomationElement Find(Window window, string automationId) =>
        Retry.WhileNull(() => window.FindFirstDescendant(condition => condition.ByAutomationId(automationId)), Timeout)
            .Result
        ?? throw new AssertFailedException($"No element with automation id '{automationId}'.");
}
```
