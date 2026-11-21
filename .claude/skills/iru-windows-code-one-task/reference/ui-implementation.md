# The UI contract on Windows desktop (WPF, WinUI 3, Windows Forms)

`ui-contract.md` is the platform-neutral rule set; this file maps it onto the three stacks this skill builds. Read
the section for the detected `ui` plus the shared test sections. The Core/app split from `architecture.md` still
decides where code goes: UI **state** types (item records, collection state, formatting) live in Core and are
unit-tested there; controls, templates and launch-argument handling live in the app project; captures and the
accessibility scan live in `tests/<App>.UITests`.

## Screens, components, inputs and events

| Contract term | WPF | WinUI 3 | Windows Forms |
|---|---|---|---|
| Screen | `Window` or a `Page` in a `Frame`, owning its view model through `DataContext` | `Window`, or a `Page` in a `NavigationView`'s `Frame`, with a typed `ViewModel` property | `Form` (MVC: implements `I<Screen>View`; MVVM: `BindingSource` over the view model) |
| Component | `UserControl` (or a `DataTemplate` for a stateless leaf) | `UserControl` (or a `DataTemplate` with `x:DataType`) | `UserControl` |
| Inputs | dependency properties, one per input — ideally one `State` property of a Core record | dependency properties, bound inside with `x:Bind State.Title, Mode=OneWay` | plain properties marked `[Browsable(false)]` + `[DesignerSerializationVisibility(Hidden)]` |
| Events | `ICommand` dependency properties (`OpenCommand`, parameter = the item id) or routed events | `ICommand` dependency properties, or `ItemClick`/`ItemInvoked` forwarded in code-behind | `event EventHandler<TEventArgs>` named after the intent (`OpenRequested`) |
| Two-way | `TwoWay` binding only to a value control's DP (`Text`, `IsChecked`, `SelectedValue`, `Value`) | `x:Bind …, Mode=TwoWay` only for value controls | `DataSourceUpdateMode.OnPropertyChanged` binding only for value controls |

- **A component never sets its own `DataContext`** (WPF): that breaks the parent's binding of its inputs. Bind
  inside it to its own properties with `ElementName` on a named root (`x:Name="Root"`) or
  `RelativeSource AncestorType`. WinUI's `x:Bind` inside a `UserControl` already resolves against the control.
- **Presentational components** take state and raise events only — no services, no navigation, no
  `Application.Current`. The screen (its view model or, for WinForms MVC, its controller/presenter) owns state,
  calls services and navigates through the app's single `INavigationService`/`IScreenNavigator` (`architecture.md`).
- Derived values (formatted totals, enabled flags, relative dates) are computed in Core and arrive in the state
  record; converters stay for purely visual mapping (`BooleanToVisibilityConverter`).
- Content composition, not subclassing: `ContentControl`/`ContentPresenter` with `DataTemplate`s (WPF/WinUI),
  `HeaderedContentControl`, a `UserControl` exposing a `Content`-typed DP; WinForms nests `UserControl`s in a
  `TableLayoutPanel`/`FlowLayoutPanel`. Subclass a control only for custom drawing or templating.
- Doc comments on every component and screen name its inputs, events and visual states (the docs are generated
  from them). The DP field, the CLR wrapper and the command property each need an XML doc comment
  (`GenerateDocumentationFile` + warnings as errors).

### WPF component (verified — compiles with the XAML markup pass)

```xml
<UserControl x:Class="Bookshelf.Components.OrderRow"
             xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
             xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
             x:Name="Root">
    <Button AutomationProperties.AutomationId="orderRow"
            AutomationProperties.Name="{Binding State.Title, ElementName=Root}"
            Command="{Binding OpenCommand, ElementName=Root}"
            CommandParameter="{Binding State.Id, ElementName=Root}"
            HorizontalContentAlignment="Stretch">
        <DockPanel>
            <TextBlock DockPanel.Dock="Right" Text="{Binding State.Total, ElementName=Root}" />
            <TextBlock Text="{Binding State.Title, ElementName=Root}" TextTrimming="CharacterEllipsis" />
        </DockPanel>
    </Button>
</UserControl>
```

```csharp
/// <summary>One order in a list. Inputs: <see cref="State"/>. Events: <see cref="OpenCommand"/> (parameter: the
/// order id). Visual states: default, selected, disabled, long-content.</summary>
public partial class OrderRow : UserControl
{
    /// <summary>Identifies the <see cref="State"/> dependency property.</summary>
    public static readonly DependencyProperty StateProperty =
        DependencyProperty.Register(nameof(State), typeof(OrderRowState), typeof(OrderRow));

    /// <summary>Identifies the <see cref="OpenCommand"/> dependency property.</summary>
    public static readonly DependencyProperty OpenCommandProperty =
        DependencyProperty.Register(nameof(OpenCommand), typeof(ICommand), typeof(OrderRow));

    public OrderRow() => InitializeComponent();

    public OrderRowState? State { get => (OrderRowState?)GetValue(StateProperty); set => SetValue(StateProperty, value); }

    public ICommand? OpenCommand { get => (ICommand?)GetValue(OpenCommandProperty); set => SetValue(OpenCommandProperty, value); }
}
```

`OrderRowState` is a Core record (`public sealed record OrderRowState(string Id, string Title, string Total);`).
WinUI is the same shape with `Microsoft.UI.Xaml.DependencyProperty.Register(…, new PropertyMetadata(null))` and
`Text="{x:Bind State.Title, Mode=OneWay}"` inside the control. A WinForms component is a `UserControl` with the
attributed properties above (without `[DesignerSerializationVisibility]` the WinForms analyzer fails the build with
`WFO1000` — verified) and `public event EventHandler<OrderIdEventArgs>? OpenRequested;`.

## Collections — virtualized container, one item component, keyed list state

The collection's view model holds the **whole list** as `ObservableCollection<OrderRowState>` (immutable records
with a stable `Id`) plus paging/loading flags; selection is an id (`SelectedId`), never a reference to a recycled
container. Apply a new snapshot by **keyed diff**, not `Clear()` + re-add (that resets scroll, selection and every
container). The catalog's Core helper (verified with MSTest on `net10.0`; unique ids assumed):

```csharp
public static void SyncById<T, TKey>(this ObservableCollection<T> target, IReadOnlyList<T> next, Func<T, TKey> keyOf)
    where TKey : notnull
{
    var keys = next.Select(keyOf).ToHashSet();
    for (var i = target.Count - 1; i >= 0; i--)
    {
        if (!keys.Contains(keyOf(target[i]))) { target.RemoveAt(i); }
    }

    var comparer = EqualityComparer<TKey>.Default;
    for (var i = 0; i < next.Count; i++)
    {
        var current = i;
        while (current < target.Count && !comparer.Equals(keyOf(target[current]), keyOf(next[i]))) { current++; }
        if (current == target.Count) { target.Insert(i, next[i]); continue; }
        if (current != i) { target.Move(current, i); }
        if (!EqualityComparer<T>.Default.Equals(target[i], next[i])) { target[i] = next[i]; }   // records: value equality
    }
}
```

(Written here compactly; in the repository it follows `code-style.md` — braces on their own lines, guard clauses.)

**WPF** — `ListBox`/`ListView`/`DataGrid` keep a `VirtualizingStackPanel`; turn recycling and pixel scrolling on,
never wrap the list in a `ScrollViewer` or put it in an auto-sized `StackPanel` (infinite height turns virtualization
off), and set `VirtualizingPanel.IsVirtualizingWhenGrouping="True"` if you group (verified XAML):

```xml
<ListBox AutomationProperties.AutomationId="orderList"
         ItemsSource="{Binding Items, ElementName=Root}"
         SelectedValuePath="Id"
         SelectedValue="{Binding SelectedId, ElementName=Root, Mode=TwoWay}"
         VirtualizingPanel.IsVirtualizing="True"
         VirtualizingPanel.VirtualizationMode="Recycling"
         VirtualizingPanel.ScrollUnit="Pixel"
         ScrollViewer.CanContentScroll="True">
    <ListBox.ItemTemplate>
        <DataTemplate DataType="{x:Type orders:OrderRowState}">
            <local:OrderRow State="{Binding}" OpenCommand="{Binding OpenCommand, ElementName=Root}" />
        </DataTemplate>
    </ListBox.ItemTemplate>
</ListBox>
```

Heterogeneous items: one implicit `DataTemplate` per item record type (or a `DataTemplateSelector`). Paging: a
`LoadMoreCommand` on the view model, raised from a one-line `ScrollViewer.ScrollChanged` handler when
`VerticalOffset + ViewportHeight` nears `ExtentHeight`. Many tabs: `TabControl` bound to a collection of tab view
models with `ContentTemplate` — content is created for the selected tab only.

**WinUI 3** — `ListView`/`GridView`/`ItemsView` (virtualizing by default) with an `x:Bind` template; never an
`ItemsRepeater` inside a `StackPanel` (unbounded height realizes everything):

```xml
<ListView ItemsSource="{x:Bind ViewModel.Items, Mode=OneWay}" SelectionMode="Single"
          IsItemClickEnabled="True" ItemClick="OnOrderClick" AutomationProperties.AutomationId="orderList">
    <ListView.ItemTemplate>
        <DataTemplate x:DataType="orders:OrderRowState">
            <local:OrderRow State="{x:Bind}" />
        </DataTemplate>
    </ListView.ItemTemplate>
</ListView>
```

`OnOrderClick` is one line forwarding `((OrderRowState)e.ClickedItem).Id` to `ViewModel.OpenCommand`. Incremental
loading: an app-project collection implementing `Microsoft.UI.Xaml.Data.ISupportIncrementalLoading` over a Core
paging service (`LoadMoreItemsAsync` → append through `SyncById`/`Add`). `ItemsView` (`ItemInvoked`,
`IsItemInvokedEnabled`) is the newer control; `TabView` creates tab content lazily.

**Windows Forms** — `ListView` (`View.Details`) or `DataGridView` in **virtual mode**: set `VirtualMode = true`,
`VirtualListSize`/`RowCount` to the count, and build only the requested row in `RetrieveVirtualItem`/
`CellValueNeeded` from one item-mapping function (the item "component"). A `FlowLayoutPanel` of row
`UserControl`s builds every item eagerly — only for small, bounded sets. Verified shape:

```csharp
_listView.VirtualMode = true;
_listView.RetrieveVirtualItem += (_, e) => e.Item = ToListViewItem(_items[e.ItemIndex]);   // only visible rows
_listView.ItemActivate += (_, _) => OpenRequested?.Invoke(this, new OrderIdEventArgs(_items[_listView.SelectedIndices[0]].Id));

public IReadOnlyList<OrderRowState> Items { get => _items; set { _items = value; _listView.VirtualListSize = value.Count; _listView.Invalidate(); } }
private static ListViewItem ToListViewItem(OrderRowState row) => new([row.Title, row.Total]) { Tag = row.Id };
```

The MVC controller (or MVVM view model) owns the list; the form passes it to the component's `Items` and maps
`OpenRequested` to the view-interface event (`OrderOpenRequested`) the controller handles.

## Direction, theme and localization

- **Right-to-left**: WPF and WinUI mirror the whole tree from one `FlowDirection.RightToLeft` on the window/root
  element — `HorizontalAlignment="Left"`, margins and `DockPanel.Dock="Left"` then mean *start*. Keep images and
  directional glyphs correct: WPF mirrors images too (set `FlowDirection="LeftToRight"` on a logo that must not
  flip); WinUI `FontIcon MirroredWhenRightToLeft="True"` for arrows. WinForms: `RightToLeft = RightToLeft.Yes`
  plus `RightToLeftLayout = true` on the form (`TableLayoutPanel`/`FlowLayoutPanel` follow it; absolute
  `Location`s do not).
- **Theme**: WPF Fluent `ThemeMode` (`System` in `App.xaml`; `Light`/`Dark` from code is experimental — `WPF0001`,
  suppressed only around the test hook below); WinUI `FrameworkElement.RequestedTheme = ElementTheme.Dark` on the
  root content (`Application.RequestedTheme` only in the `App` constructor); WinForms
  `Application.SetColorMode(SystemColorMode.Classic|Dark|System)` before the first form (stable in .NET 10). Use
  theme resources (`DynamicResource`/`ThemeResource`, `SystemColors`) — never hard-coded colours.
- **Test hook** (app project, `UiTestOptions.cs`, scaffolded by `iru-setup-windows-app`): the UI tests launch the
  app with `--ui-direction=ltr|rtl --ui-theme=light|dark`; startup reads them from
  `Environment.GetCommandLineArgs()` and applies the three lines above. Without the arguments the app follows the
  system and its UI culture. High contrast and text scaling are system-wide settings on Windows — not cheap to
  drive per test; skip those captures unless the repository already does them.
- **Strings**: no literal user-visible text — WPF `.resx` resources (`{x:Static properties:Resources.Title}`),
  WinUI `x:Uid` + `Strings/<lang>/Resources.resw`, WinForms `Localizable = true` resources.

## UI tests, captures and accessibility (`tests/<App>.UITests`, Windows only)

Behaviour of view models and controllers stays in `tests/<App>.Core.Tests`. The UI tests are FlaUI tests
(`testing.md`): `[TestCategory("UI")]`, `[OSCondition(OperatingSystems.Windows)]`, launched through `AppLauncher`.

| Level | How it renders here | Captures |
|---|---|---|
| Screen | `AppLauncher.Launch("--ui-direction=… --ui-theme=…")`, drive to the state with fake data (a `--ui-fixture=<name>` argument the app maps to fake services, or the app's own sample data) | the window: `UiScreenshots.Save(window, "screen", "OrdersPage", state, dir, theme)` |
| Component | the same launch with a component host: `--ui-component=OrderRow --ui-state=selected` makes startup show a small host window whose content is the component bound to a fixture state from `src/<App>/UiTesting/ComponentFixtures.cs` (fixture records only — no services) | the element: `UiScreenshots.Save(window.FindFirstDescendant(c => c.ByAutomationId("componentUnderTest")), "component", "OrderRow", …)` |
| Flow | the real app, navigating screen to screen through automation ids | `flow/<flow-name>/NN-<step>--<dir>--<theme>.png` per step |

- **Matrix**: `[DynamicData(nameof(Matrix))]` over `UiQuality.Matrix` (direction × theme from `.ui-quality.yml`);
  one app launch per pair; behaviour assertions in the `ltr`/`light` test only. Data sets per state: minimal,
  typical, many-items/long-content, an Arabic or Hebrew string in the `rtl` run.
- **Determinism**: the window's XAML/designer size is fixed; move focus off text boxes before a capture (caret);
  wait with `Retry.WhileFalse`, never `Thread.Sleep`; fake services return fixed data and a fixed clock.
- **Accessibility**: after each capture, `AccessibilityCheck.Run(app.ProcessId, "<Id> <state> <dir> <theme>")`
  runs the Axe.Windows rules against the app's windows; `enforce` fails the test with an
  `[a11y] <context>: <n> Axe.Windows violation(s): …` message, `report` appends `<context>\t<RuleId>: <description>`
  lines to `<screenshots dir>/a11y-report.txt` for the gate to count, `off` skips the scan. Coding
  defaults regardless: `AutomationProperties.Name` (WinForms `AccessibleName`) on icon-only buttons and images,
  `AutomationProperties.AutomationId`/`Control.Name` on everything a test finds, a label `Target` for inputs,
  keyboard access keys (`_Name`, `&Name`) and a logical tab order, `AutomationProperties.LiveSetting` (WPF/WinUI) for
  status text that changes.

### The shared helpers (scaffolded by `iru-setup-windows-app`; add them if the repository lacks them)

- `tests/<App>.UITests/UiQuality.cs` — finds the repository root (nearest `.git` or `*.slnx` above the test
  binaries), reads `.ui-quality.yml` (`accessibility`, flow-style `directions: [...]`/`themes: [...]`; defaults
  `enforce`, `[ltr, rtl]`, `[light, dark]` when missing) and exposes `Matrix` for `[DynamicData]`.
- `tests/<App>.UITests/UiScreenshots.cs` — resolves `UI_SCREENSHOTS_DIR`, else `<repo root>/.ui-screenshots`,
  validates the contract name and writes the PNG:

```csharp
public static string Save(AutomationElement element, string kind, string id, string state, string direction,
    string theme, params string[] variants)
{
    string[] segments = [state, direction, theme, .. variants];          // kebab-case, validated by regex
    var directory = Path.Combine(RootDirectory, kind, id);                // kind: component | screen | flow
    Directory.CreateDirectory(directory);
    var path = Path.Combine(directory, string.Join("--", segments) + ".png");
    using var image = FlaUI.Core.Capturing.Capture.Element(element);     // fully qualified: Regex also has Capture
    image.ToFile(path);
    return path;
}
```

- `tests/<App>.UITests/AccessibilityCheck.cs` — the Axe.Windows scan (`Axe.Windows` NuGet, 2.4.x):

```csharp
var config = Config.Builder.ForProcessId(processId).WithOutputFileFormat(OutputFileFormat.None).Build();
var output = ScannerFactory.CreateScanner(config).Scan(new ScanOptions());
var violations = output.WindowScanOutputs.SelectMany(window => window.Errors)
    .Select(error => $"{error.Rule.ID}: {error.Rule.Description}").ToList();
// enforce → Assert.Fail($"[a11y] {context}: …"); report → File.AppendAllLines(<dir>/a11y-report.txt, "{context}\t{v}")
```

All three compiled with FlaUI.UIA3 5.0.0, Axe.Windows 2.4.2 and MSTest 4.5.1 (`net10.0-windows`,
`EnableWindowsTargeting`, warnings as errors) on macOS; **running them is unverified locally (needs an interactive
Windows desktop)** — the group skill reports that stage "unverified (needs Windows)" off Windows, never passed.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Narrator/UI Automation: set `AutomationProperties.Name`, `HelpText`, `LabeledBy`, `LiveSetting`, and `AccessibilityView="Raw"` for decoration; WinUI: `AutomationProperties` plus `ToolTipService`; Windows Forms: `AccessibleName`/`AccessibleRole`/`AccessibleDescription`.
- High Contrast: use system brushes/theme resources (`SystemColors`, `ThemeResource`), never hard-coded colours; keep full keyboard operation (`TabIndex`, `AccessKey`, visible focus).
- Text follows system text scaling; honour `SystemParameters.ClientAreaAnimation` / `UISettings.AnimationsEnabled`.

## Sources

- WPF data templating and `ItemsControl`: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/data/data-templating-overview>
- WPF UI virtualization and performance: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/optimizing-performance-controls>,
  <https://learn.microsoft.com/en-us/dotnet/api/system.windows.controls.virtualizingpanel>
- WPF dependency properties and user controls: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/properties/custom-dependency-properties>
- WPF bidirectional features: <https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/bidirectional-features-in-wpf-overview>
- WPF `ThemeMode`: <https://learn.microsoft.com/en-us/dotnet/api/system.windows.thememode>
- WinUI list and collection controls, item templates, incremental loading:
  <https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/listview-and-gridview>,
  <https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/itemsview>,
  <https://learn.microsoft.com/en-us/windows/apps/develop/performance/optimize-gridview-and-listview>
- WinUI themes and `RequestedTheme`: <https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/xaml-theme-resources>
- WinUI right-to-left / `FlowDirection`: <https://learn.microsoft.com/en-us/windows/apps/design/globalizing/adjust-layout-and-fonts--and-support-rtl>
- Windows Forms virtual mode: <https://learn.microsoft.com/en-us/dotnet/api/system.windows.forms.listview.virtualmode>,
  <https://learn.microsoft.com/en-us/dotnet/desktop/winforms/controls/implementing-virtual-mode-wf-datagridview-control>
- Windows Forms right-to-left and dark mode: <https://learn.microsoft.com/en-us/dotnet/api/system.windows.forms.control.righttoleft>,
  <https://learn.microsoft.com/en-us/dotnet/desktop/winforms/whats-new/net100>
- Windows Forms designer serialization analyzer WFO1000: <https://aka.ms/winforms-warnings/wfo1000>
- UI Automation properties: <https://learn.microsoft.com/en-us/dotnet/framework/ui-automation/ui-automation-properties-overview>
- FlaUI (capturing): <https://github.com/FlaUI/FlaUI>; Axe.Windows automation API:
  <https://github.com/microsoft/axe-windows/blob/main/docs/AutomationReference.md>
