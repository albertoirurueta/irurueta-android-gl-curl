# Windows desktop app development reference

General code agreements for implementing a task in a Windows desktop app on .NET 10 / C# 14 — the layout
`iru-setup-windows-app` scaffolds (`<App>.slnx`, a UI-free `src/<App>.Core` on plain `net10.0`, the app project
`src/<App>` on `net10.0-windows` with WPF, WinUI 3 or Windows Forms, `tests/<App>.Core.Tests` on MSTest 4, an optional
FlaUI `tests/<App>.UITests`, optional `packaging/` for a WPF/WinForms MSIX) and any similar solution. These files
exist so `iru-windows-code-one-task` can load **only** what the task in front of it actually needs, instead of
carrying every rule in context on every task.

## Read this much, and no more

Detect the UI stack, the architecture and the project first (`iru-windows-code-one-task` Steps 0–1), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** C# 14/.NET 10 conventions: file-scoped namespaces, nullable, guard clauses, async rules (`ConfigureAwait(false)` only in Core, no `async void` outside event handlers), StyleCop member ordering, naming, analyzer interplay. |
| `architecture.md` | **Always.** Core vs app project, where code goes, MVVM vs MVC rules, what code-behind may contain, Generic Host DI, UI-touching services behind Core interfaces. |
| `testing.md` | The task writes or updates tests — nearly every task. |
| `xml-docs.md` | The task adds or changes a public/protected type or member. |
| `mvvm-toolkit.md` | Architecture `mvvm`: a view model, `[ObservableProperty]`, `[RelayCommand]`, validation, or messaging. |
| `mvc-winforms.md` | Architecture `mvc` (WinForms): a controller, a passive-view interface, a model with change events, or a form implementing a view. |
| `wpf.md` | `ui: wpf`: XAML, `{Binding}`/`DataContext`, resources/styles, Fluent `ThemeMode`, `Dispatcher`, XAML Behaviors, `app.manifest`. |
| `winui.md` | `ui: winui`: XAML with `x:Bind`, `NavigationView`/`Frame`, `AppWindow`, Mica, packaged vs unpackaged, `Package.appxmanifest`. |
| `winforms.md` | `ui: winforms`: designer files, layout (`Anchor`/`Dock`/`TableLayoutPanel`), `BindingSource`, command binding, high DPI, dark mode, `InvokeAsync`. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI. `ui-contract.md` is the catalog-wide contract (repeated verbatim in every UI coding skill): decomposition, composition over inheritance, inputs/events/states, virtualized collections, UI tests, `.ui-screenshots/` captures, accessibility levels in `.ui-quality.yml`. `ui-implementation.md` maps it onto WPF/WinUI/WinForms: `UserControl` components, dependency-property inputs and command outputs, recycling lists over keyed `ObservableCollection` state, WinForms virtual mode, `FlowDirection`/`RightToLeftLayout`, theme switching, FlaUI captures and the Axe.Windows scan. |
| `packaging.md` | MSIX manifests (`Package.appxmanifest`, `packaging/AppxManifest.xml`), capabilities, versions, publish properties/profiles, RIDs, the AOT/trimming support matrix. |
| `security.md` | Secrets/credentials, settings files, file system/registry, loading XAML or other external input, process launch, elevation, MSIX capabilities, update channels. |

A task that only changes logic inside one existing Core service, with no signature change and no UI, needs the two
always-read files plus `testing.md`, and nothing else. Don't read the rest speculatively. Read at most one UI file
(the detected stack) and at most one of `mvvm-toolkit.md`/`mvc-winforms.md` (the detected architecture); a task that
touches a screen, component or list adds `ui-contract.md` + `ui-implementation.md` (only its stack's sections).

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the file or project you're
   editing consistently does something else, follow it and note the divergence in the report rather than restyling
   untouched code as a side effect of an unrelated task. Exception: never put UI types or UI-thread assumptions into
   the Core project, even if a previous change did — name the pre-existing violation instead.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They never
   license adding a NuGet package (an MVVM framework, a mocking library, FlaUI), a project, a target framework, an
   MSIX capability, or a "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate.** Beyond compile-checking the projects it touched, it never runs the tests,
   coverage, analyzer reports, license headers, DocFX, the app, or packaging — the calling
   `iru-windows-code-one-task-group` does all of that once per task group.

## Honest gaps in the official guidance

Microsoft Learn has **no WPF-specific MVVM article**, **no official dialog or navigation service** for WPF or the MVVM
Toolkit, **no official guidance on testing view models** beyond the .NET MAUI enterprise book's unit-testing chapter,
and **no current recommendation for desktop UI automation** (WinAppDriver has had no release since 2020; FlaUI and
the Appium Windows Driver are third-party). The patterns these files prescribe for those areas — `IDialogService`/
`INavigationService`/file-picker services, view-model test shapes, FlaUI UI tests — are **this catalog's user
patterns**, stated as such. Native AOT and trimming are **unsupported** for WPF and Windows Forms (NETSDK1168).

## Sources

Compiled from the following, cross-checked against a throwaway verification build (.NET SDK 10.0.402, C# 14,
CommunityToolkit.Mvvm 8.4.2, MSTest 4.5.1, StyleCop.Analyzers 1.2.0-beta.556, FlaUI.UIA3 5.0.0, Windows App SDK 2.5.1
— see the skill's "Verification notes"), with this catalog's own stated preferences taking precedence where they are
stricter (notably on keeping Core UI-free and on fakes over mocks). Primary fact base: the verified source list of
https://github.com/albertoirurueta/docs/issues/191 (the planned "Windows Apps" section of the author's docs site).

- irurueta docs: programming-languages/csharp/coding-conventions-and-documentation.adoc (naming, layout,
  `.editorconfig`, analyzers, XML documentation comments, DocFX, idioms)
- irurueta docs: programming-languages/csharp/testing.adoc (MSTest/xUnit/NUnit, Microsoft.Testing.Platform, fakes,
  async tests, Coverlet) — note its MSTest table predates MSTest 4 (`[DataTestMethod]` is obsolete and
  `Assert.ThrowsException`/`[ExpectedException]` are removed in v4; see `testing.md`)
- irurueta docs: programming-languages/csharp/build-and-tooling.adoc (`dotnet` CLI, MSBuild, analyzers, CI)
- Windows apps hub / choosing a stack: <https://learn.microsoft.com/en-us/windows/apps/get-started/>
- WPF overview and app model: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/overview/>,
  <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/app-development/application-management-overview>
- WPF data binding: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/data/>
- WPF commanding: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/commanding-overview>
- WPF threading model: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/threading-model>
- WPF styles/templates and Fluent theme: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/controls/styles-templates-overview>,
  <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/whats-new/net100>, <https://learn.microsoft.com/en-us/dotnet/api/system.windows.thememode>
- WPF with the Generic Host: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/app-development/how-to-use-host-builder>
- Generic Host and DI: <https://learn.microsoft.com/en-us/dotnet/core/extensions/generic-host>,
  <https://learn.microsoft.com/en-us/dotnet/core/extensions/dependency-injection>
- XAML Behaviors for WPF: <https://github.com/microsoft/XamlBehaviorsWpf>
- MVVM Toolkit: <https://learn.microsoft.com/en-us/dotnet/communitytoolkit/mvvm/>, `…/generators/observableproperty`,
  `…/generators/relaycommand`, `…/generators/errors/mvvmtk0042`, `…/asyncrelaycommand`, `…/observablevalidator`,
  `…/messenger`; releases: <https://github.com/CommunityToolkit/dotnet/releases>
- MVVM pattern, DI, messaging, navigation, validation, unit testing ("Enterprise Application Patterns Using .NET
  MAUI"): <https://learn.microsoft.com/en-us/dotnet/architecture/maui/mvvm> and sibling chapters
- WinUI data binding and MVVM: <https://learn.microsoft.com/en-us/windows/apps/develop/data-binding/data-binding-and-mvvm>,
  <https://learn.microsoft.com/en-us/windows/apps/tutorials/winui-mvvm-toolkit/intro>
- WinUI 3 / Windows App SDK: <https://learn.microsoft.com/en-us/windows/apps/winui/winui3/>,
  <https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/>, release channels
  <https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/release-channels>
- `x:Bind`: <https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/x-bind-markup-extension>
- NavigationView: <https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/navigationview>; Mica:
  <https://learn.microsoft.com/en-us/windows/apps/design/style/mica>; windowing:
  <https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/windowing/windowing-overview>
- Windows Forms on .NET: <https://learn.microsoft.com/en-us/dotnet/desktop/winforms/overview/>, what's new in .NET 10
  <https://learn.microsoft.com/en-us/dotnet/desktop/winforms/whats-new/net100>, high DPI
  <https://learn.microsoft.com/en-us/dotnet/desktop/winforms/high-dpi-support-in-windows-forms>
- Packaging: MSIX <https://learn.microsoft.com/en-us/windows/msix/overview>, single-project MSIX
  <https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/single-project-msix>, package identity
  <https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/package-identity-overview>
- .NET publishing, trimming incompatibilities, Native AOT, RIDs: <https://learn.microsoft.com/en-us/dotnet/core/deploying/>,
  <https://learn.microsoft.com/en-us/dotnet/core/deploying/trimming/incompatibilities>,
  <https://learn.microsoft.com/en-us/dotnet/core/deploying/native-aot/>, <https://learn.microsoft.com/en-us/dotnet/core/rid-catalog>
- Unit testing with MSTest (incl. WinUI): <https://learn.microsoft.com/en-us/dotnet/core/testing/unit-testing-mstest-intro>,
  <https://learn.microsoft.com/en-us/dotnet/core/testing/unit-testing-mstest-winui>, MSTest analyzers
  <https://learn.microsoft.com/en-us/dotnet/core/testing/mstest-analyzers/overview>
- UI contract mapping (`ui-implementation.md`): WPF virtualization (`VirtualizingPanel`), data templating and
  bidirectional layout; WinUI `ListView`/`GridView`/`ItemsView`, theme resources and RTL layout; Windows Forms
  virtual mode and `RightToLeft`; Axe.Windows automation <https://github.com/microsoft/axe-windows> — its own
  "Sources" list has the URLs
- UI automation (third-party / unmaintained, prose only): FlaUI <https://github.com/FlaUI/FlaUI>, WinAppDriver
  <https://github.com/microsoft/WinAppDriver>, Appium Windows Driver <https://github.com/appium/appium-windows-driver>
- DPAPI `ProtectedData`: <https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.protecteddata>;
  Credential Locker: <https://learn.microsoft.com/en-us/windows/apps/develop/security/credential-locker>
- StyleCop.Analyzers rules: <https://github.com/DotNetAnalyzers/StyleCopAnalyzers/tree/master/documentation>; CA rules:
  <https://learn.microsoft.com/en-us/dotnet/fundamentals/code-analysis/quality-rules/>
- Sibling references this file set was aligned with: `iru-kmp-code-one-task/reference/` and
  `iru-swift-code-one-task/reference/` (shape, override rules, MVVM state rules) in this catalog.
