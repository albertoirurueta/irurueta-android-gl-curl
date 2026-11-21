# CLI templates — `src/__ToolName__` and its end-to-end tests

The thin System.CommandLine 2.0 shell (Native AOT executable and .NET tool): entry point, root command and global
options, the command-tree reader, the Spectre.Console console factory, the `greet`, `completion`, `schema`, hidden
`gen-docs` and (ui: tui) Terminal.Gui `browse` commands, plus `tests/__ToolName__.Cli.Tests`, which runs the real
command tree in-process with captured stdout/stderr and, when `__BinEnvPrefix___BINARY` is set, smoke-tests a
published binary.

Conditional markers (`// >>> <condition>` … `// <<< <condition>`, `<!-- >>> <condition> -->` … in XML) and the
`__Token__` placeholders work as described in SKILL.md's "Reference files" section. A heading suffixed `(condition)`
is written only when the condition holds. Every file below was rendered and built on 2026-10-16 for `ui: none` and
`ui: tui` — see SKILL.md "Verification notes".

### `src/__ToolName__/__ToolName__.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <!-- The thin command-line shell: System.CommandLine command tree, Spectre.Console rendering, entry point.
       Logic lives in __ToolName__.Core. Native AOT binary per RID; also packable as a .NET tool. -->
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>__ToolName__</RootNamespace>
    <AssemblyName>__BinName__</AssemblyName>

    <!-- Native AOT: small, fast-starting, no runtime needed. The AOT/trim analyzers run on every build, and with
         TreatWarningsAsErrors any IL2xxx/IL3xxx warning fails it. -->
    <PublishAot>true</PublishAot>
    <IsAotCompatible>true</IsAotCompatible>
    <InvariantGlobalization>true</InvariantGlobalization>
    <UseSystemResourceKeys>false</UseSystemResourceKeys>
    <StripSymbols>true</StripSymbols>
    <PublishDocumentationFile>false</PublishDocumentationFile>
    <PublishReferencesDocumentationFiles>false</PublishReferencesDocumentationFiles>

    <!-- .NET tool (NuGet channel). With PublishAot, `dotnet pack` builds only the pointer package; each RID package is
         `dotnet pack -r <rid>` on a host of that OS, plus `dotnet pack -r any -p:PublishAot=false` as the
         framework-dependent fallback (.NET SDK 10+ RID-specific tools). -->
    <PackAsTool>true</PackAsTool>
    <ToolCommandName>__BinName__</ToolCommandName>
    <PackageId>__PackageId__</PackageId>
    <ToolPackageRuntimeIdentifiers>linux-x64;linux-arm64;osx-arm64;osx-x64;win-x64;win-arm64;any</ToolPackageRuntimeIdentifiers>
    <PackageOutputPath>$(MSBuildThisFileDirectory)../../artifacts/nupkg</PackageOutputPath>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="System.CommandLine" />
    <PackageReference Include="Spectre.Console" />
    <!-- >>> ui: tui -->
    <PackageReference Include="Terminal.Gui" />
    <!-- <<< ui: tui -->
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\__ToolName__.Core\__ToolName__.Core.csproj" />
  </ItemGroup>

  <ItemGroup>
    <InternalsVisibleTo Include="__ToolName__.Cli.Tests" />
  </ItemGroup>
</Project>
```

### `tests/__ToolName__.Cli.Tests/__ToolName__.Cli.Tests.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>__ToolName__.Cli.Tests</RootNamespace>
    <IsPackable>false</IsPackable>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="MSTest" />
    <PackageReference Include="coverlet.collector" />
  </ItemGroup>

  <ItemGroup>
    <Using Include="Microsoft.VisualStudio.TestTools.UnitTesting" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\..\src\__ToolName__\__ToolName__.csproj" />
  </ItemGroup>
</Project>
```

### `src/__ToolName__/CliApp.cs`

```csharp
using System.CommandLine;
using System.CommandLine.Help;
using System.CommandLine.Invocation;
using __ToolName__.Commands;
using __ToolName__.Core;
using __ToolName__.Core.Output;
using __ToolName__.Core.Terminal;

namespace __ToolName__;

/// <summary>
/// Builds the command tree and runs one invocation against <see cref="CliStreams"/>, applying the CLI contract:
/// usage errors exit 2, expected failures become an error envelope with their exit code, anything else is an
/// internal error (1), and cancellation by a signal exits 130/143.
/// </summary>
internal static class CliApp
{
    /// <summary>
    /// Parser settings: response files (<c>@file</c>) are off, so an argument that starts with <c>@</c> is never
    /// expanded from disk. System.CommandLine 2.0 has no prefix/abbreviation matching to turn off.
    /// </summary>
    private static readonly ParserConfiguration ParserSettings = new() { ResponseFileTokenReplacer = null };

    /// <summary>
    /// Builds the root command and every subcommand.
    /// </summary>
    /// <param name="streams">Where the commands write.</param>
    /// <returns>The root command (named after the binary, so help reads the same under tests) and its global options.</returns>
    public static (Command Root, GlobalOptions Globals) BuildRootCommand(CliStreams streams)
    {
        var globals = new GlobalOptions();
        var root = new ToolCommand(
            ToolInfo.BinName,
            "__Description__",
            Core.Schema.CommandSafety.ReadOnlyCommand,
            null,
            $"{ToolInfo.BinName} greet Ada --json",
            $"{ToolInfo.BinName} schema");
        root.Options.Add(new HelpOption { Action = new ExamplesHelpAction() });
        root.Options.Add(new VersionOption { Action = new VersionAction() });
        foreach (var option in globals.All)
        {
            root.Options.Add(option);
        }

        root.Subcommands.Add(GreetCommand.Create(globals, streams));
        root.Subcommands.Add(CompletionCommand.Create(globals, streams));
        root.Subcommands.Add(SchemaCommand.Create(globals, streams));
        root.Subcommands.Add(GenDocsCommand.Create(globals, streams));
        // >>> ui: tui
        root.Subcommands.Add(BrowseCommand.Create(globals, streams));
        // <<< ui: tui
        return (root, globals);
    }

    /// <summary>
    /// Parses <paramref name="args"/> and runs the selected command.
    /// </summary>
    /// <param name="args">The command-line arguments (untrusted).</param>
    /// <param name="streams">Where to write.</param>
    /// <param name="cancellationToken">Cancelled by SIGINT/SIGTERM.</param>
    /// <returns>The process exit code.</returns>
    public static async Task<int> RunAsync(string[] args, CliStreams streams, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(args);
        ArgumentNullException.ThrowIfNull(streams);
        var (root, globals) = BuildRootCommand(streams);
        var parseResult = root.Parse(args, ParserSettings);
        var settings = globals.ReadLenient(parseResult);

        if (parseResult.Errors.Count > 0 && parseResult.Action is not { ClearsParseErrors: true })
        {
            var message = string.Join(" ", parseResult.Errors.Select(e => e.Message));
            var usage = new CliException(
                ExitCode.Usage,
                message,
                $"Run `{CommandPath(parseResult)} --help` for usage.");
            return ErrorReporter.Report(streams.Error, usage, settings.Format, ColorPolicy.ForStderr(settings, streams.Terminal));
        }

        var invocation = new InvocationConfiguration
        {
            Output = streams.Out,
            Error = streams.Error,
            EnableDefaultExceptionHandler = false,
            ProcessTerminationTimeout = null,
        };

        if (cancellationToken.IsCancellationRequested)
        {
            return (int)ExitCode.Interrupted;
        }

        try
        {
            return await parseResult.InvokeAsync(invocation, cancellationToken).ConfigureAwait(false);
        }
        catch (CliException exception)
        {
            return ErrorReporter.Report(streams.Error, exception, settings.Format, ColorPolicy.ForStderr(settings, streams.Terminal));
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return (int)ExitCode.Interrupted;
        }
        catch (IOException exception) when (IsBrokenPipe(exception))
        {
            // The reader went away (e.g. `| head -n1`): stop quietly.
            return (int)ExitCode.Ok;
        }
#pragma warning disable CA1031 // Any other exception is a bug: report it as INTERNAL instead of crashing with a stack trace.
        catch (Exception exception)
#pragma warning restore CA1031
        {
            new DiagnosticLog(streams.Error, settings).Debug(exception.ToString());
            var internalError = new CliException(
                ExitCode.Internal,
                $"Unexpected error: {exception.Message}",
                "This is a bug. Re-run with --verbose for details and report it.");
            return ErrorReporter.Report(streams.Error, internalError, settings.Format, ColorPolicy.ForStderr(settings, streams.Terminal));
        }
    }

    /// <summary>
    /// Writes the one-line interruption notice and returns the signal's exit code.
    /// </summary>
    /// <param name="streams">Where to write.</param>
    /// <param name="signal">130 (SIGINT) or 143 (SIGTERM).</param>
    /// <returns>The process exit code.</returns>
    public static int ReportSignal(CliStreams streams, ExitCode signal)
    {
        streams.Error.Write($"{ToolInfo.BinName}: {(signal == ExitCode.Terminated ? "terminated" : "interrupted")}\n");
        return (int)signal;
    }

    private static string CommandPath(ParseResult parseResult)
    {
        var names = new List<string>();
        for (var result = parseResult.CommandResult; result is not null; result = result.Parent as System.CommandLine.Parsing.CommandResult)
        {
            names.Insert(0, result.Command.Name);
        }

        return string.Join(' ', names);
    }

    private static bool IsBrokenPipe(IOException exception) =>
        exception.HResult == 32 || exception.Message.Contains("pipe", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// The default help, followed by the command's examples.
    /// </summary>
    private sealed class ExamplesHelpAction : SynchronousCommandLineAction
    {
        private readonly HelpAction _defaultHelp = new();

        public override int Invoke(ParseResult parseResult)
        {
            _defaultHelp.Invoke(parseResult);
            if (parseResult.CommandResult.Command is ToolCommand { Examples.Count: > 0 } command)
            {
                var output = parseResult.InvocationConfiguration.Output;
                output.Write("Examples:\n");
                foreach (var example in command.Examples)
                {
                    output.Write($"  {example}\n");
                }

                output.Write('\n');
            }

            return 0;
        }
    }

    /// <summary>
    /// Prints <see cref="ToolInfo.Version"/> (not the host's entry assembly version) on stdout.
    /// </summary>
    private sealed class VersionAction : SynchronousCommandLineAction
    {
        public override int Invoke(ParseResult parseResult)
        {
            parseResult.InvocationConfiguration.Output.Write($"{ToolInfo.Version}\n");
            return 0;
        }
    }
}
```

### `src/__ToolName__/CliStreams.cs`

```csharp
using __ToolName__.Core.Terminal;

namespace __ToolName__;

/// <summary>
/// The standard streams and terminal snapshot a run writes to. Tests pass <see cref="StringWriter"/>s and a
/// hand-built <see cref="TerminalEnvironment"/> to run the whole command tree in-process.
/// </summary>
/// <param name="Out">stdout — results only.</param>
/// <param name="Error">stderr — messages, diagnostics, error envelopes.</param>
/// <param name="Terminal">Which streams are terminals, and the relevant environment variables.</param>
internal sealed record CliStreams(TextWriter Out, TextWriter Error, TerminalEnvironment Terminal)
{
    /// <summary>
    /// Gets the streams of the current process.
    /// </summary>
    /// <returns>The console streams.</returns>
    public static CliStreams FromConsole() => new(Console.Out, Console.Error, TerminalEnvironment.FromSystem());
}
```

### `src/__ToolName__/CommandTreeReader.cs`

```csharp
using System.CommandLine;
using System.CommandLine.Completions;
using System.Globalization;
using __ToolName__.Core;
using __ToolName__.Core.Schema;

namespace __ToolName__;

/// <summary>
/// Walks the System.CommandLine tree into the framework-neutral <see cref="CommandSpec"/> that <c>schema</c>,
/// <c>completion</c> and the generated CLI reference render. Hidden symbols are skipped.
/// </summary>
internal static class CommandTreeReader
{
    /// <summary>
    /// Reads the whole tree below <paramref name="root"/>.
    /// </summary>
    /// <param name="root">The root command.</param>
    /// <returns>The spec, named after the binary.</returns>
    public static CommandSpec Read(Command root)
    {
        ArgumentNullException.ThrowIfNull(root);
        return Read(root, root, [], ToolInfo.BinName);
    }

    private static CommandSpec Read(Command root, Command command, IReadOnlyList<string> path, string name)
    {
        // Parsing just the command path yields the implicit (default) value of every option at that level.
        var defaults = root.Parse(path.ToArray());
        var fullPath = string.Join(' ', path.Prepend(ToolInfo.BinName));
        var tool = command as ToolCommand;
        return new CommandSpec(
            command.Arguments.Where(a => !a.Hidden).Select(ReadArgument).ToList(),
            command.Subcommands.Where(c => !c.Hidden).Select(c => Read(root, c, [.. path, c.Name], c.Name)).ToList(),
            command.Description ?? string.Empty,
            name,
            command.Options.Where(o => !o.Hidden).Select(o => ReadOption(o, defaults)).ToList(),
            tool?.OutputSchemaVersion,
            fullPath,
            tool?.Safety ?? CommandSafety.ReadOnlyCommand);
    }

    private static ArgumentSpec ReadArgument(Argument argument) =>
        new(
            Arity(argument.Arity),
            argument.HasDefaultValue ? Format(argument.GetDefaultValue()) : null,
            argument.Description ?? string.Empty,
            argument.Name,
            TypeName(argument.ValueType),
            Values(argument.ValueType, argument.GetCompletions(CompletionContext.Empty)));

    private static OptionSpec ReadOption(Option option, ParseResult defaults)
    {
        var isFlag = option.ValueType == typeof(bool);
        var defaultValue = isFlag ? "false" : null;
        if (defaults.GetResult(option) is { Implicit: true } implicitResult)
        {
            defaultValue = Format(implicitResult.GetValueOrDefault<object?>());
        }

        return new OptionSpec(
            option.Aliases.ToList(),
            defaultValue,
            option.Description ?? string.Empty,
            option.Name,
            option.Recursive,
            isFlag && option.Arity.MaximumNumberOfValues == 0,
            option.Required,
            TypeName(option.ValueType),
            Values(option.ValueType, option.GetCompletions(CompletionContext.Empty)));
    }

    private static string Arity(ArgumentArity arity) =>
        arity.MaximumNumberOfValues >= 100_000
            ? $"{arity.MinimumNumberOfValues}..*"
            : $"{arity.MinimumNumberOfValues}..{arity.MaximumNumberOfValues}";

    private static string TypeName(Type type)
    {
        var underlying = Nullable.GetUnderlyingType(type) ?? type;
        if (underlying == typeof(bool))
        {
            return "boolean";
        }

        if (underlying == typeof(int) || underlying == typeof(long))
        {
            return "integer";
        }

        if (underlying == typeof(double) || underlying == typeof(decimal))
        {
            return "number";
        }

        if (underlying == typeof(FileInfo) || underlying == typeof(DirectoryInfo))
        {
            return "path";
        }

        return underlying.IsArray ? "array" : "string";
    }

    private static List<string> Values(Type type, IEnumerable<CompletionItem> completions) =>
        type == typeof(bool) ? [] : completions.Select(c => c.Label).ToList();

    private static string? Format(object? value) => value switch
    {
        null => null,
        bool flag => flag ? "true" : "false",
        IFormattable formattable => formattable.ToString(null, CultureInfo.InvariantCulture),
        _ => value.ToString(),
    };
}
```

### `src/__ToolName__/ConsoleFactory.cs`

```csharp
using Spectre.Console;

namespace __ToolName__;

/// <summary>
/// Creates Spectre.Console consoles that obey the colour policy instead of Spectre's own detection, so redirected
/// output, <c>NO_COLOR</c>, <c>TERM=dumb</c> and <c>--no-color</c> never get ANSI escape sequences.
/// </summary>
internal static class ConsoleFactory
{
    /// <summary>
    /// Creates a console writing to <paramref name="writer"/>.
    /// </summary>
    /// <param name="writer">stdout or stderr.</param>
    /// <param name="color">The <see cref="Core.Terminal.ColorPolicy"/> decision for that stream.</param>
    /// <param name="interactive">Whether the stream is a terminal a person is looking at (spinners, live views).</param>
    /// <returns>The console.</returns>
    public static IAnsiConsole Create(TextWriter writer, bool color, bool interactive)
    {
        var console = AnsiConsole.Create(new AnsiConsoleSettings
        {
            Ansi = color ? AnsiSupport.Yes : AnsiSupport.No,
            ColorSystem = color ? ColorSystemSupport.Standard : ColorSystemSupport.NoColors,
            Interactive = interactive ? InteractionSupport.Yes : InteractionSupport.No,
            Out = new AnsiConsoleOutput(writer),
        });
        if (!interactive)
        {
            // Never wrap redirected output at an imaginary 80-column terminal.
            console.Profile.Width = int.MaxValue / 2;
        }

        return console;
    }
}
```

### `src/__ToolName__/GlobalOptions.cs`

```csharp
using System.CommandLine;
using __ToolName__.Core;
using __ToolName__.Core.Output;

namespace __ToolName__;

/// <summary>
/// The global flags (CLI contract §1/§3/§4), declared once on the root and inherited by every subcommand.
/// </summary>
internal sealed class GlobalOptions
{
    /// <summary>
    /// Initializes a new instance of the <see cref="GlobalOptions"/> class.
    /// </summary>
    public GlobalOptions()
    {
        Format.AcceptOnlyFromAmong("text", "json", "jsonl");
        LogFormat.AcceptOnlyFromAmong("text", "json");
    }

    /// <summary>Gets <c>--format text|json|jsonl</c>.</summary>
    public Option<string> Format { get; } = new("--format")
    {
        Description = "Result format on stdout.",
        DefaultValueFactory = _ => "text",
        Recursive = true,
    };

    /// <summary>Gets <c>--json</c>, an alias for <c>--format json</c>.</summary>
    public Option<bool> Json { get; } = new("--json")
    {
        Description = "Same as --format json.",
        Recursive = true,
    };

    /// <summary>Gets <c>--no-color</c>.</summary>
    public Option<bool> NoColor { get; } = new("--no-color")
    {
        Description = "Never write colour or styling (NO_COLOR is honoured too).",
        Recursive = true,
    };

    /// <summary>Gets <c>--no-input</c>.</summary>
    public Option<bool> NoInput { get; } = new("--no-input")
    {
        Description = "Never prompt; fail with the flag to pass instead (also " + ToolInfo.EnvPrefix + "NO_INPUT=1).",
        Recursive = true,
    };

    /// <summary>Gets <c>--quiet</c>/<c>-q</c>.</summary>
    public Option<bool> Quiet { get; } = new("--quiet", "-q")
    {
        Description = "Suppress informational messages on stderr (errors are still shown).",
        Recursive = true,
    };

    /// <summary>Gets <c>--verbose</c>/<c>-v</c>, repeatable (<c>-vv</c>).</summary>
    public Option<bool> Verbose { get; } = new("--verbose", "-v")
    {
        Description = "Add diagnostics on stderr; repeat for more (-vv).",
        Arity = ArgumentArity.Zero,
        Recursive = true,
    };

    /// <summary>Gets <c>--log-format text|json</c>.</summary>
    public Option<string> LogFormat { get; } = new("--log-format")
    {
        Description = "Format of diagnostics on stderr.",
        DefaultValueFactory = _ => "text",
        Recursive = true,
    };

    /// <summary>
    /// Gets every global option, in help order.
    /// </summary>
    public IEnumerable<Option> All => [Format, Json, NoColor, NoInput, Quiet, Verbose, LogFormat];

    /// <summary>
    /// Reads the global flags from a successful parse.
    /// </summary>
    /// <param name="parseResult">The parse result.</param>
    /// <returns>The settings.</returns>
    /// <exception cref="CliException"><c>--json</c> conflicts with another <c>--format</c> (exit code 2).</exception>
    public GlobalSettings Read(ParseResult parseResult)
    {
        var format = GlobalSettings.ParseFormat(parseResult.GetValue(Format) ?? "text");
        if (parseResult.GetValue(Json))
        {
            if (format == OutputFormat.Jsonl)
            {
                throw new CliException(
                    ExitCode.Usage,
                    "--json conflicts with --format jsonl.",
                    "Pass either --json or --format jsonl, not both.");
            }

            format = OutputFormat.Json;
        }

        return new GlobalSettings(
            format,
            parseResult.GetValue(NoColor),
            parseResult.GetValue(NoInput),
            parseResult.GetValue(Quiet),
            parseResult.GetResult(Verbose)?.IdentifierTokenCount ?? 0,
            GlobalSettings.ParseLogFormat(parseResult.GetValue(LogFormat) ?? "text"));
    }

    /// <summary>
    /// Reads the global flags from a parse that may have errors, falling back to defaults for unreadable values —
    /// used only to decide how to report a usage error.
    /// </summary>
    /// <param name="parseResult">The parse result.</param>
    /// <returns>The best-effort settings.</returns>
    public GlobalSettings ReadLenient(ParseResult parseResult)
    {
        try
        {
            return Read(parseResult);
        }
        catch (Exception exception) when (exception is CliException or InvalidOperationException or ArgumentException)
        {
            var json = parseResult.GetResult(Json) is not null;
            return GlobalSettings.Default with { Format = json ? OutputFormat.Json : OutputFormat.Text };
        }
    }
}
```

### `src/__ToolName__/Program.cs`

```csharp
using __ToolName__;
using __ToolName__.Core.Terminal;

// Thin entry point: install signal handling, run the command tree against the real console, map signals to 130/143.
using var shutdown = ShutdownCoordinator.Install();
var streams = CliStreams.FromConsole();
var exitCode = await CliApp.RunAsync(args, streams, shutdown.Token).ConfigureAwait(false);
return shutdown.SignalExitCode is { } signal ? CliApp.ReportSignal(streams, signal) : exitCode;
```

### `src/__ToolName__/ToolCommand.cs`

```csharp
using System.CommandLine;
using __ToolName__.Core.Schema;

namespace __ToolName__;

/// <summary>
/// A command with the metadata <c>schema</c> and the help examples need: its safety and output schema version.
/// </summary>
internal sealed class ToolCommand : Command
{
    /// <summary>
    /// Initializes a new instance of the <see cref="ToolCommand"/> class.
    /// </summary>
    /// <param name="name">The command name.</param>
    /// <param name="description">One line saying when to use it.</param>
    /// <param name="safety">Whether it reads, mutates or destroys.</param>
    /// <param name="outputSchemaVersion">The <c>schemaVersion</c> of its JSON output, or <see langword="null"/>.</param>
    /// <param name="examples">Real command lines shown under "Examples:" in its help.</param>
    public ToolCommand(string name, string description, CommandSafety safety, int? outputSchemaVersion, params string[] examples)
        : base(name, description)
    {
        Safety = safety;
        OutputSchemaVersion = outputSchemaVersion;
        Examples = examples;
    }

    /// <summary>
    /// Gets the safety metadata.
    /// </summary>
    public CommandSafety Safety { get; }

    /// <summary>
    /// Gets the output schema version, if the command writes JSON.
    /// </summary>
    public int? OutputSchemaVersion { get; }

    /// <summary>
    /// Gets the example command lines.
    /// </summary>
    public IReadOnlyList<string> Examples { get; }
}
```

### `tests/__ToolName__.Cli.Tests/BrowseTests.cs` (ui: tui)

```csharp
namespace __ToolName__.Cli.Tests;

[TestClass]
public sealed class BrowseTests
{
    [TestMethod]
    public async Task Browse_WithoutATerminal_Exits2_AndPointsToTheJsonCommand()
    {
        var result = await CliRunner.RunAsync("browse");

        Assert.AreEqual(2, result.ExitCode);
        Assert.AreEqual(string.Empty, result.Stdout);
        Assert.Contains("greet <NAME> --json", result.Stderr);
    }

    [TestMethod]
    public async Task Browse_InMachineMode_Exits2()
    {
        var result = await CliRunner.RunAsync(CliRunner.Interactive, "browse", "--json");

        Assert.AreEqual(2, result.ExitCode);
    }
}
```

### `tests/__ToolName__.Cli.Tests/AccessibilityCheck.cs` (ui: tui)

```csharp
namespace __ToolName__.Cli.Tests;

/// <summary>
/// Applies the <c>.ui-quality.yml</c> accessibility level to the asserted terminal-UI checks (no automated audit
/// exists for terminals): <c>enforce</c> fails the test, <c>report</c> appends to
/// <c>&lt;screenshots dir&gt;/a11y-report.txt</c>, <c>off</c> and <c>none</c> ignore them.
/// </summary>
internal static class AccessibilityCheck
{
    private static readonly Lock ReportLock = new();

    /// <summary>Applies the configured level to <paramref name="violations"/>.</summary>
    /// <param name="target">What was checked, e.g. <c>BrowseView default rtl</c>.</param>
    /// <param name="violations">Each violation as <c>rule-id: message</c> (<c>tui-color-only</c>, <c>tui-help-line</c>, <c>tui-keyboard</c>).</param>
    public static void Apply(string target, IReadOnlyList<string> violations)
    {
        var level = UiScreenshots.QualitySetting("accessibility") ?? "enforce";
        if (violations.Count == 0 || level is "off" or "none")
        {
            return;
        }

        if (level == "enforce")
        {
            Assert.Fail($"[a11y] {target}: {string.Join("; ", violations)}");
        }

        Directory.CreateDirectory(UiScreenshots.RootDirectory);
        lock (ReportLock)
        {
            File.AppendAllLines(
                Path.Combine(UiScreenshots.RootDirectory, "a11y-report.txt"),
                violations.Select(violation => $"{target}\t{violation}"));
        }
    }
}
```

### `tests/__ToolName__.Cli.Tests/UiScreenshots.cs` (ui: tui)

```csharp
using System.Text.RegularExpressions;

namespace __ToolName__.Cli.Tests;

/// <summary>
/// Writes terminal-UI captures (the rendered screen buffer as text) to
/// <c>&lt;UI_SCREENSHOTS_DIR or repo root/.ui-screenshots&gt;/&lt;kind&gt;/&lt;Id&gt;/&lt;state&gt;--&lt;direction&gt;--no-color.txt</c>,
/// and reads the directions to capture from the repository's <c>.ui-quality.yml</c> (default <c>[ltr, rtl]</c>).
/// </summary>
internal static partial class UiScreenshots
{
    private static readonly string[] Kinds = ["component", "screen", "flow"];

    private static readonly Lazy<string> Root = new(FindRepositoryRoot);

    /// <summary>Gets the capture root: <c>UI_SCREENSHOTS_DIR</c> when set, else <c>.ui-screenshots</c> at the repository root.</summary>
    public static string RootDirectory =>
        Environment.GetEnvironmentVariable("UI_SCREENSHOTS_DIR") is { Length: > 0 } configured
            ? configured
            : Path.Combine(Root.Value, ".ui-screenshots");

    /// <summary>Gets the <c>directions</c> list of <c>.ui-quality.yml</c>, or <c>[ltr, rtl]</c>.</summary>
    public static IReadOnlyList<string> Directions =>
        QualitySetting("directions") is { } value
            ? value.Trim('[', ']').Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            : ["ltr", "rtl"];

    /// <summary>Reads one <c>key: value</c> line of the repository's <c>.ui-quality.yml</c>.</summary>
    /// <param name="key">The key (<c>accessibility</c>, <c>directions</c>, <c>themes</c>).</param>
    /// <returns>The trimmed value, or <see langword="null"/> when the file or key is missing.</returns>
    public static string? QualitySetting(string key)
    {
        var file = Path.Combine(Root.Value, ".ui-quality.yml");
        return File.Exists(file)
            ? File.ReadLines(file)
                .Select(line => line.Split('#')[0].Trim())
                .Where(line => line.StartsWith(key + ":", StringComparison.Ordinal))
                .Select(line => line[(key.Length + 1)..].Trim())
                .FirstOrDefault()
            : null;
    }

    /// <summary>Writes <paramref name="screen"/> as a text capture and returns its path.</summary>
    /// <param name="screen">The rendered buffer (<c>IDriver.ToString()</c>).</param>
    /// <param name="kind"><c>component</c>, <c>screen</c> or <c>flow</c>.</param>
    /// <param name="id">The view type name as in code, or the kebab-case flow name.</param>
    /// <param name="state">The kebab-case visual state.</param>
    /// <param name="direction"><c>ltr</c> or <c>rtl</c> (right-to-left content in the normal terminal layout).</param>
    /// <returns>The written file's path.</returns>
    public static string SaveText(string screen, string kind, string id, string state, string direction)
    {
        if (!Kinds.Contains(kind) || !IdPattern().IsMatch(id) || !KebabPattern().IsMatch(state) || direction is not ("ltr" or "rtl"))
        {
            throw new ArgumentException($"Not a contract capture name: {kind}/{id}/{state}--{direction}--no-color.txt");
        }

        var directory = Path.Combine(RootDirectory, kind, id);
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, $"{state}--{direction}--no-color.txt");
        File.WriteAllText(path, screen);
        return path;
    }

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

    [GeneratedRegex("^[a-z0-9]+(-[a-z0-9]+)*$")]
    private static partial Regex KebabPattern();

    [GeneratedRegex("^([A-Z][A-Za-z0-9]*|[a-z0-9]+(-[a-z0-9]+)*)$")]
    private static partial Regex IdPattern();
}
```

### `tests/__ToolName__.Cli.Tests/Tui/BrowseViewTests.cs` (ui: tui)

```csharp
using __ToolName__.Core.Browse;
using __ToolName__.Tui;
using Terminal.Gui.App;
using Terminal.Gui.Drivers;
using Terminal.Gui.Input;
using Terminal.Gui.Testing;

namespace __ToolName__.Cli.Tests.Tui;

[TestClass]
[TestCategory("UI")]
[DoNotParallelize]
public sealed class BrowseViewTests
{
    private const string ScreenId = "BrowseView";

    private static readonly string[] States = ["default", "many-items", "long-content"];

    public static IEnumerable<object[]> StatesAndDirections =>
        from state in States
        from direction in UiScreenshots.Directions
        select new object[] { state, direction };

    [TestMethod]
    [DynamicData(nameof(StatesAndDirections))]
    public void Browse_RendersTheState_AndCapturesIt(string state, string direction)
    {
        var browseState = new BrowseState(Fixture(state, direction));

        var screen = Render(browseState);

        // Long values are truncated by the layout, so assert on their visible start.
        Assert.Contains(string.Concat(browseState.Names[0].Take(4)), screen);
        Assert.Contains(string.Concat(browseState.SelectedGreeting.Take(10)), screen);
        UiScreenshots.SaveText(screen, "screen", ScreenId, state, direction);
        AccessibilityCheck.Apply($"{ScreenId} {state} {direction}", Violations(browseState, screen));
    }

    [TestMethod]
    public void CursorDown_SelectsTheNextName_AndShowsItsGreeting()
    {
        var browseState = new BrowseState(BrowseState.SampleNames);

        var screen = Render(browseState, app =>
        {
            app.InjectKey(Key.CursorDown);
            app.InjectKey(Key.CursorDown);
        });

        Assert.AreEqual(1, browseState.SelectedIndex);
        Assert.Contains(browseState.SelectedGreeting, screen);
    }

    private static List<string> Violations(BrowseState browseState, string screen)
    {
        var violations = new List<string>();
        if (!screen.Contains(string.Concat(browseState.SelectedGreeting.Take(10)), StringComparison.Ordinal))
        {
            violations.Add("tui-color-only: the selection is not readable as text");
        }

        if (!screen.Contains("Esc", StringComparison.Ordinal))
        {
            violations.Add("tui-help-line: no key help on screen");
        }

        return violations;
    }

    private static string[] Fixture(string state, string direction) => (state, direction) switch
    {
        ("many-items", _) => [.. Enumerable.Range(1, 500).Select(index => $"Name {index}")],
        ("long-content", "rtl") => ["عبد الرحمن بن محمد بن عبد الله الطويل الاسم جدا", "דוד"],
        ("long-content", _) => ["Maximiliana Wolfeschlegelsteinhausenbergerdorff", "Ada"],
        (_, "rtl") => ["سارة", "דוד", "مريم"],
        _ => [.. BrowseState.SampleNames],
    };

    private static string Render(BrowseState browseState, Action<IApplication>? act = null)
    {
        using var app = Application.Create();
        app.Init(DriverRegistry.Names.ANSI);
        app.Driver!.SetScreenSize(60, 12);
        using var window = BrowseView.Create(browseState, "__BinName__");
        var token = app.Begin(window);
        app.LayoutAndDraw(true);
        act?.Invoke(app);
        app.LayoutAndDraw(true);
        var screen = app.Driver.ToString() ?? string.Empty;
        app.End(token!);
        return screen;
    }
}
```

### `tests/__ToolName__.Cli.Tests/CliResult.cs`

```csharp
namespace __ToolName__.Cli.Tests;

/// <summary>
/// The outcome of one run: exit code and everything written to stdout and stderr.
/// </summary>
internal sealed record CliResult(int ExitCode, string Stdout, string Stderr);
```

### `tests/__ToolName__.Cli.Tests/CliRunner.cs`

```csharp
using __ToolName__.Core.Terminal;

namespace __ToolName__.Cli.Tests;

/// <summary>
/// Runs the real command tree in-process with captured stdout/stderr and a chosen terminal snapshot.
/// </summary>
internal static class CliRunner
{
    public static readonly TerminalEnvironment Piped = new(false, false, false, new Dictionary<string, string>());

    public static readonly TerminalEnvironment Interactive = new(true, true, true, new Dictionary<string, string>());

    public static async Task<CliResult> RunAsync(TerminalEnvironment terminal, params string[] args)
    {
        using var stdout = new StringWriter();
        using var stderr = new StringWriter();
        var exitCode = await CliApp.RunAsync(args, new CliStreams(stdout, stderr, terminal), CancellationToken.None);
        return new CliResult(exitCode, stdout.ToString(), stderr.ToString());
    }

    public static Task<CliResult> RunAsync(params string[] args) => RunAsync(Piped, args);
}
```

### `tests/__ToolName__.Cli.Tests/ContractTests.cs`

```csharp
using System.Text.Json;
using __ToolName__.Core;

namespace __ToolName__.Cli.Tests;

/// <summary>
/// The CLI contract, end to end through the command tree: streams, exit codes, colour, JSON output and errors.
/// </summary>
[TestClass]
public sealed class ContractTests
{
    [TestMethod]
    public async Task Greet_Text_WritesOnlyTheResultOnStdout()
    {
        var result = await CliRunner.RunAsync("greet", "Ada");

        Assert.AreEqual(0, result.ExitCode);
        Assert.AreEqual("Hello, Ada!\n", result.Stdout.ReplaceLineEndings("\n"));
        Assert.AreEqual(string.Empty, result.Stderr);
    }

    [TestMethod]
    public async Task Greet_WithoutName_GreetsTheWorld()
    {
        var result = await CliRunner.RunAsync("greet");

        Assert.AreEqual("Hello, world!\n", result.Stdout.ReplaceLineEndings("\n"));
    }

    [TestMethod]
    [DataRow("--json")]
    [DataRow("--format=json")]
    public async Task Greet_Json_MatchesTheGolden(string flag)
    {
        var result = await CliRunner.RunAsync("greet", "Ada", flag);

        Assert.AreEqual(0, result.ExitCode);
        Assert.AreEqual("{\"greeting\":\"Hello, Ada!\",\"name\":\"Ada\",\"schemaVersion\":1}\n", result.Stdout);
        Assert.AreEqual(string.Empty, result.Stderr);
    }

    [TestMethod]
    public async Task Greet_Jsonl_EndsWithASummaryLine()
    {
        var result = await CliRunner.RunAsync("--format", "jsonl", "greet", "Ada");

        var lines = result.Stdout.Split('\n', StringSplitOptions.RemoveEmptyEntries);
        Assert.HasCount(2, lines);
        Assert.AreEqual("{\"nextCursor\":null,\"schemaVersion\":1,\"total\":1,\"type\":\"summary\"}", lines[1]);
    }

    [TestMethod]
    public async Task ValidationError_Exits3_WithAnEnvelopeOnStderr()
    {
        var result = await CliRunner.RunAsync("greet", string.Empty, "--json");

        Assert.AreEqual((int)ExitCode.Validation, result.ExitCode);
        Assert.AreEqual(string.Empty, result.Stdout);
        using var envelope = JsonDocument.Parse(result.Stderr);
        var error = envelope.RootElement.GetProperty("error");
        Assert.AreEqual("INVALID_NAME", error.GetProperty("code").GetString());
        Assert.AreEqual(3, error.GetProperty("exitCode").GetInt32());
        Assert.IsFalse(string.IsNullOrEmpty(error.GetProperty("hint").GetString()));
        Assert.AreEqual(1, envelope.RootElement.GetProperty("schemaVersion").GetInt32());
    }

    [TestMethod]
    public async Task ValidationError_Text_PrintsMessageAndHint()
    {
        var result = await CliRunner.RunAsync("greet", new string('x', 65));

        Assert.AreEqual(3, result.ExitCode);
        Assert.StartsWith($"{ToolInfo.BinName}: The name must be at most 64 characters long.\nhint: ", result.Stderr);
    }

    [TestMethod]
    [DataRow("gree")]
    [DataRow("greet", "--frmat", "json")]
    [DataRow("greet", "--format", "yaml")]
    [DataRow("greet", "--nope")]
    [DataRow("@args.rsp")]
    [DataRow("--version", "greet")]
    [DataRow("completion", "tcsh")]
    public async Task UsageErrors_Exit2_WithAHelpPointer(params string[] args)
    {
        var result = await CliRunner.RunAsync(args);

        Assert.AreEqual((int)ExitCode.Usage, result.ExitCode);
        Assert.AreEqual(string.Empty, result.Stdout);
        Assert.Contains("--help` for usage.", result.Stderr);
    }

    [TestMethod]
    public async Task NoCommand_IsAUsageError()
    {
        var result = await CliRunner.RunAsync();

        Assert.AreEqual((int)ExitCode.Usage, result.ExitCode);
        Assert.Contains($"Run `{ToolInfo.BinName} --help` for usage.", result.Stderr);
    }

    [TestMethod]
    public async Task UsageError_InJsonMode_IsAnEnvelope()
    {
        var result = await CliRunner.RunAsync("gree", "--json");

        using var envelope = JsonDocument.Parse(result.Stderr);
        Assert.AreEqual("USAGE", envelope.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.AreEqual(2, result.ExitCode);
    }

    [TestMethod]
    public async Task JsonAndJsonl_Conflict_IsAUsageError()
    {
        var result = await CliRunner.RunAsync("greet", "--json", "--format", "jsonl");

        Assert.AreEqual(2, result.ExitCode);
        Assert.Contains("\"code\":\"USAGE\"", result.Stderr);
    }

    [TestMethod]
    public async Task HelpAndVersion_AreResultsOnStdout()
    {
        var help = await CliRunner.RunAsync("greet", "--help");
        var version = await CliRunner.RunAsync("--version");

        Assert.AreEqual(0, help.ExitCode);
        Assert.Contains("Examples:", help.Stdout);
        Assert.Contains($"{ToolInfo.BinName} greet Ada --json", help.Stdout);
        Assert.AreEqual(string.Empty, help.Stderr);
        Assert.AreEqual(0, version.ExitCode);
        Assert.AreEqual($"{ToolInfo.Version}\n", version.Stdout);
    }

    [TestMethod]
    public async Task Interactive_IsColoured_ButNoColorAndNoColorFlagAreNot()
    {
        var coloured = await CliRunner.RunAsync(CliRunner.Interactive, "greet", "Ada");
        var noColorEnv = await CliRunner.RunAsync(
            CliRunner.Interactive with { Variables = new Dictionary<string, string> { ["NO_COLOR"] = "1" } },
            "greet",
            "Ada");
        var noColorFlag = await CliRunner.RunAsync(CliRunner.Interactive, "greet", "Ada", "--no-color");
        var dumb = await CliRunner.RunAsync(
            CliRunner.Interactive with { Variables = new Dictionary<string, string> { ["TERM"] = "dumb" } },
            "greet",
            "Ada");

        Assert.Contains("\u001b[", coloured.Stdout);
        Assert.DoesNotContain("\u001b", noColorEnv.Stdout);
        Assert.DoesNotContain("\u001b", noColorFlag.Stdout);
        Assert.DoesNotContain("\u001b", dumb.Stdout);
    }

    [TestMethod]
    public async Task Piped_IsNeverColoured_EvenForErrors()
    {
        var ok = await CliRunner.RunAsync("greet", "Ada");
        var failed = await CliRunner.RunAsync("greet", string.Empty);

        Assert.DoesNotContain("\u001b", ok.Stdout + ok.Stderr + failed.Stdout + failed.Stderr);
    }

    [TestMethod]
    public async Task Verbose_WritesDiagnosticsOnStderrOnly()
    {
        var quietRun = await CliRunner.RunAsync("greet", "Ada", "--json");
        var verbose = await CliRunner.RunAsync("greet", "Ada", "--json", "-vv", "--log-format", "json");

        Assert.AreEqual(quietRun.Stdout, verbose.Stdout);
        Assert.StartsWith("{\"level\":\"debug\",\"message\":\"greet: format=Json\"}", verbose.Stderr);
    }

    [TestMethod]
    public async Task OptionLookingName_AfterDoubleDash_IsStillRejectedAsAnOption()
    {
        var result = await CliRunner.RunAsync("greet", "--", "-x");

        Assert.AreEqual(2, result.ExitCode);
    }

    [TestMethod]
    public async Task Signals_MapTo130And143()
    {
        var interrupted = new StringWriter();
        var streams = new CliStreams(TextWriter.Null, interrupted, CliRunner.Piped);

        Assert.AreEqual(130, CliApp.ReportSignal(streams, ExitCode.Interrupted));
        Assert.AreEqual(143, CliApp.ReportSignal(streams, ExitCode.Terminated));
        Assert.AreEqual($"{ToolInfo.BinName}: interrupted\n{ToolInfo.BinName}: terminated\n", interrupted.ToString());
        await Task.CompletedTask;
    }

    [TestMethod]
    public async Task CancelledBeforeRunning_ReportsInterrupted()
    {
        using var cancelled = new CancellationTokenSource();
        await cancelled.CancelAsync();

        var exitCode = await CliApp.RunAsync(["greet"], new CliStreams(TextWriter.Null, TextWriter.Null, CliRunner.Piped), cancelled.Token);

        Assert.AreEqual((int)ExitCode.Interrupted, exitCode);
    }
}
```

### `tests/__ToolName__.Cli.Tests/MSTestSettings.cs`

```csharp
[assembly: Parallelize(Scope = ExecutionScope.MethodLevel)]
```

### `tests/__ToolName__.Cli.Tests/PublishedBinaryTests.cs`

```csharp
using System.Diagnostics;

namespace __ToolName__.Cli.Tests;

/// <summary>
/// Smoke tests against a published (Native AOT) binary. They run only when <c>__BinEnvPrefix___BINARY</c> points to
/// one, e.g. in CI after <c>dotnet publish src/__ToolName__ -r &lt;rid&gt;</c>; otherwise they are skipped (inconclusive).
/// </summary>
[TestClass]
[TestCategory("PublishedBinary")]
public sealed class PublishedBinaryTests
{
    private static readonly string? Binary = Environment.GetEnvironmentVariable("__BinEnvPrefix___BINARY");

    [TestMethod]
    public async Task Version_Help_AndUsageErrors()
    {
        Assert.AreEqual(0, (await RunAsync("--version")).ExitCode);
        Assert.AreEqual(0, (await RunAsync("--help")).ExitCode);
        var usage = await RunAsync("no-such-command");
        Assert.AreEqual(2, usage.ExitCode);
        Assert.AreEqual(string.Empty, usage.Stdout);
    }

    [TestMethod]
    public async Task Json_IsPure_AndPipedOutputHasNoEscapes()
    {
        var result = await RunAsync("greet", "Ada", "--json");

        Assert.AreEqual("{\"greeting\":\"Hello, Ada!\",\"name\":\"Ada\",\"schemaVersion\":1}\n", result.Stdout);
        Assert.DoesNotContain("\u001b", (await RunAsync("greet", "Ada")).Stdout);
    }

    private static async Task<CliResult> RunAsync(params string[] args)
    {
        if (string.IsNullOrEmpty(Binary))
        {
            Assert.Inconclusive("Set __BinEnvPrefix___BINARY to a published binary to run these tests.");
        }

        var start = new ProcessStartInfo(Binary!)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        foreach (var arg in args)
        {
            start.ArgumentList.Add(arg);
        }

        using var process = Process.Start(start)!;
        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync();
        return new CliResult(process.ExitCode, await stdout, await stderr);
    }
}
```

### `tests/__ToolName__.Cli.Tests/SchemaAndCompletionTests.cs`

```csharp
using System.Text.Json;
using __ToolName__.Core;

namespace __ToolName__.Cli.Tests;

[TestClass]
public sealed class SchemaAndCompletionTests
{
    [TestMethod]
    public async Task Schema_DescribesTheTree_ExitCodesAndSafety()
    {
        var result = await CliRunner.RunAsync("schema");

        Assert.AreEqual(0, result.ExitCode);
        using var schema = JsonDocument.Parse(result.Stdout);
        var root = schema.RootElement;
        Assert.AreEqual(ToolInfo.BinName, root.GetProperty("name").GetString());
        Assert.AreEqual(ExitCodes.All.Count, root.GetProperty("exitCodes").GetArrayLength());
        var greet = root.GetProperty("command").GetProperty("commands").EnumerateArray()
            .Single(c => c.GetProperty("name").GetString() == "greet");
        Assert.IsTrue(greet.GetProperty("safety").GetProperty("readOnly").GetBoolean());
        Assert.AreEqual("world", greet.GetProperty("arguments")[0].GetProperty("default").GetString());
        var format = root.GetProperty("command").GetProperty("options").EnumerateArray()
            .Single(o => o.GetProperty("name").GetString() == "--format");
        Assert.AreEqual("text", format.GetProperty("default").GetString());
        Assert.AreEqual(3, format.GetProperty("values").GetArrayLength());
        var verbose = root.GetProperty("command").GetProperty("options").EnumerateArray()
            .Single(o => o.GetProperty("name").GetString() == "--verbose");
        Assert.IsTrue(verbose.GetProperty("repeatable").GetBoolean());
    }

    [TestMethod]
    public async Task Schema_KeysAreSorted_AndOutputIsStable()
    {
        var first = await CliRunner.RunAsync("schema");
        var second = await CliRunner.RunAsync("schema", "--json");

        Assert.AreEqual(first.Stdout, second.Stdout);
        using var schema = JsonDocument.Parse(first.Stdout);
        AssertSorted(schema.RootElement);
    }

    [TestMethod]
    [DataRow("bash", "complete -F")]
    [DataRow("zsh", "#compdef")]
    [DataRow("fish", "complete -c")]
    [DataRow("powershell", "Register-ArgumentCompleter")]
    public async Task Completion_PrintsAScriptGeneratedFromTheTree(string shell, string marker)
    {
        var result = await CliRunner.RunAsync("completion", shell);

        Assert.AreEqual(0, result.ExitCode);
        Assert.Contains(marker, result.Stdout);
        Assert.Contains("greet", result.Stdout);
        Assert.Contains("--format", result.Stdout);
    }

    [TestMethod]
    public async Task GenDocs_IsHidden_AndWritesTheReferenceManPageAndCompletions()
    {
        var root = Path.Combine(Path.GetTempPath(), "gen-docs-" + Guid.NewGuid().ToString("N"));
        try
        {
            var result = await CliRunner.RunAsync("gen-docs", "--output-dir", root);
            var help = await CliRunner.RunAsync("--help");
            var schema = await CliRunner.RunAsync("schema");

            Assert.AreEqual(0, result.ExitCode);
            Assert.Contains("== `" + ToolInfo.BinName + " greet`", File.ReadAllText(Path.Combine(root, "docs/modules/ROOT/pages/cli-reference.adoc")));
            Assert.IsTrue(File.Exists(Path.Combine(root, $"artifacts/man/{ToolInfo.BinName}.1")));
            Assert.HasCount(4, Directory.GetFiles(Path.Combine(root, "artifacts/completions")));
            Assert.DoesNotContain("gen-docs", help.Stdout);
            Assert.DoesNotContain("gen-docs", schema.Stdout);
        }
        finally
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
    }

    private static void AssertSorted(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = element.EnumerateObject().Select(p => p.Name).ToList();
            Assert.AreSequenceEqual(names.Order(StringComparer.Ordinal).ToList(), names);
            foreach (var property in element.EnumerateObject())
            {
                AssertSorted(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                AssertSorted(item);
            }
        }
    }
}
```

### `src/__ToolName__/Commands/BrowseCommand.cs` (ui: tui)

```csharp
using System.CommandLine;
using __ToolName__.Core;
using __ToolName__.Core.Browse;
using __ToolName__.Core.Schema;
using __ToolName__.Tui;
using Terminal.Gui.App;

namespace __ToolName__.Commands;

/// <summary>
/// <c>__BinName__ browse</c> — a full-screen Terminal.Gui demo for people. It starts only when stdin and stdout are
/// terminals; everything it shows is also available non-interactively (<c>greet --json</c>).
/// </summary>
internal static class BrowseCommand
{
    /// <summary>
    /// Creates the command.
    /// </summary>
    /// <param name="globals">The global options.</param>
    /// <param name="streams">Where to write.</param>
    /// <returns>The command.</returns>
    public static Command Create(GlobalOptions globals, CliStreams streams)
    {
        var command = new ToolCommand(
            "browse",
            "Browse greetings in a full-screen terminal UI (interactive terminals only; scripts use greet --json).",
            CommandSafety.ReadOnlyCommand,
            null,
            $"{ToolInfo.BinName} browse");
        command.SetAction((parseResult, cancellationToken) =>
        {
            var settings = globals.Read(parseResult);
            if (settings.MachineMode || !streams.Terminal.StdinIsTerminal || !streams.Terminal.StdoutIsTerminal)
            {
                throw new CliException(
                    ExitCode.Usage,
                    "browse needs an interactive terminal on stdin and stdout.",
                    $"Use `{ToolInfo.BinName} greet <NAME> --json` for the same data without a terminal.");
            }

            Run(new BrowseState(BrowseState.SampleNames), cancellationToken);
            return Task.FromResult((int)ExitCode.Ok);
        });
        return command;
    }

    private static void Run(BrowseState state, CancellationToken cancellationToken)
    {
        using var app = Application.Create();
        app.Init();

        // SIGINT/SIGTERM: leave the main loop so the terminal is restored before the process exits 130/143. A UI-thread
        // timer polls the token, so a signal that arrives before the loop starts is not lost.
        app.AddTimeout(TimeSpan.FromMilliseconds(100), () =>
        {
            if (!cancellationToken.IsCancellationRequested)
            {
                return true;
            }

            app.RequestStop();
            return false;
        });
        using var window = BrowseView.Create(state, ToolInfo.BinName);
        app.Run(window);
    }
}
```

### `src/__ToolName__/Tui/BrowseView.cs` (ui: tui)

```csharp
using System.Collections.ObjectModel;
using __ToolName__.Core.Browse;
using Terminal.Gui.ViewBase;
using Terminal.Gui.Views;

namespace __ToolName__.Tui;

/// <summary>
/// The <c>browse</c> screen, composed from Terminal.Gui views: a <see cref="ListView"/> of names (it draws only the
/// visible rows) and the selected name's greeting. It renders a <see cref="BrowseState"/> and forwards selection
/// changes to it; all logic stays in the state. Visual states: <c>default</c>, <c>many-items</c>, <c>long-content</c>.
/// </summary>
internal static class BrowseView
{
    /// <summary>
    /// Builds the screen's window.
    /// </summary>
    /// <param name="state">The state to render and update.</param>
    /// <param name="toolName">The tool's command name, shown in the title with the key help.</param>
    /// <returns>The window; disposing it disposes its subviews.</returns>
    public static Window Create(BrowseState state, string toolName)
    {
        ArgumentNullException.ThrowIfNull(state);
        var window = new Window { Title = $"{toolName} browse (↑/↓ select, Esc quit)" };
        var list = new ListView { X = 0, Y = 0, Width = Dim.Percent(40), Height = Dim.Fill() };
        var greeting = new Label { X = Pos.Right(list) + 2, Y = 1, Text = state.SelectedGreeting };
        list.SetSource(new ObservableCollection<string>(state.Names));
        list.ValueChanged += (_, args) =>
        {
            state.Select(args.NewValue ?? 0);
            greeting.Text = state.SelectedGreeting;
        };
        window.Add(list, greeting);
        return window;
    }
}
```

### `src/__ToolName__/Commands/CompletionCommand.cs`

```csharp
using System.CommandLine;
using __ToolName__.Core;
using __ToolName__.Core.Completion;
using __ToolName__.Core.Schema;

namespace __ToolName__.Commands;

/// <summary>
/// <c>__BinName__ completion &lt;shell&gt;</c> — prints a completion script generated from the command tree.
/// </summary>
internal static class CompletionCommand
{
    /// <summary>
    /// Creates the command.
    /// </summary>
    /// <param name="globals">The global options.</param>
    /// <param name="streams">Where to write.</param>
    /// <returns>The command.</returns>
    public static Command Create(GlobalOptions globals, CliStreams streams)
    {
        var shell = new Argument<string>("shell") { Description = "The shell to generate a script for." };
        shell.AcceptOnlyFromAmong([.. CompletionScriptGenerator.ShellNames]);
        var command = new ToolCommand(
            "completion",
            "Print a shell completion script. Use it once when installing the tool.",
            CommandSafety.ReadOnlyCommand,
            null,
            $"{ToolInfo.BinName} completion bash > ~/.local/share/bash-completion/completions/{ToolInfo.BinName}",
            $"{ToolInfo.BinName} completion zsh > \"${{fpath[1]}}/_{ToolInfo.BinName}\"",
            $"{ToolInfo.BinName} completion powershell >> $PROFILE")
        {
            shell,
        };
        command.SetAction(parseResult =>
        {
            _ = globals.Read(parseResult);
            var root = CommandTreeReader.Read(parseResult.RootCommandResult.Command);
            var script = CompletionScriptGenerator.Generate(
                CompletionScriptGenerator.ParseShell(parseResult.GetValue(shell)!),
                root);
            streams.Out.Write(script);
            return (int)ExitCode.Ok;
        });
        return command;
    }
}
```

### `src/__ToolName__/Commands/GenDocsCommand.cs`

```csharp
using System.CommandLine;
using __ToolName__.Core;
using __ToolName__.Core.Completion;
using __ToolName__.Core.Docs;
using __ToolName__.Core.Schema;

namespace __ToolName__.Commands;

/// <summary>
/// <c>__BinName__ gen-docs --output-dir &lt;repo&gt;</c> — a hidden maintainer command that regenerates the CLI reference
/// page, the man page and the completion scripts from the command tree. Run it from the repository root
/// (<c>dotnet run --project src/__ToolName__ -- gen-docs --output-dir .</c>) whenever a command or option changes.
/// </summary>
internal static class GenDocsCommand
{
    /// <summary>
    /// The Antora page the reference is written to, relative to the output directory.
    /// </summary>
    public const string ReferencePage = "docs/modules/ROOT/pages/cli-reference.adoc";

    /// <summary>
    /// Creates the command.
    /// </summary>
    /// <param name="globals">The global options.</param>
    /// <param name="streams">Where to write.</param>
    /// <returns>The hidden command.</returns>
    public static Command Create(GlobalOptions globals, CliStreams streams)
    {
        var outputDir = new Option<DirectoryInfo>("--output-dir")
        {
            Description = "The repository root to write into.",
            Required = true,
        };
        var command = new ToolCommand(
            "gen-docs",
            "Regenerate the CLI reference, man page and completion scripts (maintainers only).",
            new CommandSafety(false, true, false),
            null)
        {
            outputDir,
        };
        command.Hidden = true;
        command.SetAction(parseResult =>
        {
            var settings = globals.Read(parseResult);
            var root = parseResult.GetValue(outputDir)!.FullName;
            var spec = CommandTreeReader.Read(parseResult.RootCommandResult.Command);
            var schema = SchemaBuilder.Build(spec);
            var files = new SortedDictionary<string, string>(StringComparer.Ordinal)
            {
                [ReferencePage] = ReferenceGenerator.ToAsciiDoc(schema),
                [$"artifacts/man/{ToolInfo.BinName}.1"] = ReferenceGenerator.ToManPage(schema),
                [$"artifacts/completions/{ToolInfo.BinName}.bash"] = CompletionScriptGenerator.Generate(Shell.Bash, spec),
                [$"artifacts/completions/_{ToolInfo.BinName}"] = CompletionScriptGenerator.Generate(Shell.Zsh, spec),
                [$"artifacts/completions/{ToolInfo.BinName}.fish"] = CompletionScriptGenerator.Generate(Shell.Fish, spec),
                [$"artifacts/completions/{ToolInfo.BinName}.ps1"] = CompletionScriptGenerator.Generate(Shell.PowerShell, spec),
            };
            var log = new Core.Output.DiagnosticLog(streams.Error, settings);
            foreach (var (relative, contents) in files)
            {
                AtomicFile.WriteAllText(Path.Combine(root, relative), contents);
                log.Debug($"wrote {relative}");
            }

            return (int)ExitCode.Ok;
        });
        return command;
    }
}
```

### `src/__ToolName__/Commands/GreetCommand.cs`

```csharp
using System.CommandLine;
using __ToolName__.Core;
using __ToolName__.Core.Greeting;
using __ToolName__.Core.Output;
using __ToolName__.Core.Schema;
using __ToolName__.Core.Terminal;
using Spectre.Console;

namespace __ToolName__.Commands;

/// <summary>
/// <c>__BinName__ greet [NAME]</c> — the read-only sample command.
/// </summary>
internal static class GreetCommand
{
    /// <summary>
    /// Creates the command.
    /// </summary>
    /// <param name="globals">The global options.</param>
    /// <param name="streams">Where to write.</param>
    /// <returns>The command.</returns>
    public static Command Create(GlobalOptions globals, CliStreams streams)
    {
        var name = new Argument<string>("name")
        {
            Description = $"Who to greet: 1-{Greeter.MaxNameLength} printable characters.",
            Arity = ArgumentArity.ZeroOrOne,
            DefaultValueFactory = _ => Greeter.DefaultName,
        };
        name.Validators.Add(result =>
        {
            // An unknown option would otherwise be taken as NAME; report it as the usage error it is.
            if (result.Tokens is [{ Value: var token }] && token.StartsWith('-'))
            {
                result.AddError($"Unrecognized option '{token}'.");
            }
        });

        var command = new ToolCommand(
            "greet",
            "Print a greeting. Use it to check the tool is installed and wired up.",
            CommandSafety.ReadOnlyCommand,
            1,
            $"{ToolInfo.BinName} greet Ada",
            $"{ToolInfo.BinName} greet Ada --json")
        {
            name,
        };
        command.SetAction(parseResult => Run(parseResult.GetValue(name), globals.Read(parseResult), streams));
        return command;
    }

    private static int Run(string? name, GlobalSettings settings, CliStreams streams)
    {
        var log = new DiagnosticLog(streams.Error, settings);
        log.Debug($"greet: format={settings.Format}");
        var result = Greeter.Greet(name);
        switch (settings.Format)
        {
            case OutputFormat.Json:
                JsonOutput.WriteLine(streams.Out, result);
                break;
            case OutputFormat.Jsonl:
                JsonOutput.WriteLine(streams.Out, result);
                JsonOutput.WriteSummary(streams.Out, 1);
                break;
            default:
                var console = ConsoleFactory.Create(
                    streams.Out,
                    ColorPolicy.ForStdout(settings, streams.Terminal),
                    streams.Terminal.StdoutIsTerminal);
                console.MarkupLine($"[green]{Markup.Escape(result.Greeting)}[/]");
                break;
        }

        return (int)ExitCode.Ok;
    }
}
```

### `src/__ToolName__/Commands/SchemaCommand.cs`

```csharp
using System.CommandLine;
using __ToolName__.Core;
using __ToolName__.Core.Output;
using __ToolName__.Core.Schema;

namespace __ToolName__.Commands;

/// <summary>
/// <c>__BinName__ schema</c> — prints the machine-readable command tree (always JSON).
/// </summary>
internal static class SchemaCommand
{
    /// <summary>
    /// Creates the command.
    /// </summary>
    /// <param name="globals">The global options.</param>
    /// <param name="streams">Where to write.</param>
    /// <returns>The command.</returns>
    public static Command Create(GlobalOptions globals, CliStreams streams)
    {
        var command = new ToolCommand(
            "schema",
            "Print the command tree, options, exit codes and safety metadata as JSON. Use it to generate docs or tool wrappers.",
            CommandSafety.ReadOnlyCommand,
            1,
            $"{ToolInfo.BinName} schema");
        command.SetAction(parseResult =>
        {
            _ = globals.Read(parseResult);
            var root = parseResult.RootCommandResult.Command;
            JsonOutput.WriteLine(streams.Out, SchemaBuilder.Build(CommandTreeReader.Read(root)));
            return (int)ExitCode.Ok;
        });
        return command;
    }
}
```
