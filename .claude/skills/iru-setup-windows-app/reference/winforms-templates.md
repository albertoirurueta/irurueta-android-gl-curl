# Windows Forms templates — `ui: winforms` (architecture `mvc` or `mvvm`)

The `src/__AppName__` Windows Forms project: `Program.cs` composition root (`ApplicationConfiguration.Initialize`,
system color mode, Generic Host), `MainForm.cs` + a designer-compatible `MainForm.Designer.cs`, PerMonitorV2 DPI via
`ApplicationHighDpiMode`.

- **`architecture: mvc`** — `MainForm` implements `Core`'s passive-view `IMainView`; `Core`'s `MainController`
  subscribes to the view's events, updates `GreetingModel`, and pushes model changes back to the view. The
  designer wires control events to small `On…` handlers that only re-raise the view events.
- **`architecture: mvvm`** — a designer `BindingSource` over `Core`'s `MainViewModel`: `TextBox.Text` ↔ `Name`
  (`OnPropertyChanged`), `Label.Text` ← `Message`, and `Button.Command` ← `GreetCommand` (the .NET 8+ command
  binding — the button's `Enabled` follows `CanExecute`).

`MainForm.Designer.cs` is in the exact shape the Visual Studio WinForms designer serializes (fields at the end,
`#region Windows Form Designer generated code`, no hand logic) so it round-trips in the designer; Roslyn treats
`*.Designer.cs` as generated code (no StyleCop/nullable diagnostics there). Markers and `__Token__` placeholders
as described in SKILL.md. Rendered, compiled (`EnableWindowsTargeting`) and analyzer-clean on macOS on 2026-10-16
for both architectures; running the form is unverified locally.

### `src/__AppName__/__AppName__.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework>
    <UseWindowsForms>true</UseWindowsForms>
    <RootNamespace>__RootNamespace__</RootNamespace>
    <AssemblyName>__AppName__</AssemblyName>
    <ApplicationManifest>app.manifest</ApplicationManifest>
    <ApplicationIcon>Assets\AppIcon.ico</ApplicationIcon>
    <!-- Read by the ApplicationConfiguration.Initialize() source generator. -->
    <ApplicationHighDpiMode>PerMonitorV2</ApplicationHighDpiMode>
    <ApplicationVisualStyles>true</ApplicationVisualStyles>
    <ApplicationUseCompatibleTextRendering>false</ApplicationUseCompatibleTextRendering>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.Extensions.Hosting" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\__AppName__.Core\__AppName__.Core.csproj" />
  </ItemGroup>
</Project>
```

### `src/__AppName__/Program.cs`

```csharp
// >>> architecture: mvc
using __RootNamespace__.Core.Controllers;
using __RootNamespace__.Core.Models;
// <<< architecture: mvc
using __RootNamespace__.Core.Services;
// >>> architecture: mvc
using __RootNamespace__.Core.Views;
// <<< architecture: mvc
// >>> architecture: mvvm
using __RootNamespace__.Core.ViewModels;
// <<< architecture: mvvm
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace __RootNamespace__;

/// <summary>
/// Composition root: configures Windows Forms, builds the Generic Host and runs the main form.
/// </summary>
internal static class Program
{
    [STAThread]
    private static void Main()
    {
        // High DPI (PerMonitorV2), visual styles and the default font come from the project file.
        ApplicationConfiguration.Initialize();

        // The UI tests pass --ui-theme / --ui-direction (UiTestOptions); without them the system settings apply.
        Application.SetColorMode(UiTestOptions.Theme switch
        {
            "light" => SystemColorMode.Classic,
            "dark" => SystemColorMode.Dark,
            _ => SystemColorMode.System,
        });

        var builder = Host.CreateApplicationBuilder(
            new HostApplicationBuilderSettings { ContentRootPath = AppContext.BaseDirectory });
        builder.Services.AddSingleton<IGreetingService, GreetingService>();
        // >>> architecture: mvc
        builder.Services.AddSingleton<GreetingModel>();
        builder.Services.AddSingleton<MainForm>();
        builder.Services.AddSingleton<IMainView>(services => services.GetRequiredService<MainForm>());
        builder.Services.AddSingleton<MainController>();
        // <<< architecture: mvc
        // >>> architecture: mvvm
        builder.Services.AddTransient<MainViewModel>();
        builder.Services.AddSingleton<MainForm>();
        // <<< architecture: mvvm

        using var host = builder.Build();
        host.Start();
        var mainForm = host.Services.GetRequiredService<MainForm>();
        if (UiTestOptions.IsRightToLeft)
        {
            mainForm.RightToLeft = RightToLeft.Yes;
            mainForm.RightToLeftLayout = true;
        }

        // >>> architecture: mvc
        _ = host.Services.GetRequiredService<MainController>(); // attaches to the form; disposed with the host
        // <<< architecture: mvc
        Application.Run(mainForm);
        host.StopAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
    }
}
```

### `src/__AppName__/MainForm.cs` (architecture: mvc)

```csharp
using __RootNamespace__.Core.Views;

namespace __RootNamespace__;

/// <summary>
/// Main form: the passive <see cref="IMainView"/> driven by <c>MainController</c>. It only translates control
/// events into view events and displays what the controller tells it to.
/// </summary>
public partial class MainForm : Form, IMainView
{
    /// <summary>
    /// Initializes a new instance of the <see cref="MainForm"/> class.
    /// </summary>
    public MainForm()
    {
        InitializeComponent();
    }

    /// <inheritdoc/>
    public event EventHandler? GreetRequested;

    /// <inheritdoc/>
    public event EventHandler? NameInputChanged;

    /// <inheritdoc/>
    public string NameInput => nameTextBox.Text;

    /// <inheritdoc/>
    public void ShowMessage(string message) => messageLabel.Text = message;

    /// <inheritdoc/>
    public void SetGreetEnabled(bool enabled) => greetButton.Enabled = enabled;

    private void OnGreetButtonClick(object? sender, EventArgs e) => GreetRequested?.Invoke(this, EventArgs.Empty);

    private void OnNameTextBoxTextChanged(object? sender, EventArgs e) =>
        NameInputChanged?.Invoke(this, EventArgs.Empty);
}
```

### `src/__AppName__/MainForm.cs` (architecture: mvvm)

```csharp
using __RootNamespace__.Core.ViewModels;

namespace __RootNamespace__;

/// <summary>
/// Main form: its controls are bound (in the designer) to a <see cref="MainViewModel"/> through
/// <c>mainViewModelBindingSource</c>; all behavior lives in the view model.
/// </summary>
public partial class MainForm : Form
{
    /// <summary>
    /// Initializes a new instance of the <see cref="MainForm"/> class.
    /// </summary>
    /// <param name="viewModel">The view model the form binds to.</param>
    public MainForm(MainViewModel viewModel)
    {
        ArgumentNullException.ThrowIfNull(viewModel);
        InitializeComponent();
        mainViewModelBindingSource.DataSource = viewModel;
    }
}
```

### `src/__AppName__/MainForm.Designer.cs`

```csharp
namespace __RootNamespace__;

partial class MainForm
{
    /// <summary>
    ///  Required designer variable.
    /// </summary>
    private System.ComponentModel.IContainer components = null;

    /// <summary>
    ///  Clean up any resources being used.
    /// </summary>
    /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
    protected override void Dispose(bool disposing)
    {
        if (disposing && (components != null))
        {
            components.Dispose();
        }
        base.Dispose(disposing);
    }

    #region Windows Form Designer generated code

    /// <summary>
    ///  Required method for Designer support - do not modify
    ///  the contents of this method with the code editor.
    /// </summary>
    private void InitializeComponent()
    {
        // >>> architecture: mvc
        tableLayoutPanel = new TableLayoutPanel();
        nameLabel = new Label();
        nameTextBox = new TextBox();
        greetButton = new Button();
        messageLabel = new Label();
        tableLayoutPanel.SuspendLayout();
        SuspendLayout();
        // <<< architecture: mvc
        // >>> architecture: mvvm
        components = new System.ComponentModel.Container();
        tableLayoutPanel = new TableLayoutPanel();
        nameLabel = new Label();
        nameTextBox = new TextBox();
        mainViewModelBindingSource = new BindingSource(components);
        greetButton = new Button();
        messageLabel = new Label();
        tableLayoutPanel.SuspendLayout();
        ((System.ComponentModel.ISupportInitialize)mainViewModelBindingSource).BeginInit();
        SuspendLayout();
        // <<< architecture: mvvm
        // 
        // tableLayoutPanel
        // 
        tableLayoutPanel.ColumnCount = 2;
        tableLayoutPanel.ColumnStyles.Add(new ColumnStyle());
        tableLayoutPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
        tableLayoutPanel.Controls.Add(nameLabel, 0, 0);
        tableLayoutPanel.Controls.Add(nameTextBox, 1, 0);
        tableLayoutPanel.Controls.Add(greetButton, 1, 1);
        tableLayoutPanel.Controls.Add(messageLabel, 1, 2);
        tableLayoutPanel.Dock = DockStyle.Fill;
        tableLayoutPanel.Location = new Point(0, 0);
        tableLayoutPanel.Name = "tableLayoutPanel";
        tableLayoutPanel.Padding = new Padding(12);
        tableLayoutPanel.RowCount = 4;
        tableLayoutPanel.RowStyles.Add(new RowStyle());
        tableLayoutPanel.RowStyles.Add(new RowStyle());
        tableLayoutPanel.RowStyles.Add(new RowStyle());
        tableLayoutPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
        tableLayoutPanel.Size = new Size(420, 160);
        tableLayoutPanel.TabIndex = 0;
        // 
        // nameLabel
        // 
        nameLabel.Anchor = AnchorStyles.Left;
        nameLabel.AutoSize = true;
        nameLabel.Location = new Point(15, 19);
        nameLabel.Name = "nameLabel";
        nameLabel.Size = new Size(39, 15);
        nameLabel.TabIndex = 0;
        nameLabel.Text = "&Name";
        // 
        // nameTextBox
        // 
        // >>> architecture: mvvm
        nameTextBox.DataBindings.Add(new Binding("Text", mainViewModelBindingSource, "Name", true, DataSourceUpdateMode.OnPropertyChanged));
        // <<< architecture: mvvm
        nameTextBox.Dock = DockStyle.Fill;
        nameTextBox.Location = new Point(60, 15);
        nameTextBox.Name = "nameTextBox";
        nameTextBox.Size = new Size(345, 23);
        nameTextBox.TabIndex = 1;
        // >>> architecture: mvc
        nameTextBox.TextChanged += OnNameTextBoxTextChanged;
        // <<< architecture: mvc
        // >>> architecture: mvvm
        // 
        // mainViewModelBindingSource
        // 
        mainViewModelBindingSource.DataSource = typeof(Core.ViewModels.MainViewModel);
        // <<< architecture: mvvm
        // 
        // greetButton
        // 
        greetButton.AutoSize = true;
        // >>> architecture: mvvm
        greetButton.DataBindings.Add(new Binding("Command", mainViewModelBindingSource, "GreetCommand", true));
        // <<< architecture: mvvm
        greetButton.Location = new Point(60, 44);
        greetButton.Name = "greetButton";
        greetButton.Size = new Size(75, 25);
        greetButton.TabIndex = 2;
        greetButton.Text = "&Greet";
        greetButton.UseVisualStyleBackColor = true;
        // >>> architecture: mvc
        greetButton.Click += OnGreetButtonClick;
        // <<< architecture: mvc
        // 
        // messageLabel
        // 
        messageLabel.AutoSize = true;
        // >>> architecture: mvvm
        messageLabel.DataBindings.Add(new Binding("Text", mainViewModelBindingSource, "Message", true));
        // <<< architecture: mvvm
        messageLabel.Location = new Point(60, 78);
        messageLabel.Margin = new Padding(3, 6, 3, 0);
        messageLabel.Name = "messageLabel";
        messageLabel.Size = new Size(0, 15);
        messageLabel.TabIndex = 3;
        // 
        // MainForm
        // 
        AcceptButton = greetButton;
        AutoScaleDimensions = new SizeF(7F, 15F);
        AutoScaleMode = AutoScaleMode.Font;
        ClientSize = new Size(420, 160);
        Controls.Add(tableLayoutPanel);
        Name = "MainForm";
        StartPosition = FormStartPosition.CenterScreen;
        Text = "__DisplayName__";
        tableLayoutPanel.ResumeLayout(false);
        tableLayoutPanel.PerformLayout();
        // >>> architecture: mvvm
        ((System.ComponentModel.ISupportInitialize)mainViewModelBindingSource).EndInit();
        // <<< architecture: mvvm
        ResumeLayout(false);
    }

    #endregion

    private TableLayoutPanel tableLayoutPanel;
    private Label nameLabel;
    private TextBox nameTextBox;
    private Button greetButton;
    private Label messageLabel;
    // >>> architecture: mvvm
    private BindingSource mainViewModelBindingSource;
    // <<< architecture: mvvm
}
```
