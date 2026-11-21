# Core test templates — `tests/__ToolName__.Core.Tests`

MSTest 4 unit tests for every `src/__ToolName__.Core` type (the scaffold reaches ~99% line and branch coverage of
Core). Fakes over mocks; no mocking library.

Conditional markers (`// >>> <condition>` … `// <<< <condition>`, `<!-- >>> <condition> -->` … in XML) and the
`__Token__` placeholders work as described in SKILL.md's "Reference files" section. A heading suffixed `(condition)`
is written only when the condition holds. Every file below was rendered and built on 2026-10-16 for `ui: none` and
`ui: tui` — see SKILL.md "Verification notes".

### `tests/__ToolName__.Core.Tests/__ToolName__.Core.Tests.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>__ToolName__.Core.Tests</RootNamespace>
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
    <ProjectReference Include="..\..\src\__ToolName__.Core\__ToolName__.Core.csproj" />
  </ItemGroup>
</Project>
```

### `tests/__ToolName__.Core.Tests/CliExceptionTests.cs`

```csharp
namespace __ToolName__.Core.Tests;

[TestClass]
public sealed class CliExceptionTests
{
    [TestMethod]
    public void Defaults_CodeToTheExitCodeName_AndSortsDetails()
    {
        var details = new Dictionary<string, string> { ["b"] = "2", ["a"] = "1" };

        var error = new CliException(ExitCode.NotFound, "No item named 'x'.", "List the items first.", details: details);

        Assert.AreEqual("NOT_FOUND", error.Code);
        Assert.AreEqual(ExitCode.NotFound, error.ExitCode);
        Assert.AreEqual("List the items first.", error.Hint);
        Assert.IsFalse(error.Retryable);
        Assert.AreEqual("a,b", string.Join(',', error.Details.Keys));
    }

    [TestMethod]
    public void Retryable_IsTrueOnlyForCode6()
    {
        Assert.IsTrue(new CliException(ExitCode.Retryable, "Timed out.", "Retry later.").Retryable);
    }

    [TestMethod]
    public void InternalError_MayOmitTheHint()
    {
        var error = new CliException(ExitCode.Internal, "Boom.");

        Assert.IsNull(error.Hint);
        Assert.IsEmpty(error.Details);
    }

    [TestMethod]
    public void MissingHint_ForCodes2To7_Throws()
    {
        Assert.ThrowsExactly<ArgumentException>(() => new CliException(ExitCode.Usage, "Bad flag."));
    }

    [TestMethod]
    public void BlankMessage_Throws()
    {
        Assert.ThrowsExactly<ArgumentException>(() => new CliException(ExitCode.Internal, " "));
    }
}
```

### `tests/__ToolName__.Core.Tests/ExitCodesTests.cs`

```csharp
namespace __ToolName__.Core.Tests;

[TestClass]
public sealed class ExitCodesTests
{
    [TestMethod]
    public void Numbers_MatchTheContract()
    {
        int[] expected = [0, 1, 2, 3, 4, 5, 6, 7, 130, 143];

        Assert.AreSequenceEqual(expected, ExitCodes.All.Select(code => (int)code).ToArray());
    }

    [TestMethod]
    public void EveryCode_HasANameAndAMeaning()
    {
        foreach (var code in ExitCodes.All)
        {
            Assert.MatchesRegex("^[A-Z_]+$", ExitCodes.NameOf(code));
            Assert.IsFalse(string.IsNullOrWhiteSpace(ExitCodes.MeaningOf(code)));
        }
    }

    [TestMethod]
    public void UndefinedCode_Throws()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => ExitCodes.NameOf((ExitCode)99));
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => ExitCodes.MeaningOf((ExitCode)99));
    }
}
```

### `tests/__ToolName__.Core.Tests/MSTestSettings.cs`

```csharp
[assembly: Parallelize(Scope = ExecutionScope.MethodLevel)]
```

### `tests/__ToolName__.Core.Tests/ToolInfoTests.cs`

```csharp
using System.Reflection;
using System.Reflection.Emit;

namespace __ToolName__.Core.Tests;

[TestClass]
public sealed class ToolInfoTests
{
    [TestMethod]
    public void Version_IsTheBuildVersion_WithoutMetadata()
    {
        Assert.IsFalse(string.IsNullOrWhiteSpace(ToolInfo.Version));
        Assert.DoesNotContain("+", ToolInfo.Version);
    }

    [TestMethod]
    public void ReadVersion_DropsBuildMetadata()
    {
        var assembly = Build("1.2.3-dev+abc123");

        Assert.AreEqual("1.2.3-dev", ToolInfo.ReadVersion(assembly));
    }

    [TestMethod]
    public void ReadVersion_WithoutAttribute_Is000()
    {
        var assembly = AssemblyBuilder.DefineDynamicAssembly(new AssemblyName("NoVersion"), AssemblyBuilderAccess.Run);

        Assert.AreEqual("0.0.0", ToolInfo.ReadVersion(assembly));
    }

    private static AssemblyBuilder Build(string version)
    {
        var attribute = new CustomAttributeBuilder(
            typeof(AssemblyInformationalVersionAttribute).GetConstructor([typeof(string)])!,
            [version]);
        return AssemblyBuilder.DefineDynamicAssembly(new AssemblyName("Versioned"), AssemblyBuilderAccess.Run, [attribute]);
    }
}
```

### `tests/__ToolName__.Core.Tests/Browse/BrowseStateTests.cs` (ui: tui)

```csharp
using __ToolName__.Core.Browse;

namespace __ToolName__.Core.Tests.Browse;

[TestClass]
public sealed class BrowseStateTests
{
    [TestMethod]
    public void StartsOnTheFirstName()
    {
        var state = new BrowseState(BrowseState.SampleNames);

        Assert.AreEqual(0, state.SelectedIndex);
        Assert.AreEqual("Hello, Ada!", state.SelectedGreeting);
    }

    [TestMethod]
    public void Moves_AreClampedToTheList()
    {
        var state = new BrowseState(["a", "b"]);

        state.MovePrevious();
        Assert.AreEqual(0, state.SelectedIndex);
        state.MoveNext();
        state.MoveNext();
        Assert.AreEqual(1, state.SelectedIndex);
        state.Select(-5);
        Assert.AreEqual(0, state.SelectedIndex);
    }

    [TestMethod]
    public void InvalidLists_Throw()
    {
        Assert.ThrowsExactly<ArgumentException>(() => new BrowseState([]));
        Assert.ThrowsExactly<ArgumentNullException>(() => new BrowseState(null!));
    }
}
```

### `tests/__ToolName__.Core.Tests/Completion/CompletionScriptGeneratorTests.cs`

```csharp
using __ToolName__.Core.Completion;
using __ToolName__.Core.Tests.Schema;

namespace __ToolName__.Core.Tests.Completion;

[TestClass]
public sealed class CompletionScriptGeneratorTests
{
    [TestMethod]
    [DataRow("bash", Shell.Bash)]
    [DataRow("zsh", Shell.Zsh)]
    [DataRow("fish", Shell.Fish)]
    [DataRow("powershell", Shell.PowerShell)]
    public void ParseShell_KnownShells(string name, Shell expected)
    {
        Assert.AreEqual(expected, CompletionScriptGenerator.ParseShell(name));
    }

    [TestMethod]
    public void ParseShell_UnknownShell_Throws()
    {
        Assert.ThrowsExactly<ArgumentException>(() => CompletionScriptGenerator.ParseShell("tcsh"));
    }

    [TestMethod]
    public void Bash_CompletesCommandsInheritedOptionsAndValues()
    {
        var script = CompletionScriptGenerator.Generate(Shell.Bash, SchemaBuilderTests.SampleTree());

        Assert.Contains("complete -F _tool tool", script);
        Assert.Contains("completion|greet|prune) cmd=", script);
        Assert.Contains("--format|-f) COMPREPLY=($(compgen -W \"text json\"", script);
        Assert.Contains("prune) COMPREPLY=($(compgen -W \"--format --quiet --yes -f -q\"", script);
        Assert.Contains("completion) COMPREPLY=($(compgen -W \"--format --quiet -f -q bash zsh\"", script);
    }

    [TestMethod]
    public void Zsh_RegistersWithCompdef()
    {
        var script = CompletionScriptGenerator.Generate(Shell.Zsh, SchemaBuilderTests.SampleTree());

        Assert.StartsWith("#compdef tool\n", script);
        Assert.Contains("compdef _tool tool", script);
        Assert.Contains("--format|-f) compadd -- text json; return ;;", script);
    }

    [TestMethod]
    public void Fish_DescribesCommandsAndOptions()
    {
        var script = CompletionScriptGenerator.Generate(Shell.Fish, SchemaBuilderTests.SampleTree());

        Assert.Contains("complete -c tool -n \"not __fish_seen_subcommand_from completion greet prune\" -a greet -d 'Greet.'", script);
        Assert.Contains("complete -c tool -l format -s f -x -a 'text json' -d 'Result format.'", script);
        Assert.Contains("complete -c tool -n \"__fish_seen_subcommand_from prune\" -l yes -d 'Confirm.'", script);
        Assert.Contains("-a 'bash zsh'", script);
    }

    [TestMethod]
    public void Fish_EscapesQuotes_AndHandlesALeafRoot()
    {
        var root = SchemaBuilderTests.SampleTree() with { Commands = [], Options = [] };
        var withQuote = root with { Commands = [root with { Name = "it", Description = "It's \\ fine." }] };

        Assert.AreEqual("# fish completion for tool (generated by `tool completion fish`; do not edit)\ncomplete -c tool -f\n", CompletionScriptGenerator.Generate(Shell.Fish, root));
        Assert.Contains("-d 'It\\'s \\\\ fine.'", CompletionScriptGenerator.Generate(Shell.Fish, withQuote));
    }

    [TestMethod]
    public void PowerShell_RegistersANativeCompleter()
    {
        var script = CompletionScriptGenerator.Generate(Shell.PowerShell, SchemaBuilderTests.SampleTree());

        Assert.Contains("Register-ArgumentCompleter -Native -CommandName 'tool'", script);
        Assert.Contains("'greet' = @('--format', '--quiet', '-f', '-q')", script);
        Assert.Contains("'-f' = @('text', 'json')", script);
    }

    [TestMethod]
    public void Generate_RejectsUnknownShellsAndNull()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => CompletionScriptGenerator.Generate((Shell)42, SchemaBuilderTests.SampleTree()));
        Assert.ThrowsExactly<ArgumentNullException>(() => CompletionScriptGenerator.Generate(Shell.Bash, null!));
    }

    [TestMethod]
    public void BinaryNamesWithDashes_BecomeValidFunctionNames()
    {
        var root = SchemaBuilderTests.SampleTree() with { Name = "my-tool" };

        Assert.Contains("complete -F _my_tool my-tool", CompletionScriptGenerator.Generate(Shell.Bash, root));
    }
}
```

### `tests/__ToolName__.Core.Tests/Docs/AtomicFileTests.cs`

```csharp
using __ToolName__.Core.Docs;

namespace __ToolName__.Core.Tests.Docs;

[TestClass]
public sealed class AtomicFileTests
{
    [TestMethod]
    public void WriteAllText_CreatesDirectories_AndReplacesContent()
    {
        var root = Path.Combine(Path.GetTempPath(), "atomic-" + Guid.NewGuid().ToString("N"));
        var path = Path.Combine(root, "a", "b.txt");
        try
        {
            AtomicFile.WriteAllText(path, "one");
            AtomicFile.WriteAllText(path, "two");

            Assert.AreEqual("two", File.ReadAllText(path));
            Assert.ContainsSingle(Directory.GetFiles(Path.GetDirectoryName(path)!));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [TestMethod]
    public void InvalidArguments_Throw()
    {
        Assert.ThrowsExactly<ArgumentException>(() => AtomicFile.WriteAllText(" ", "x"));
        Assert.ThrowsExactly<ArgumentNullException>(() => AtomicFile.WriteAllText("x.txt", null!));
    }
}
```

### `tests/__ToolName__.Core.Tests/Docs/ReferenceGeneratorTests.cs`

```csharp
using __ToolName__.Core.Docs;
using __ToolName__.Core.Schema;
using __ToolName__.Core.Tests.Schema;

namespace __ToolName__.Core.Tests.Docs;

[TestClass]
public sealed class ReferenceGeneratorTests
{
    [TestMethod]
    public void AsciiDoc_DocumentsCommandsOptionsSafetyAndExitCodes()
    {
        var page = ReferenceGenerator.ToAsciiDoc(SchemaBuilder.Build(SchemaBuilderTests.SampleTree()));

        Assert.StartsWith($"= `{ToolInfo.BinName}` command reference\n", page);
        Assert.Contains("== `tool greet`", page);
        Assert.Contains("Safety: read-only, idempotent. JSON output `schemaVersion`: 1.", page);
        Assert.Contains("Safety: destructive (needs `--yes`; supports `--dry-run`), idempotent.", page);
        Assert.Contains("|`--format`, `-f` |string |`text` |Result format. One of: text, json.", page);
        Assert.Contains("|`shell` |1..1 |- |Shell. One of: bash, zsh.", page);
        Assert.Contains("|130 |`INTERRUPTED` |", page);
    }

    [TestMethod]
    public void AsciiDoc_DescribesMutatingCommands_AndEscapesCells()
    {
        var root = SchemaBuilderTests.SampleTree();
        var write = root with { Name = "write", Path = "tool write", Description = "a|b", Commands = [], Arguments = [], Options = [], OutputSchemaVersion = null, Safety = new CommandSafety(false, false, false) };

        var page = ReferenceGenerator.ToAsciiDoc(SchemaBuilder.Build(root with { Commands = [write] }));

        Assert.Contains("a|b\n\nSafety: mutating.\n", page);
    }

    [TestMethod]
    public void ManPage_IsRoff_WithEscapedDashes()
    {
        var man = ReferenceGenerator.ToManPage(SchemaBuilder.Build(SchemaBuilderTests.SampleTree()));

        Assert.StartsWith($".TH {ToolInfo.BinName.ToUpperInvariant().Replace("-", "\\-", StringComparison.Ordinal)} 1 ", man);
        Assert.Contains(".B completion [shell]\n", man);
        Assert.Contains(".B \\-\\-format, \\-f\n", man);
        Assert.Contains(".SH EXIT STATUS\n", man);
    }

    [TestMethod]
    public void NullSchema_Throws()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => ReferenceGenerator.ToAsciiDoc(null!));
        Assert.ThrowsExactly<ArgumentNullException>(() => ReferenceGenerator.ToManPage(null!));
    }
}
```

### `tests/__ToolName__.Core.Tests/Greeting/GreeterTests.cs`

```csharp
using __ToolName__.Core.Greeting;

namespace __ToolName__.Core.Tests.Greeting;

[TestClass]
public sealed class GreeterTests
{
    [TestMethod]
    public void Greet_BuildsTheResult()
    {
        Assert.AreEqual(new GreetingResult("Hello, Ada!", "Ada", 1), Greeter.Greet("Ada"));
    }

    [TestMethod]
    public void Greet_WithoutName_GreetsTheWorld()
    {
        Assert.AreEqual("Hello, world!", Greeter.Greet(null).Greeting);
    }

    [TestMethod]
    [DataRow("", "empty")]
    [DataRow("   ", "empty")]
    [DataRow("a\u0007b", "control-characters")]
    public void Greet_InvalidName_IsAValidationError(string name, string reason)
    {
        var error = Assert.ThrowsExactly<CliException>(() => Greeter.Greet(name));

        Assert.AreEqual(ExitCode.Validation, error.ExitCode);
        Assert.AreEqual("INVALID_NAME", error.Code);
        Assert.AreEqual(reason, error.Details["reason"]);
    }

    [TestMethod]
    public void Greet_TooLongName_IsAValidationError()
    {
        var error = Assert.ThrowsExactly<CliException>(() => Greeter.Greet(new string('x', Greeter.MaxNameLength + 1)));

        Assert.AreEqual("too-long", error.Details["reason"]);
        Assert.AreEqual("Hello, " + new string('x', Greeter.MaxNameLength) + "!", Greeter.Greet(new string('x', Greeter.MaxNameLength)).Greeting);
    }
}
```

### `tests/__ToolName__.Core.Tests/Output/DiagnosticLogTests.cs`

```csharp
using __ToolName__.Core.Output;

namespace __ToolName__.Core.Tests.Output;

[TestClass]
public sealed class DiagnosticLogTests
{
    [TestMethod]
    public void Verbose_WritesTextDiagnostics()
    {
        using var stderr = new StringWriter();

        new DiagnosticLog(stderr, GlobalSettings.Default with { Verbosity = 1 }).Debug("hello");

        Assert.AreEqual("debug: hello\n", stderr.ToString());
    }

    [TestMethod]
    public void JsonLogFormat_WritesJsonLines()
    {
        using var stderr = new StringWriter();

        new DiagnosticLog(stderr, GlobalSettings.Default with { Verbosity = 2, LogFormat = LogFormat.Json }).Debug("hi", 2);

        Assert.AreEqual("{\"level\":\"debug\",\"message\":\"hi\"}\n", stderr.ToString());
    }

    [TestMethod]
    [DataRow(0, false)]
    [DataRow(3, true)]
    public void BelowLevelOrQuiet_WritesNothing(int verbosity, bool quiet)
    {
        using var stderr = new StringWriter();

        new DiagnosticLog(stderr, GlobalSettings.Default with { Verbosity = verbosity, Quiet = quiet }).Debug("hidden");

        Assert.AreEqual(string.Empty, stderr.ToString());
    }

    [TestMethod]
    public void NullArguments_Throw()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => new DiagnosticLog(null!, GlobalSettings.Default));
        Assert.ThrowsExactly<ArgumentNullException>(() => new DiagnosticLog(TextWriter.Null, null!));
    }
}
```

### `tests/__ToolName__.Core.Tests/Output/ErrorReporterTests.cs`

```csharp
using __ToolName__.Core.Output;

namespace __ToolName__.Core.Tests.Output;

[TestClass]
public sealed class ErrorReporterTests
{
    private static readonly CliException NotFound =
        new(ExitCode.NotFound, "No item named 'x'.", "Run `list` to see the available names.");

    [TestMethod]
    public void Json_WritesTheEnvelope_AndReturnsTheExitCode()
    {
        using var stderr = new StringWriter();

        var code = ErrorReporter.Report(stderr, NotFound, OutputFormat.Json, color: false);

        Assert.AreEqual(4, code);
        Assert.AreEqual(
            """{"error":{"code":"NOT_FOUND","details":{},"exitCode":4,"hint":"Run `list` to see the available names.","message":"No item named 'x'.","retryable":false},"schemaVersion":1}""" + "\n",
            stderr.ToString());
    }

    [TestMethod]
    public void Text_WritesMessageAndHintLines_WithoutColour()
    {
        using var stderr = new StringWriter();

        ErrorReporter.Report(stderr, NotFound, OutputFormat.Text, color: false);

        Assert.AreEqual(
            $"{ToolInfo.BinName}: No item named 'x'.\nhint: Run `list` to see the available names.\n",
            stderr.ToString());
    }

    [TestMethod]
    public void Text_WithColour_StylesOnlyThePrefix()
    {
        using var stderr = new StringWriter();

        ErrorReporter.Report(stderr, new CliException(ExitCode.Internal, "Boom."), OutputFormat.Text, color: true);

        Assert.AreEqual($"\u001b[31m{ToolInfo.BinName}:\u001b[0m Boom.\n", stderr.ToString());
    }

    [TestMethod]
    public void NullArguments_Throw()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => ErrorReporter.Report(null!, NotFound, OutputFormat.Text, false));
        Assert.ThrowsExactly<ArgumentNullException>(() => ErrorReporter.Report(TextWriter.Null, null!, OutputFormat.Text, false));
        Assert.ThrowsExactly<ArgumentNullException>(() => ErrorReporter.ToEnvelope(null!));
    }
}
```

### `tests/__ToolName__.Core.Tests/Output/GlobalSettingsTests.cs`

```csharp
using __ToolName__.Core.Output;

namespace __ToolName__.Core.Tests.Output;

[TestClass]
public sealed class GlobalSettingsTests
{
    [TestMethod]
    [DataRow("text", OutputFormat.Text)]
    [DataRow("json", OutputFormat.Json)]
    [DataRow("jsonl", OutputFormat.Jsonl)]
    public void ParseFormat_KnownValues(string value, OutputFormat expected)
    {
        Assert.AreEqual(expected, GlobalSettings.ParseFormat(value));
    }

    [TestMethod]
    [DataRow("text", LogFormat.Text)]
    [DataRow("json", LogFormat.Json)]
    public void ParseLogFormat_KnownValues(string value, LogFormat expected)
    {
        Assert.AreEqual(expected, GlobalSettings.ParseLogFormat(value));
    }

    [TestMethod]
    public void UnknownValues_Throw()
    {
        Assert.ThrowsExactly<ArgumentException>(() => GlobalSettings.ParseFormat("yaml"));
        Assert.ThrowsExactly<ArgumentException>(() => GlobalSettings.ParseLogFormat("xml"));
    }

    [TestMethod]
    public void MachineMode_IsTrueForJsonFormats()
    {
        Assert.IsFalse(GlobalSettings.Default.MachineMode);
        Assert.IsTrue((GlobalSettings.Default with { Format = OutputFormat.Jsonl }).MachineMode);
    }
}
```

### `tests/__ToolName__.Core.Tests/Output/JsonOutputTests.cs`

```csharp
using __ToolName__.Core.Greeting;
using __ToolName__.Core.Output;

namespace __ToolName__.Core.Tests.Output;

[TestClass]
public sealed class JsonOutputTests
{
    [TestMethod]
    public void Serialize_IsCompact_SortedAndUnescaped()
    {
        var json = JsonOutput.Serialize(new GreetingResult("Hello, O'Brien `x`!", "O'Brien `x`", 1));

        Assert.AreEqual("""{"greeting":"Hello, O'Brien `x`!","name":"O'Brien `x`","schemaVersion":1}""", json);
    }

    [TestMethod]
    public void WriteSummary_WritesTheJsonlSummaryLine()
    {
        using var writer = new StringWriter();

        JsonOutput.WriteSummary(writer, 3);

        Assert.AreEqual("{\"nextCursor\":null,\"schemaVersion\":1,\"total\":3,\"type\":\"summary\"}\n", writer.ToString());
    }

    [TestMethod]
    public void WriteLine_WithoutWriter_Throws()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => JsonOutput.WriteLine(null!, new LogLine("debug", "x")));
    }
}
```

### `tests/__ToolName__.Core.Tests/Schema/SchemaBuilderTests.cs`

```csharp
using __ToolName__.Core.Output;
using __ToolName__.Core.Schema;

namespace __ToolName__.Core.Tests.Schema;

[TestClass]
public sealed class SchemaBuilderTests
{
    [TestMethod]
    public void Build_SortsCommandsAndOptions_AndListsEveryExitCode()
    {
        var document = SchemaBuilder.Build(SampleTree());

        Assert.AreEqual("completion,greet,prune", string.Join(',', document.Command.Commands.Select(c => c.Name)));
        Assert.AreEqual("--format,--quiet", string.Join(',', document.Command.Options.Select(o => o.Name)));
        Assert.HasCount(ExitCodes.All.Count, document.ExitCodes);
        Assert.AreEqual(ToolInfo.BinName, document.Name);
        Assert.AreEqual(ToolInfo.Version, document.Version);
    }

    [TestMethod]
    public void Build_SerializesDeterministically()
    {
        var first = JsonOutput.Serialize(SchemaBuilder.Build(SampleTree()));
        var second = JsonOutput.Serialize(SchemaBuilder.Build(SampleTree()));

        Assert.AreEqual(first, second);
        Assert.StartsWith("{\"command\":{\"arguments\":[],\"commands\":[{\"arguments\":[{\"arity\":\"1..1\"", first);
        Assert.Contains("\"safety\":{\"destructive\":true,\"idempotent\":true,\"readOnly\":false}", first);
    }

    [TestMethod]
    public void NullArguments_Throw()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => SchemaBuilder.Build(null!));
        Assert.ThrowsExactly<ArgumentNullException>(() => SchemaBuilder.Normalize(null!));
    }

    internal static CommandSpec SampleTree()
    {
        var format = new OptionSpec(["-f"], "text", "Result format.", "--format", true, false, false, "string", ["text", "json"]);
        var quiet = new OptionSpec(["-q"], "false", "Quiet.", "--quiet", true, false, false, "boolean", []);
        var yes = new OptionSpec([], "false", "Confirm.", "--yes", false, false, false, "boolean", []);
        var name = new ArgumentSpec("0..1", "world", "Who.", "name", "string", []);
        var shell = new ArgumentSpec("1..1", null, "Shell.", "shell", "string", ["bash", "zsh"]);
        var greet = new CommandSpec([name], [], "Greet.", "greet", [], 1, "tool greet", CommandSafety.ReadOnlyCommand);
        var prune = new CommandSpec([], [], "Prune.", "prune", [yes], 1, "tool prune", new CommandSafety(true, true, false));
        var completion = new CommandSpec([shell], [], "Completion.", "completion", [], null, "tool completion", CommandSafety.ReadOnlyCommand);
        return new CommandSpec([], [prune, greet, completion], "Root.", "tool", [quiet, format], null, "tool", CommandSafety.ReadOnlyCommand);
    }
}
```

### `tests/__ToolName__.Core.Tests/Terminal/ColorPolicyTests.cs`

```csharp
using __ToolName__.Core.Output;
using __ToolName__.Core.Terminal;

namespace __ToolName__.Core.Tests.Terminal;

[TestClass]
public sealed class ColorPolicyTests
{
    [TestMethod]
    public void Terminal_WithNothingDisabling_IsColoured()
    {
        Assert.IsTrue(ColorPolicy.ForStdout(GlobalSettings.Default, Env(true)));
        Assert.IsTrue(ColorPolicy.ForStderr(GlobalSettings.Default, Env(true)));
    }

    [TestMethod]
    public void Redirected_IsPlain()
    {
        Assert.IsFalse(ColorPolicy.ForStdout(GlobalSettings.Default, Env(false)));
        Assert.IsFalse(ColorPolicy.ForStderr(GlobalSettings.Default, Env(false)));
    }

    [TestMethod]
    public void NoColor_Wins_EvenOverForceColor()
    {
        Assert.IsFalse(ColorPolicy.ForStdout(GlobalSettings.Default, Env(true, ("NO_COLOR", "1"), ("FORCE_COLOR", "1"))));
        Assert.IsFalse(ColorPolicy.ForStdout(GlobalSettings.Default with { NoColor = true }, Env(true, ("FORCE_COLOR", "1"))));
    }

    [TestMethod]
    public void EmptyNoColor_IsIgnored()
    {
        Assert.IsTrue(ColorPolicy.ForStdout(GlobalSettings.Default, Env(true, ("NO_COLOR", string.Empty))));
    }

    [TestMethod]
    public void DumbTerminal_IsPlain()
    {
        Assert.IsFalse(ColorPolicy.ForStdout(GlobalSettings.Default, Env(true, ("TERM", "dumb"))));
    }

    [TestMethod]
    [DataRow("FORCE_COLOR")]
    [DataRow("CLICOLOR_FORCE")]
    public void ForceColor_ColoursRedirectedOutput(string variable)
    {
        Assert.IsTrue(ColorPolicy.ForStdout(GlobalSettings.Default, Env(false, (variable, "1"))));
        Assert.IsFalse(ColorPolicy.ForStdout(GlobalSettings.Default, Env(false, (variable, "0"))));
    }

    [TestMethod]
    public void MachineMode_QuietAndJsonLogs_ArePlain()
    {
        var json = GlobalSettings.Default with { Format = OutputFormat.Json };

        Assert.IsFalse(ColorPolicy.ForStdout(json, Env(true)));
        Assert.IsFalse(ColorPolicy.ForStderr(json, Env(true)));
        Assert.IsFalse(ColorPolicy.ForStderr(GlobalSettings.Default with { Quiet = true }, Env(true)));
        Assert.IsFalse(ColorPolicy.ForStderr(GlobalSettings.Default with { LogFormat = LogFormat.Json }, Env(true)));
    }

    [TestMethod]
    public void NullArguments_Throw()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => ColorPolicy.ForStdout(null!, Env(true)));
        Assert.ThrowsExactly<ArgumentNullException>(() => ColorPolicy.ForStdout(GlobalSettings.Default, null!));
        Assert.ThrowsExactly<ArgumentNullException>(() => ColorPolicy.ForStderr(null!, Env(true)));
        Assert.ThrowsExactly<ArgumentNullException>(() => ColorPolicy.ForStderr(GlobalSettings.Default, null!));
    }

    private static TerminalEnvironment Env(bool terminal, params (string Name, string Value)[] variables) =>
        new(terminal, terminal, terminal, variables.ToDictionary(v => v.Name, v => v.Value));
}
```

### `tests/__ToolName__.Core.Tests/Terminal/PromptPolicyTests.cs`

```csharp
using __ToolName__.Core.Output;
using __ToolName__.Core.Terminal;

namespace __ToolName__.Core.Tests.Terminal;

[TestClass]
public sealed class PromptPolicyTests
{
    private static readonly TerminalEnvironment Interactive = new(true, true, true, new Dictionary<string, string>());

    [TestMethod]
    public void InteractiveTerminal_MayPrompt()
    {
        Assert.IsTrue(PromptPolicy.CanPrompt(GlobalSettings.Default, Interactive));
    }

    [TestMethod]
    public void RedirectedStdinOrStderr_MayNotPrompt()
    {
        Assert.IsFalse(PromptPolicy.CanPrompt(GlobalSettings.Default, Interactive with { StdinIsTerminal = false }));
        Assert.IsFalse(PromptPolicy.CanPrompt(GlobalSettings.Default, Interactive with { StderrIsTerminal = false }));
    }

    [TestMethod]
    public void NoInputFlagOrMachineMode_MayNotPrompt()
    {
        Assert.IsFalse(PromptPolicy.CanPrompt(GlobalSettings.Default with { NoInput = true }, Interactive));
        Assert.IsFalse(PromptPolicy.CanPrompt(GlobalSettings.Default with { Format = OutputFormat.Json }, Interactive));
    }

    [TestMethod]
    [DataRow("CI", "true")]
    [DataRow("__BinEnvPrefix___NO_INPUT", "1")]
    public void Environment_MayDisablePrompts(string name, string value)
    {
        var environment = Interactive with { Variables = new Dictionary<string, string> { [name] = value } };

        Assert.IsFalse(PromptPolicy.CanPrompt(GlobalSettings.Default, environment));
    }

    [TestMethod]
    public void ConfirmationRequired_CarriesTheConfirmCommand()
    {
        var error = PromptPolicy.ConfirmationRequired("delete 3 items", "__BinName__ prune --yes");

        Assert.AreEqual(ExitCode.ConfirmationRequired, error.ExitCode);
        Assert.AreEqual("__BinName__ prune --yes", error.Details["confirmCommand"]);
        Assert.Contains("--yes", error.Hint!);
    }

    [TestMethod]
    public void NullArguments_Throw()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => PromptPolicy.CanPrompt(null!, Interactive));
        Assert.ThrowsExactly<ArgumentNullException>(() => PromptPolicy.CanPrompt(GlobalSettings.Default, null!));
    }
}
```

### `tests/__ToolName__.Core.Tests/Terminal/ShutdownCoordinatorTests.cs`

```csharp
using System.Runtime.InteropServices;
using __ToolName__.Core.Terminal;

namespace __ToolName__.Core.Tests.Terminal;

[TestClass]
public sealed class ShutdownCoordinatorTests
{
    [TestMethod]
    public void NoSignal_LeavesTheTokenAlone()
    {
        using var coordinator = new ShutdownCoordinator(_ => Assert.Fail("must not exit"));

        Assert.IsFalse(coordinator.Token.IsCancellationRequested);
        Assert.IsNull(coordinator.SignalExitCode);
    }

    [TestMethod]
    [DataRow(PosixSignal.SIGINT, ExitCode.Interrupted)]
    [DataRow(PosixSignal.SIGTERM, ExitCode.Terminated)]
    public void FirstSignal_CancelsAndRecordsTheExitCode(PosixSignal signal, ExitCode expected)
    {
        using var coordinator = new ShutdownCoordinator(_ => Assert.Fail("must not exit on the first signal"));

        coordinator.Handle(signal);

        Assert.IsTrue(coordinator.Token.IsCancellationRequested);
        Assert.AreEqual(expected, coordinator.SignalExitCode);
    }

    [TestMethod]
    public void SecondSignal_ExitsImmediately()
    {
        var exits = new List<int>();
        using var coordinator = new ShutdownCoordinator(exits.Add);

        coordinator.Handle(PosixSignal.SIGINT);
        coordinator.Handle(PosixSignal.SIGINT);

        Assert.AreEqual(130, Assert.ContainsSingle(exits));
    }

    [TestMethod]
    public void Install_RegistersAndDisposesCleanly()
    {
        var coordinator = ShutdownCoordinator.Install();

        Assert.IsNull(coordinator.SignalExitCode);
        coordinator.Dispose();
    }
}
```

### `tests/__ToolName__.Core.Tests/Terminal/TerminalEnvironmentTests.cs`

```csharp
using __ToolName__.Core.Terminal;

namespace __ToolName__.Core.Tests.Terminal;

[TestClass]
public sealed class TerminalEnvironmentTests
{
    [TestMethod]
    public void FromSystem_UnderTheTestRunner_SeesRedirectedOutput()
    {
        var environment = TerminalEnvironment.FromSystem();

        Assert.IsFalse(environment.StdoutIsTerminal);
        Assert.IsTrue(environment.Variables.Keys.All(TerminalEnvironment.RelevantVariables.Contains));
    }

    [TestMethod]
    [DataRow("1", true)]
    [DataRow("yes", true)]
    [DataRow("", false)]
    [DataRow("0", false)]
    [DataRow("FALSE", false)]
    public void IsTruthy(string value, bool expected)
    {
        var environment = new TerminalEnvironment(false, false, false, new Dictionary<string, string> { ["X"] = value });

        Assert.AreEqual(expected, environment.IsTruthy("X"));
        Assert.IsFalse(environment.IsTruthy("UNSET"));
        Assert.IsNull(environment.Get("UNSET"));
    }
}
```
