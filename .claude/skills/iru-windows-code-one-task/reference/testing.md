# Testing (MSTest 4, view models, controllers, UI automation)

Two test projects, two very different jobs:

| Project | Target | Runs on | Counts toward the 80% bar | Contains |
|---|---|---|---|---|
| `tests/<App>.Core.Tests` | `net10.0` | Linux, macOS, Windows | yes (Core's line coverage) | models, services, view models / controllers — everything with logic |
| `tests/<App>.UITests` | `net10.0-windows` | Windows only, interactive desktop | never | FlaUI screen/component/flow tests with `.ui-screenshots/` captures and the Axe.Windows check, every one `[TestCategory("UI")]` |

Follow whichever framework the project references (MSTest by default in this catalog; xUnit/NUnit if that's what's
there — same rules, their own attributes). Write tests; don't run them (the group skill does).

## MSTest 4 conventions

```csharp
using Bookshelf.Core.Services;
using Bookshelf.Core.ViewModels;

namespace Bookshelf.Core.Tests.ViewModels;

[TestClass]
public sealed class MainViewModelTests
{
    [TestMethod]
    public void GreetCommand_WithName_SetsMessage()
    {
        var viewModel = new MainViewModel(new FakeGreetingService("Hi, Ada"));
        viewModel.Name = "Ada";

        viewModel.GreetCommand.Execute(null);

        Assert.AreEqual("Hi, Ada", viewModel.Message);
    }

    [TestMethod]
    [DataRow("")]
    [DataRow("   ")]
    public void GreetCommand_BlankName_CannotExecute(string name)
    {
        var viewModel = new MainViewModel(new FakeGreetingService("unused")) { Name = name };

        Assert.IsFalse(viewModel.GreetCommand.CanExecute(null));
    }

    [TestMethod]
    public void Constructor_NullService_Throws() =>
        Assert.ThrowsExactly<ArgumentNullException>(() => new MainViewModel(null!));
}
```

- `[TestClass]` on a `public sealed class`; `[TestMethod]` on every test, **including data-driven ones** —
  `[DataTestMethod]` is obsolete in MSTest 4 (`MSTEST0044`, an error under `TreatWarningsAsErrors`); `[DataRow]` /
  `[DynamicData]` for cases.
- Exceptions: `Assert.ThrowsExactly<T>(…)` / `await Assert.ThrowsExactlyAsync<T>(…)` (exact type) or
  `Assert.Throws<T>` (derived types allowed). `Assert.ThrowsException<T>` and `[ExpectedException]` were **removed**
  in MSTest 4 (verified: `CS0117`/`CS0246`) — older docs, including the irurueta C# testing page's table, still show
  them.
- `[TestInitialize]`/`[TestCleanup]` for per-test setup (or just construct in the test); `TestContext` property for
  `TestContext.CancellationToken` in async tests.
- MSTest's analyzers run in the build: e.g. `MSTEST0032` (assertion condition always true — verified) and `MSTEST0044`
  above fail the build under `TreatWarningsAsErrors`; write the assertion the analyzer wants.
- Name: `Member_Scenario_Expected`. The catalog's `.editorconfig` turns `CA1707` off for `tests/**`; if this repository
  doesn't and `CA1707` fires, follow the existing tests' naming.
- Arrange–act–assert with blank lines; one behavior per test; no logic (loops/ifs) in a test.
- `[assembly: Parallelize(Scope = ExecutionScope.MethodLevel)]` may already be set (Core tests are pure and safe to
  parallelize) — then no static mutable state in tests or fakes.

## View-model tests (the catalog's pattern — Microsoft has no official guide for this)

- **Fakes over mocks**: a small hand-written `FakeGreetingService : IGreetingService` in the test project returning
  canned values and recording calls. Use Moq/NSubstitute only if the project already references one.
- **Property-changed**: subscribe to `PropertyChanged`, act, assert the property names raised
  (`CollectionAssert.Contains(raised, nameof(MainViewModel.Message))`), including `[NotifyPropertyChangedFor]`
  dependents.
- **`CanExecute`**: assert before/after the state change, and that `CanExecuteChanged` was raised
  (`[NotifyCanExecuteChangedFor]`).
- **Async commands**: `await viewModel.SaveCommand.ExecuteAsync(null);` — or, after `Execute(null)`, await
  `SaveCommand.ExecutionTask`. Never `Thread.Sleep`/`Task.Delay` to "wait for" a command. Cancellation:
  start `ExecuteAsync`, call `SaveCancelCommand.Execute(null)`, `await Assert.ThrowsExactlyAsync<TaskCanceledException>(…)`
  on the task, then assert `IsRunning` is `false` and no side effect happened (verified pattern).
- **Validation**: set an invalid value, assert `HasErrors` and `GetErrors(nameof(Title))`.
- **Messenger**: pass `new WeakReferenceMessenger()` into the view model, `Register` a recipient in the test, assert
  the message it received.
- **No UI thread in Core.Tests**: nothing that needs a `Dispatcher`, `SynchronizationContext`, `Application`, or a
  WPF/WinUI/WinForms type. If a test seems to need one, the code under test is in the wrong layer.

## Controller tests (MVC)

```csharp
internal sealed class FakeMainView : IMainView
{
    public event EventHandler? GreetRequested;

    public string NameInput { get; set; } = string.Empty;

    public List<string> Messages { get; } = [];

    public void ShowMessage(string message) => Messages.Add(message);

    public void RaiseGreetRequested() => GreetRequested?.Invoke(this, EventArgs.Empty);
}
```

Construct the controller with the fake view and a real (pure) model, raise the view event, assert what the fake view
was told; dispose the controller and assert that raising the event again does nothing (unsubscription) — e.g.
`Assert.HasCount(1, view.Messages)`. (A `CollectionAssert.AreEqual(new[] { … }, …)` literal trips `CA1861` under
`latest-recommended`; MSTest 4's `Assert.HasCount`/`Assert.Contains` avoid it — verified.)

## UI automation tests (FlaUI — third-party, Windows only)

Microsoft currently recommends no desktop UI-automation tool (WinAppDriver has had no release since 2020); the
catalog uses **FlaUI** (`FlaUI.UIA3`), a third-party UI Automation wrapper. A task that adds or changes a screen or
component writes its UI tests per `ui-contract.md` §6–8: every visual state, the direction × theme matrix from
`.ui-quality.yml` (`[DynamicData(nameof(Matrix))]` over `UiQuality.Matrix`, launching with
`--ui-direction=… --ui-theme=…`), a `UiScreenshots.Save(…)` capture per state, and `AccessibilityCheck.Run(…)` after
each capture — helpers and the component-host pattern in `ui-implementation.md`.

The scaffold (`iru-setup-windows-app`, `ui-tests: flaui`) already provides the project shape — reuse it, never
re-invent it: `[assembly: DoNotParallelize]` in `MSTestSettings.cs` (UI automation drives the one desktop session),
and an `AppLauncher` that starts the app from `APP_AUMID` (a deployed MSIX's AppUserModelID — **required** for WinUI,
whose packaged exe can't start without package identity), else `APP_EXE`, else the newest built
`src/<App>/bin/**/<App>.exe` (WPF/WinForms only). A new test class follows the scaffold's `MainWindowTests`:

```csharp
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Tools;
using FlaUI.UIA3;

namespace Bookshelf.UITests;

[TestClass]
[TestCategory("UI")]
[OSCondition(OperatingSystems.Windows)]
public sealed class GreetingUITests
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(30);

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

    private static AutomationElement Find(Window window, string automationId) =>
        Retry.WhileNull(() => window.FindFirstDescendant(condition => condition.ByAutomationId(automationId)), Timeout)
            .Result
        ?? throw new AssertFailedException($"No element with automation id '{automationId}'.");
}
```

(Compiled in a copy of the scaffold's WPF `UITests` project — FlaUI.UIA3 5.0.0, MSTest 4, nullable on,
`EnableWindowsTargeting`, warnings as errors — 0 warnings. Running it is unverified locally — needs Windows.)

- **Every** UI test class carries `[TestCategory("UI")]` — ordinary runs exclude it (`--filter "TestCategory!=UI"`)
  and the group skill runs it separately, Windows only, behind `.integration-test.lock`.
- **`[OSCondition(OperatingSystems.Windows)]`**, never an `OperatingSystem.IsWindows()` check plus a runtime skip:
  MSTest 4's analyzer flags the runtime check (MSTEST0061, an error under warnings-as-errors), and
  `FlaUI.Core.Tools.OperatingSystem` makes a bare `OperatingSystem` ambiguous (CS0104). Off Windows MSTest reports
  the tests as skipped — never passed — and the gate skills don't run that project there anyway (its csproj sets
  `IsTestProject=false` off Windows because its test host needs `Microsoft.WindowsDesktop.App`).
- Launch only through the scaffold's `AppLauncher` (`APP_AUMID` → `APP_EXE` → newest built exe) — never a hardcoded
  exe path or an `APP_EXE`-only lookup, which can't start a packaged WinUI app.
- Find elements by `AutomationId` (`AutomationProperties.AutomationId` in XAML, `Control.Name` in WinForms) — never by
  position or localized text; the scaffold's ids are `nameTextBox`, `greetButton`, `messageLabel` (examples — use
  the ids of the views the task touches, and add a missing id to the view in the same task).
- Bounded waits (`Retry.WhileNull`/`Retry.WhileFalse`, the `GetMainWindow` timeout), never fixed sleeps; always close
  the app (`app.Close()` in `finally`, plus `using`).
- Captures go only through `UiScreenshots.Save` (resolves `UI_SCREENSHOTS_DIR`, else `<repo root>/.ui-screenshots`,
  and validates the `<kind>/<Id>/<state>--<dir>--<theme>.png` name) — never a hardcoded path.
- UI tests never count toward coverage and are never the only test of a piece of logic.
