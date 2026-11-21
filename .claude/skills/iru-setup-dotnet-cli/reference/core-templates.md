# Core templates — `src/__ToolName__.Core`

The UI-free, framework-free library that holds every behaviour of the CLI contract — exit codes, the error envelope,
deterministic source-generated JSON, colour/prompt policy, signal handling, the schema model, completion-script and
reference/man-page generation, the `greet` sample logic and (ui: tui) the browse state. The 80% coverage bar is
measured on this project.

Conditional markers (`// >>> <condition>` … `// <<< <condition>`, `<!-- >>> <condition> -->` … in XML) and the
`__Token__` placeholders work as described in SKILL.md's "Reference files" section. A heading suffixed `(condition)`
is written only when the condition holds. Every file below was rendered and built on 2026-10-16 for `ui: none` and
`ui: tui` — see SKILL.md "Verification notes".

### `src/__ToolName__.Core/__ToolName__.Core.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <!-- The tool's logic: exit codes, output/envelope, terminal policy, schema and completion generation.
       No System.CommandLine/Spectre dependency, so every line is unit-testable and the 80% coverage bar is measured here. -->
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>__ToolName__.Core</RootNamespace>
    <AssemblyName>__ToolName__.Core</AssemblyName>
    <IsAotCompatible>true</IsAotCompatible>
    <IsPackable>false</IsPackable>
  </PropertyGroup>

  <ItemGroup>
    <InternalsVisibleTo Include="__ToolName__.Core.Tests" />
  </ItemGroup>
</Project>
```

### `src/__ToolName__.Core/CliException.cs`

```csharp
namespace __ToolName__.Core;

/// <summary>
/// An expected failure that maps to one <see cref="ExitCode"/> and one error envelope. Commands throw it; the entry
/// point reports it on stderr (text or JSON) and exits with <see cref="ExitCode"/>.
/// </summary>
public sealed class CliException : Exception
{
    /// <summary>
    /// Initializes a new instance of the <see cref="CliException"/> class.
    /// </summary>
    /// <param name="exitCode">The process exit code this failure maps to.</param>
    /// <param name="message">What went wrong, in one sentence.</param>
    /// <param name="hint">How to fix it; required (non-blank) for exit codes 2–7.</param>
    /// <param name="code">A stable SCREAMING_SNAKE identifier; defaults to the exit code's name.</param>
    /// <param name="details">Machine-readable details; never secrets.</param>
    /// <exception cref="ArgumentException"><paramref name="message"/> is blank, or <paramref name="hint"/> is blank for exit codes 2–7.</exception>
    public CliException(
        ExitCode exitCode,
        string message,
        string? hint = null,
        string? code = null,
        IReadOnlyDictionary<string, string>? details = null)
        : base(message)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        if (exitCode is >= ExitCode.Usage and <= ExitCode.Auth && string.IsNullOrWhiteSpace(hint))
        {
            throw new ArgumentException("A hint is required for exit codes 2-7.", nameof(hint));
        }

        ExitCode = exitCode;
        Hint = hint;
        Code = code ?? ExitCodes.NameOf(exitCode);
        Details = new SortedDictionary<string, string>(
            details?.ToDictionary(pair => pair.Key, pair => pair.Value) ?? [],
            StringComparer.Ordinal);
    }

    /// <summary>
    /// Gets the process exit code this failure maps to.
    /// </summary>
    public ExitCode ExitCode { get; }

    /// <summary>
    /// Gets how to fix the failure, or <see langword="null"/> when there is nothing to suggest.
    /// </summary>
    public string? Hint { get; }

    /// <summary>
    /// Gets the stable SCREAMING_SNAKE identifier of this failure.
    /// </summary>
    public string Code { get; }

    /// <summary>
    /// Gets the machine-readable details, sorted by key.
    /// </summary>
    public IReadOnlyDictionary<string, string> Details { get; }

    /// <summary>
    /// Gets a value indicating whether retrying with the same arguments may succeed (only for <see cref="ExitCode.Retryable"/>).
    /// </summary>
    public bool Retryable => ExitCode == ExitCode.Retryable;
}
```

### `src/__ToolName__.Core/ExitCode.cs`

```csharp
namespace __ToolName__.Core;

/// <summary>
/// The process exit codes of the tool — one per caller reaction (CLI contract §2). Scripts and agents depend on these
/// numbers: never renumber them; map a new failure kind onto an existing reaction instead.
/// </summary>
public enum ExitCode
{
    /// <summary>Success, including an idempotent no-op and a <c>--dry-run</c> plan.</summary>
    Ok = 0,

    /// <summary>Unexpected failure (a bug). Report it; don't retry blindly.</summary>
    Internal = 1,

    /// <summary>Bad flag, argument or unknown command. Fix the command line using the message.</summary>
    Usage = 2,

    /// <summary>Well-formed input that was rejected. Read the problems, fix the data.</summary>
    Validation = 3,

    /// <summary>The named resource doesn't exist.</summary>
    NotFound = 4,

    /// <summary>A destructive action needs confirmation and none was given.</summary>
    ConfirmationRequired = 5,

    /// <summary>Transient failure (timeout, rate limit, 5xx). Wait, then retry with the same arguments.</summary>
    Retryable = 6,

    /// <summary>Missing or invalid credentials.</summary>
    Auth = 7,

    /// <summary>Interrupted by SIGINT (Ctrl+C): 128 + 2.</summary>
    Interrupted = 130,

    /// <summary>Terminated by SIGTERM: 128 + 15.</summary>
    Terminated = 143,
}
```

### `src/__ToolName__.Core/ExitCodes.cs`

```csharp
namespace __ToolName__.Core;

/// <summary>
/// Stable names and meanings of <see cref="ExitCode"/> values, used by the error envelope and the <c>schema</c> output.
/// </summary>
public static class ExitCodes
{
    /// <summary>
    /// Gets every exit code the tool can emit, in ascending numeric order.
    /// </summary>
    public static IReadOnlyList<ExitCode> All { get; } =
    [
        ExitCode.Ok,
        ExitCode.Internal,
        ExitCode.Usage,
        ExitCode.Validation,
        ExitCode.NotFound,
        ExitCode.ConfirmationRequired,
        ExitCode.Retryable,
        ExitCode.Auth,
        ExitCode.Interrupted,
        ExitCode.Terminated,
    ];

    /// <summary>
    /// Gets the stable SCREAMING_SNAKE identifier of <paramref name="code"/> (the envelope's <c>code</c> default).
    /// </summary>
    /// <param name="code">The exit code.</param>
    /// <returns>The identifier, e.g. <c>NOT_FOUND</c>.</returns>
    /// <exception cref="ArgumentOutOfRangeException">The value is not a defined exit code.</exception>
    public static string NameOf(ExitCode code) => code switch
    {
        ExitCode.Ok => "OK",
        ExitCode.Internal => "INTERNAL",
        ExitCode.Usage => "USAGE",
        ExitCode.Validation => "VALIDATION",
        ExitCode.NotFound => "NOT_FOUND",
        ExitCode.ConfirmationRequired => "CONFIRMATION_REQUIRED",
        ExitCode.Retryable => "RETRYABLE",
        ExitCode.Auth => "AUTH",
        ExitCode.Interrupted => "INTERRUPTED",
        ExitCode.Terminated => "TERMINATED",
        _ => throw new ArgumentOutOfRangeException(nameof(code), code, "Unknown exit code."),
    };

    /// <summary>
    /// Gets the caller reaction <paramref name="code"/> asks for, as documented in the <c>schema</c> output.
    /// </summary>
    /// <param name="code">The exit code.</param>
    /// <returns>A one-sentence meaning.</returns>
    /// <exception cref="ArgumentOutOfRangeException">The value is not a defined exit code.</exception>
    public static string MeaningOf(ExitCode code) => code switch
    {
        ExitCode.Ok => "Success, including an idempotent no-op and a dry-run plan.",
        ExitCode.Internal => "Unexpected failure (a bug); report it and don't retry blindly.",
        ExitCode.Usage => "Bad flag, argument or unknown command; fix the command line.",
        ExitCode.Validation => "Well-formed input that was rejected; fix the data.",
        ExitCode.NotFound => "The named resource doesn't exist; look it up first.",
        ExitCode.ConfirmationRequired => "A destructive action needs confirmation; re-run with --yes.",
        ExitCode.Retryable => "Transient failure; wait, then retry with the same arguments.",
        ExitCode.Auth => "Missing or invalid credentials; log in or set the token variable.",
        ExitCode.Interrupted => "Interrupted by SIGINT (Ctrl+C).",
        ExitCode.Terminated => "Terminated by SIGTERM.",
        _ => throw new ArgumentOutOfRangeException(nameof(code), code, "Unknown exit code."),
    };
}
```

### `src/__ToolName__.Core/ToolInfo.cs`

```csharp
using System.Reflection;

namespace __ToolName__.Core;

/// <summary>
/// Identity of the tool: command name and version.
/// </summary>
public static class ToolInfo
{
    /// <summary>
    /// The command name users type.
    /// </summary>
    public const string BinName = "__BinName__";

    /// <summary>
    /// The prefix of every environment variable the tool reads (e.g. <c>__BinEnvPrefix___NO_INPUT</c>).
    /// </summary>
    public const string EnvPrefix = "__BinEnvPrefix___";

    /// <summary>
    /// Gets the tool version (<c>Version</c> in <c>Directory.Build.props</c>, without build metadata).
    /// </summary>
    public static string Version { get; } = ReadVersion(typeof(ToolInfo).Assembly);

    /// <summary>
    /// Reads the informational version of <paramref name="assembly"/>, dropping any <c>+metadata</c> suffix.
    /// </summary>
    /// <param name="assembly">The assembly to inspect.</param>
    /// <returns>The version, or <c>0.0.0</c> when the attribute is missing.</returns>
    internal static string ReadVersion(Assembly assembly)
    {
        var version = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (string.IsNullOrEmpty(version))
        {
            return "0.0.0";
        }

        var plus = version.IndexOf('+', StringComparison.Ordinal);
        return plus < 0 ? version : version[..plus];
    }
}
```

### `src/__ToolName__.Core/Browse/BrowseState.cs` (ui: tui)

```csharp
using __ToolName__.Core.Greeting;

namespace __ToolName__.Core.Browse;

/// <summary>
/// The state of the <c>__BinName__ browse</c> terminal UI — a list of names and the selected one — kept free of any
/// UI type so every transition is unit-tested. The view only renders it and forwards keys.
/// </summary>
public sealed class BrowseState
{
    /// <summary>
    /// Initializes a new instance of the <see cref="BrowseState"/> class.
    /// </summary>
    /// <param name="names">The names to browse; at least one.</param>
    /// <exception cref="ArgumentException"><paramref name="names"/> is empty.</exception>
    public BrowseState(IReadOnlyList<string> names)
    {
        ArgumentNullException.ThrowIfNull(names);
        if (names.Count == 0)
        {
            throw new ArgumentException("At least one name is required.", nameof(names));
        }

        Names = names;
    }

    /// <summary>
    /// Gets the sample names the demo browses.
    /// </summary>
    public static IReadOnlyList<string> SampleNames { get; } = ["Ada", "Grace", "Linus", "Margaret"];

    /// <summary>
    /// Gets the names, in display order.
    /// </summary>
    public IReadOnlyList<string> Names { get; }

    /// <summary>
    /// Gets the index of the selected name.
    /// </summary>
    public int SelectedIndex { get; private set; }

    /// <summary>
    /// Gets the greeting for the selected name.
    /// </summary>
    public string SelectedGreeting => Greeter.Greet(Names[SelectedIndex]).Greeting;

    /// <summary>
    /// Selects <paramref name="index"/>, clamped to the list bounds.
    /// </summary>
    /// <param name="index">The requested index.</param>
    public void Select(int index) => SelectedIndex = Math.Clamp(index, 0, Names.Count - 1);

    /// <summary>
    /// Moves the selection down one row, stopping at the last name.
    /// </summary>
    public void MoveNext() => Select(SelectedIndex + 1);

    /// <summary>
    /// Moves the selection up one row, stopping at the first name.
    /// </summary>
    public void MovePrevious() => Select(SelectedIndex - 1);
}
```

### `src/__ToolName__.Core/Completion/CompletionScriptGenerator.cs`

```csharp
using System.Text;
using __ToolName__.Core.Schema;

namespace __ToolName__.Core.Completion;

/// <summary>
/// Generates static shell completion scripts from the command tree — never hand-written, so they cannot drift from
/// the parser. Completes subcommand names, option names and aliases (including recursive options inherited from
/// ancestors), restricted argument values, and the values of options that take a fixed set of values.
/// </summary>
public static class CompletionScriptGenerator
{
    /// <summary>
    /// The shell names accepted by <c>__BinName__ completion</c>.
    /// </summary>
    public static readonly IReadOnlyList<string> ShellNames = ["bash", "fish", "powershell", "zsh"];

    /// <summary>
    /// Parses a shell name.
    /// </summary>
    /// <param name="name">One of <see cref="ShellNames"/>.</param>
    /// <returns>The shell.</returns>
    /// <exception cref="ArgumentException">The shell is not supported.</exception>
    public static Shell ParseShell(string name) => name switch
    {
        "bash" => Shell.Bash,
        "zsh" => Shell.Zsh,
        "fish" => Shell.Fish,
        "powershell" => Shell.PowerShell,
        _ => throw new ArgumentException($"Unsupported shell '{name}'.", nameof(name)),
    };

    /// <summary>
    /// Generates the completion script for <paramref name="shell"/>.
    /// </summary>
    /// <param name="shell">The target shell.</param>
    /// <param name="root">The root command spec (its <see cref="CommandSpec.Name"/> is the binary name).</param>
    /// <returns>The script text, ending with a newline.</returns>
    /// <exception cref="ArgumentOutOfRangeException">The shell value is not defined.</exception>
    public static string Generate(Shell shell, CommandSpec root)
    {
        ArgumentNullException.ThrowIfNull(root);
        var model = Flatten(SchemaBuilder.Normalize(root));
        return shell switch
        {
            Shell.Bash => Bash(root.Name, model),
            Shell.Zsh => Zsh(root.Name, model),
            Shell.Fish => Fish(root.Name, model),
            Shell.PowerShell => PowerShell(root.Name, model),
            _ => throw new ArgumentOutOfRangeException(nameof(shell), shell, "Unknown shell."),
        };
    }

    private static List<Node> Flatten(CommandSpec root)
    {
        var nodes = new List<Node>();
        Visit(root, string.Empty, [], nodes);
        return nodes;
    }

    private static void Visit(CommandSpec command, string key, IReadOnlyList<OptionSpec> inherited, List<Node> nodes)
    {
        var options = inherited.Concat(command.Options).GroupBy(o => o.Name).Select(g => g.Last()).ToList();
        var words = command.Commands.Select(c => c.Name)
            .Concat(options.SelectMany(o => o.Aliases.Prepend(o.Name)))
            .Concat(command.Arguments.SelectMany(a => a.Values))
            .Distinct()
            .Order(StringComparer.Ordinal)
            .ToList();
        var valued = options.Where(o => o.Values.Count > 0).ToList();
        nodes.Add(new Node(key, command, words, valued));
        var recursive = options.Where(o => o.Recursive).ToList();
        foreach (var child in command.Commands)
        {
            Visit(child, child.Name, recursive, nodes);
        }
    }

    private static string Bash(string bin, List<Node> nodes)
    {
        var function = "_" + Identifier(bin);
        var sb = new StringBuilder();
        sb.Append($"# bash completion for {bin} (generated by `{bin} completion bash`; do not edit)\n");
        sb.Append($"{function}() {{\n");
        sb.Append("    local cur prev cmd i\n");
        sb.Append("    cur=\"${COMP_WORDS[COMP_CWORD]}\"\n");
        sb.Append("    prev=\"${COMP_WORDS[COMP_CWORD-1]}\"\n");
        sb.Append("    cmd=\"\"\n");
        sb.Append("    for ((i = 1; i < COMP_CWORD; i++)); do\n");
        sb.Append("        case \"${COMP_WORDS[i]}\" in\n");
        AppendCommandCase(sb, nodes, "            ", "cmd=\"${COMP_WORDS[i]}\" ;;");
        sb.Append("        esac\n");
        sb.Append("    done\n");
        sb.Append("    case \"$prev\" in\n");
        foreach (var option in ValuedOptions(nodes))
        {
            sb.Append($"        {string.Join('|', option.Aliases.Prepend(option.Name))}) ");
            sb.Append($"COMPREPLY=($(compgen -W \"{string.Join(' ', option.Values)}\" -- \"$cur\")); return ;;\n");
        }

        sb.Append("    esac\n");
        sb.Append("    case \"$cmd\" in\n");
        foreach (var node in nodes)
        {
            sb.Append($"        {(node.Key.Length == 0 ? "\"\"" : node.Key)}) ");
            sb.Append($"COMPREPLY=($(compgen -W \"{string.Join(' ', node.Words)}\" -- \"$cur\")) ;;\n");
        }

        sb.Append("    esac\n");
        sb.Append("}\n");
        sb.Append($"complete -F {function} {bin}\n");
        return sb.ToString();
    }

    private static string Zsh(string bin, List<Node> nodes)
    {
        var function = "_" + Identifier(bin);
        var sb = new StringBuilder();
        sb.Append($"#compdef {bin}\n");
        sb.Append($"# zsh completion for {bin} (generated by `{bin} completion zsh`; do not edit)\n");
        sb.Append($"{function}() {{\n");
        sb.Append("    local cmd=\"\" prev=\"${words[CURRENT-1]}\" i\n");
        sb.Append("    local -a candidates\n");
        sb.Append("    for (( i = 2; i < CURRENT; i++ )); do\n");
        sb.Append("        case \"${words[i]}\" in\n");
        AppendCommandCase(sb, nodes, "            ", "cmd=\"${words[i]}\" ;;");
        sb.Append("        esac\n");
        sb.Append("    done\n");
        sb.Append("    case \"$prev\" in\n");
        foreach (var option in ValuedOptions(nodes))
        {
            sb.Append($"        {string.Join('|', option.Aliases.Prepend(option.Name))}) ");
            sb.Append($"compadd -- {string.Join(' ', option.Values)}; return ;;\n");
        }

        sb.Append("    esac\n");
        sb.Append("    case \"$cmd\" in\n");
        foreach (var node in nodes)
        {
            sb.Append($"        {(node.Key.Length == 0 ? "\"\"" : node.Key)}) ");
            sb.Append($"candidates=({string.Join(' ', node.Words)}) ;;\n");
        }

        sb.Append("    esac\n");
        sb.Append("    compadd -- \"${candidates[@]}\"\n");
        sb.Append("}\n");
        sb.Append($"if [[ \"${{funcstack[1]}}\" == \"{function}\" ]]; then\n");
        sb.Append($"    {function} \"$@\"\n");
        sb.Append("else\n");
        sb.Append($"    compdef {function} {bin}\n");
        sb.Append("fi\n");
        return sb.ToString();
    }

    private static string Fish(string bin, List<Node> nodes)
    {
        var sb = new StringBuilder();
        sb.Append($"# fish completion for {bin} (generated by `{bin} completion fish`; do not edit)\n");
        sb.Append($"complete -c {bin} -f\n");
        var commandNames = nodes.Where(n => n.Key.Length > 0).Select(n => n.Key).Distinct().ToList();
        foreach (var node in nodes)
        {
            var condition = node.Key.Length == 0
                ? (commandNames.Count == 0 ? string.Empty : $" -n \"not __fish_seen_subcommand_from {string.Join(' ', commandNames)}\"")
                : $" -n \"__fish_seen_subcommand_from {node.Key}\"";
            foreach (var child in node.Command.Commands)
            {
                sb.Append($"complete -c {bin}{condition} -a {child.Name} -d {FishQuote(child.Description)}\n");
            }

            foreach (var argument in node.Command.Arguments.Where(a => a.Values.Count > 0))
            {
                sb.Append($"complete -c {bin}{condition} -a {FishQuote(string.Join(' ', argument.Values))}\n");
            }

            foreach (var option in node.Command.Options)
            {
                sb.Append($"complete -c {bin}{(option.Recursive ? string.Empty : condition)}{FishSwitches(option)}");
                if (option.Values.Count > 0)
                {
                    sb.Append($" -x -a {FishQuote(string.Join(' ', option.Values))}");
                }

                sb.Append($" -d {FishQuote(option.Description)}\n");
            }
        }

        return sb.ToString();
    }

    private static string PowerShell(string bin, List<Node> nodes)
    {
        var sb = new StringBuilder();
        sb.Append($"# PowerShell completion for {bin} (generated by `{bin} completion powershell`; do not edit)\n");
        sb.Append($"Register-ArgumentCompleter -Native -CommandName '{bin}' -ScriptBlock {{\n");
        sb.Append("    param($wordToComplete, $commandAst, $cursorPosition)\n");
        sb.Append("    $commands = @{\n");
        foreach (var node in nodes)
        {
            sb.Append($"        '{node.Key}' = @({string.Join(", ", node.Words.Select(w => $"'{w}'"))})\n");
        }

        sb.Append("    }\n");
        sb.Append("    $values = @{\n");
        foreach (var option in ValuedOptions(nodes))
        {
            foreach (var name in option.Aliases.Prepend(option.Name))
            {
                sb.Append($"        '{name}' = @({string.Join(", ", option.Values.Select(v => $"'{v}'"))})\n");
            }
        }

        sb.Append("    }\n");
        sb.Append("    $cmd = ''\n");
        sb.Append("    $prev = ''\n");
        sb.Append("    foreach ($element in ($commandAst.CommandElements | Select-Object -Skip 1)) {\n");
        sb.Append("        $text = $element.ToString()\n");
        sb.Append("        if ($element.Extent.StartOffset -ge $cursorPosition) { break }\n");
        sb.Append("        if ($text -ne $wordToComplete) { $prev = $text }\n");
        sb.Append("        if ($text -ne '' -and $commands.ContainsKey($text)) { $cmd = $text }\n");
        sb.Append("    }\n");
        sb.Append("    $candidates = if ($values.ContainsKey($prev)) { $values[$prev] } else { $commands[$cmd] }\n");
        sb.Append("    $candidates | Where-Object { $_ -like \"$wordToComplete*\" } | ForEach-Object {\n");
        sb.Append("        [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)\n");
        sb.Append("    }\n");
        sb.Append("}\n");
        return sb.ToString();
    }

    private static void AppendCommandCase(StringBuilder sb, List<Node> nodes, string indent, string action)
    {
        var names = nodes.Where(n => n.Key.Length > 0).Select(n => n.Key).Distinct().ToList();
        if (names.Count > 0)
        {
            sb.Append($"{indent}{string.Join('|', names)}) {action}\n");
        }
    }

    private static IEnumerable<OptionSpec> ValuedOptions(List<Node> nodes) =>
        nodes.SelectMany(n => n.Valued).GroupBy(o => o.Name).Select(g => g.First()).OrderBy(o => o.Name, StringComparer.Ordinal);

    private static string FishSwitches(OptionSpec option)
    {
        var sb = new StringBuilder();
        foreach (var name in option.Aliases.Prepend(option.Name))
        {
            sb.Append(name.StartsWith("--", StringComparison.Ordinal) ? $" -l {name[2..]}" : $" -s {name.TrimStart('-')}");
        }

        return sb.ToString();
    }

    private static string FishQuote(string text) => "'" + text.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("'", "\\'", StringComparison.Ordinal) + "'";

    private static string Identifier(string bin) =>
        new(bin.Select(c => char.IsAsciiLetterOrDigit(c) ? c : '_').ToArray());

    private sealed record Node(string Key, CommandSpec Command, IReadOnlyList<string> Words, IReadOnlyList<OptionSpec> Valued);
}
```

### `src/__ToolName__.Core/Completion/Shell.cs`

```csharp
namespace __ToolName__.Core.Completion;

/// <summary>
/// Shells <c>__BinName__ completion</c> can generate a script for.
/// </summary>
public enum Shell
{
    /// <summary>GNU Bash (<c>complete -F</c>).</summary>
    Bash,

    /// <summary>Zsh (<c>compdef</c>).</summary>
    Zsh,

    /// <summary>fish (<c>complete -c</c>).</summary>
    Fish,

    /// <summary>PowerShell (<c>Register-ArgumentCompleter -Native</c>).</summary>
    PowerShell,
}
```

### `src/__ToolName__.Core/Docs/AtomicFile.cs`

```csharp
namespace __ToolName__.Core.Docs;

/// <summary>
/// Writes files completely or not at all (temp file + rename), so an interrupted run never leaves half a file.
/// </summary>
public static class AtomicFile
{
    /// <summary>
    /// Writes <paramref name="contents"/> to <paramref name="path"/>, creating the parent directory.
    /// </summary>
    /// <param name="path">The destination file.</param>
    /// <param name="contents">The UTF-8 text to write (no BOM).</param>
    public static void WriteAllText(string path, string contents)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        ArgumentNullException.ThrowIfNull(contents);
        var full = Path.GetFullPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        var temp = full + ".tmp-" + Environment.ProcessId;
        File.WriteAllText(temp, contents);
        File.Move(temp, full, overwrite: true);
    }
}
```

### `src/__ToolName__.Core/Docs/ReferenceGenerator.cs`

```csharp
using System.Text;
using __ToolName__.Core.Schema;

namespace __ToolName__.Core.Docs;

/// <summary>
/// Renders the <c>schema</c> document as the AsciiDoc CLI reference page and as a man page — generated from the
/// command tree, never maintained by hand next to the code (CLI contract §7). Output is deterministic (no dates).
/// </summary>
public static class ReferenceGenerator
{
    /// <summary>
    /// Renders the Antora page <c>cli-reference.adoc</c>.
    /// </summary>
    /// <param name="schema">The schema document.</param>
    /// <returns>The AsciiDoc text.</returns>
    public static string ToAsciiDoc(SchemaDocument schema)
    {
        ArgumentNullException.ThrowIfNull(schema);
        var sb = new StringBuilder();
        sb.Append($"= `{schema.Name}` command reference\n");
        sb.Append($"// Generated by `{schema.Name} gen-docs` from the command tree; do not edit by hand.\n\n");
        sb.Append($"{schema.Command.Description}\n\n");
        sb.Append($"This page describes version `{schema.Version}`. ");
        sb.Append($"`{schema.Name} schema` prints the same information as JSON.\n\n");
        sb.Append("== Global options\n\n");
        AppendOptions(sb, schema.Command.Options);
        foreach (var command in Flatten(schema.Command.Commands))
        {
            sb.Append($"== `{command.Path}`\n\n{command.Description}\n\n");
            sb.Append($"Safety: {Safety(command.Safety)}.");
            if (command.OutputSchemaVersion is { } version)
            {
                sb.Append($" JSON output `schemaVersion`: {version}.");
            }

            sb.Append("\n\n");
            if (command.Arguments.Count > 0)
            {
                sb.Append(".Arguments\n[cols=\"1,1,1,3\"]\n|===\n|Name |Arity |Default |Description\n\n");
                foreach (var argument in command.Arguments)
                {
                    sb.Append($"|`{argument.Name}` |{argument.Arity} |{Code(argument.Default)} |{Cell(Describe(argument.Description, argument.Values))}\n");
                }

                sb.Append("|===\n\n");
            }

            if (command.Options.Count > 0)
            {
                AppendOptions(sb, command.Options);
            }
        }

        sb.Append("== Exit codes\n\n[cols=\"1,2,5\"]\n|===\n|Code |Name |Meaning\n\n");
        foreach (var exitCode in schema.ExitCodes)
        {
            sb.Append($"|{exitCode.Code} |`{exitCode.Name}` |{Cell(exitCode.Meaning)}\n");
        }

        sb.Append("|===\n");
        return sb.ToString();
    }

    /// <summary>
    /// Renders the section-1 man page (roff).
    /// </summary>
    /// <param name="schema">The schema document.</param>
    /// <returns>The roff text.</returns>
    public static string ToManPage(SchemaDocument schema)
    {
        ArgumentNullException.ThrowIfNull(schema);
        var sb = new StringBuilder();
        sb.Append($".TH {Roff(schema.Name.ToUpperInvariant())} 1 \"\" \"{Roff(schema.Name)} {Roff(schema.Version)}\" \"User Commands\"\n");
        sb.Append($".SH NAME\n{Roff(schema.Name)} \\- {Roff(schema.Command.Description)}\n");
        sb.Append($".SH SYNOPSIS\n.B {Roff(schema.Name)}\n[\\fIOPTIONS\\fR] \\fICOMMAND\\fR [\\fIARGS\\fR]\n");
        sb.Append(".SH COMMANDS\n");
        foreach (var command in Flatten(schema.Command.Commands))
        {
            var arguments = string.Concat(command.Arguments.Select(a => $" [{a.Name}]"));
            var subcommand = command.Path[(command.Path.IndexOf(' ', StringComparison.Ordinal) + 1)..];
            sb.Append($".TP\n.B {Roff(subcommand + arguments)}\n{Roff(command.Description)}\n");
        }

        sb.Append(".SH OPTIONS\n");
        foreach (var option in schema.Command.Options)
        {
            var names = string.Join(", ", option.Aliases.Prepend(option.Name));
            sb.Append($".TP\n.B {Roff(names)}\n{Roff(Describe(option.Description, option.Values))}\n");
        }

        sb.Append(".SH EXIT STATUS\n");
        foreach (var exitCode in schema.ExitCodes)
        {
            sb.Append($".TP\n.B {exitCode.Code}\n{Roff(exitCode.Name)}: {Roff(exitCode.Meaning)}\n");
        }

        return sb.ToString();
    }

    private static void AppendOptions(StringBuilder sb, IReadOnlyList<OptionSpec> options)
    {
        sb.Append(".Options\n[cols=\"2,1,1,4\"]\n|===\n|Option |Type |Default |Description\n\n");
        foreach (var option in options)
        {
            var names = string.Join(", ", option.Aliases.Prepend(option.Name).Select(n => $"`{n}`"));
            sb.Append($"|{names} |{option.Type} |{Code(option.Default)} |{Cell(Describe(option.Description, option.Values))}\n");
        }

        sb.Append("|===\n\n");
    }

    private static IEnumerable<CommandSpec> Flatten(IEnumerable<CommandSpec> commands) =>
        commands.SelectMany(c => Flatten(c.Commands).Prepend(c));

    private static string Describe(string description, IReadOnlyList<string> values) =>
        values.Count == 0 ? description : $"{description} One of: {string.Join(", ", values)}.";

    private static string Safety(CommandSafety safety)
    {
        var kind = safety.ReadOnly ? "read-only" : safety.Destructive ? "destructive (needs `--yes`; supports `--dry-run`)" : "mutating";
        return safety.Idempotent ? kind + ", idempotent" : kind;
    }

    private static string Code(string? value) => value is null ? "-" : $"`{value}`";

    private static string Cell(string text) => text.Replace("|", "\\|", StringComparison.Ordinal);

    private static string Roff(string text) =>
        text.Replace("\\", "\\e", StringComparison.Ordinal).Replace("-", "\\-", StringComparison.Ordinal);
}
```

### `src/__ToolName__.Core/Greeting/Greeter.cs`

```csharp
namespace __ToolName__.Core.Greeting;

/// <summary>
/// The sample domain logic behind <c>__BinName__ greet</c>: validates a name and builds the greeting.
/// </summary>
public static class Greeter
{
    /// <summary>
    /// The longest accepted name, in UTF-16 code units.
    /// </summary>
    public const int MaxNameLength = 64;

    /// <summary>
    /// The name greeted when none is given.
    /// </summary>
    public const string DefaultName = "world";

    /// <summary>
    /// Validates <paramref name="name"/> and greets it.
    /// </summary>
    /// <param name="name">The name to greet; <see langword="null"/> greets <see cref="DefaultName"/>.</param>
    /// <returns>The greeting document.</returns>
    /// <exception cref="CliException">The name is empty, too long, or contains control characters (exit code 3).</exception>
    public static GreetingResult Greet(string? name)
    {
        name ??= DefaultName;
        if (string.IsNullOrWhiteSpace(name))
        {
            throw Invalid("The name must not be empty.", "empty");
        }

        if (name.Length > MaxNameLength)
        {
            throw Invalid($"The name must be at most {MaxNameLength} characters long.", "too-long");
        }

        if (name.Any(char.IsControl))
        {
            throw Invalid("The name must not contain control characters.", "control-characters");
        }

        return new GreetingResult($"Hello, {name}!", name, 1);
    }

    private static CliException Invalid(string message, string reason) =>
        new(
            ExitCode.Validation,
            message,
            $"Pass a name of 1-{MaxNameLength} printable characters, e.g. `{ToolInfo.BinName} greet Ada`.",
            "INVALID_NAME",
            new Dictionary<string, string> { ["reason"] = reason });
}
```

### `src/__ToolName__.Core/Greeting/GreetingResult.cs`

```csharp
namespace __ToolName__.Core.Greeting;

/// <summary>
/// The JSON result of <c>__BinName__ greet</c> (keys in alphabetical order).
/// </summary>
/// <param name="Greeting">The greeting text, e.g. <c>Hello, Ada!</c>.</param>
/// <param name="Name">The greeted name.</param>
/// <param name="SchemaVersion">The output schema version.</param>
public sealed record GreetingResult(string Greeting, string Name, int SchemaVersion);
```

### `src/__ToolName__.Core/Output/CliJsonContext.cs`

```csharp
using System.Text.Json.Serialization;
using __ToolName__.Core.Greeting;
using __ToolName__.Core.Schema;

namespace __ToolName__.Core.Output;

/// <summary>
/// Source-generated System.Text.Json metadata for every document the tool writes — no reflection, so the tool stays
/// trimming- and Native AOT-safe. Register each new output type here.
/// </summary>
[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    WriteIndented = false,
    GenerationMode = JsonSourceGenerationMode.Metadata)]
[JsonSerializable(typeof(ErrorEnvelope))]
[JsonSerializable(typeof(SummaryLine))]
[JsonSerializable(typeof(LogLine))]
[JsonSerializable(typeof(GreetingResult))]
[JsonSerializable(typeof(SchemaDocument))]
internal sealed partial class CliJsonContext : JsonSerializerContext
{
}
```

### `src/__ToolName__.Core/Output/DiagnosticLog.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// Diagnostics on stderr: shown with <c>--verbose</c>, hidden by <c>--quiet</c>, JSON with <c>--log-format json</c>.
/// Never writes to stdout.
/// </summary>
public sealed class DiagnosticLog
{
    private readonly TextWriter _stderr;
    private readonly GlobalSettings _settings;

    /// <summary>
    /// Initializes a new instance of the <see cref="DiagnosticLog"/> class.
    /// </summary>
    /// <param name="stderr">The error stream.</param>
    /// <param name="settings">The parsed global flags.</param>
    public DiagnosticLog(TextWriter stderr, GlobalSettings settings)
    {
        ArgumentNullException.ThrowIfNull(stderr);
        ArgumentNullException.ThrowIfNull(settings);
        _stderr = stderr;
        _settings = settings;
    }

    /// <summary>
    /// Writes a debug diagnostic when <c>--verbose</c> was given at least <paramref name="level"/> times.
    /// </summary>
    /// <param name="message">The diagnostic; never a secret.</param>
    /// <param name="level">The minimum verbosity.</param>
    public void Debug(string message, int level = 1)
    {
        if (_settings.Quiet || _settings.Verbosity < level)
        {
            return;
        }

        if (_settings.LogFormat == LogFormat.Json)
        {
            JsonOutput.WriteLine(_stderr, new LogLine("debug", message));
        }
        else
        {
            _stderr.Write($"debug: {message}\n");
        }
    }
}
```

### `src/__ToolName__.Core/Output/ErrorBody.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// The body of an <see cref="ErrorEnvelope"/>.
/// </summary>
/// <param name="Code">Stable SCREAMING_SNAKE identifier.</param>
/// <param name="Details">Machine-readable details, sorted by key.</param>
/// <param name="ExitCode">The process exit code.</param>
/// <param name="Hint">How to fix it.</param>
/// <param name="Message">What went wrong.</param>
/// <param name="Retryable">Whether retrying with the same arguments may succeed.</param>
public sealed record ErrorBody(
    string Code,
    IReadOnlyDictionary<string, string> Details,
    int ExitCode,
    string? Hint,
    string Message,
    bool Retryable);
```

### `src/__ToolName__.Core/Output/ErrorEnvelope.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// The JSON error envelope written to stderr in machine mode (CLI contract §4). Properties are declared in
/// alphabetical order so the serialized keys are sorted.
/// </summary>
/// <param name="Error">The error.</param>
/// <param name="SchemaVersion">The envelope schema version.</param>
public sealed record ErrorEnvelope(ErrorBody Error, int SchemaVersion);
```

### `src/__ToolName__.Core/Output/ErrorReporter.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// Writes a <see cref="CliException"/> to stderr: an <see cref="ErrorEnvelope"/> in machine mode, otherwise
/// <c>__BinName__: &lt;message&gt;</c> and <c>hint: &lt;hint&gt;</c> lines.
/// </summary>
public static class ErrorReporter
{
    private const string Red = "\u001b[31m";
    private const string Reset = "\u001b[0m";

    /// <summary>
    /// Reports <paramref name="error"/> and returns its exit code.
    /// </summary>
    /// <param name="stderr">The error stream.</param>
    /// <param name="error">The failure.</param>
    /// <param name="format">The requested output format; <c>json</c>/<c>jsonl</c> produce an envelope.</param>
    /// <param name="color">Whether stderr may be coloured (see <see cref="Terminal.ColorPolicy"/>).</param>
    /// <returns>The process exit code.</returns>
    public static int Report(TextWriter stderr, CliException error, OutputFormat format, bool color)
    {
        ArgumentNullException.ThrowIfNull(stderr);
        ArgumentNullException.ThrowIfNull(error);
        if (format != OutputFormat.Text)
        {
            JsonOutput.WriteLine(stderr, ToEnvelope(error));
            return (int)error.ExitCode;
        }

        var prefix = color ? $"{Red}{ToolInfo.BinName}:{Reset}" : $"{ToolInfo.BinName}:";
        stderr.Write($"{prefix} {error.Message}\n");
        if (!string.IsNullOrWhiteSpace(error.Hint))
        {
            stderr.Write($"hint: {error.Hint}\n");
        }

        return (int)error.ExitCode;
    }

    /// <summary>
    /// Builds the JSON envelope of <paramref name="error"/>.
    /// </summary>
    /// <param name="error">The failure.</param>
    /// <returns>The envelope.</returns>
    public static ErrorEnvelope ToEnvelope(CliException error)
    {
        ArgumentNullException.ThrowIfNull(error);
        return new ErrorEnvelope(
            new ErrorBody(error.Code, error.Details, (int)error.ExitCode, error.Hint, error.Message, error.Retryable),
            1);
    }
}
```

### `src/__ToolName__.Core/Output/GlobalSettings.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// The global flags every command accepts, after parsing.
/// </summary>
/// <param name="Format">The result format on stdout.</param>
/// <param name="NoColor">Whether <c>--no-color</c> was given.</param>
/// <param name="NoInput">Whether <c>--no-input</c> was given.</param>
/// <param name="Quiet">Whether <c>--quiet</c> was given (suppresses informational stderr, never errors).</param>
/// <param name="Verbosity">How many times <c>--verbose</c> was given.</param>
/// <param name="LogFormat">The diagnostics format on stderr.</param>
public sealed record GlobalSettings(
    OutputFormat Format,
    bool NoColor,
    bool NoInput,
    bool Quiet,
    int Verbosity,
    LogFormat LogFormat)
{
    /// <summary>
    /// Gets the settings used when no flag is given.
    /// </summary>
    public static GlobalSettings Default { get; } = new(OutputFormat.Text, false, false, false, 0, LogFormat.Text);

    /// <summary>
    /// Gets a value indicating whether a machine-readable format (<c>json</c>/<c>jsonl</c>) was requested.
    /// </summary>
    public bool MachineMode => Format != OutputFormat.Text;

    /// <summary>
    /// Parses a <c>--format</c> value.
    /// </summary>
    /// <param name="value">One of <c>text</c>, <c>json</c>, <c>jsonl</c>.</param>
    /// <returns>The format.</returns>
    /// <exception cref="ArgumentException">The value is not a known format.</exception>
    public static OutputFormat ParseFormat(string value) => value switch
    {
        "text" => OutputFormat.Text,
        "json" => OutputFormat.Json,
        "jsonl" => OutputFormat.Jsonl,
        _ => throw new ArgumentException($"Unknown format '{value}'.", nameof(value)),
    };

    /// <summary>
    /// Parses a <c>--log-format</c> value.
    /// </summary>
    /// <param name="value">One of <c>text</c>, <c>json</c>.</param>
    /// <returns>The log format.</returns>
    /// <exception cref="ArgumentException">The value is not a known log format.</exception>
    public static LogFormat ParseLogFormat(string value) => value switch
    {
        "text" => LogFormat.Text,
        "json" => LogFormat.Json,
        _ => throw new ArgumentException($"Unknown log format '{value}'.", nameof(value)),
    };
}
```

### `src/__ToolName__.Core/Output/JsonOutput.cs`

```csharp
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization.Metadata;

namespace __ToolName__.Core.Output;

/// <summary>
/// Deterministic JSON writing: compact separators, keys in declaration (alphabetical) order, no HTML-escaping of
/// characters such as <c>'</c> or <c>`</c>, one document per line.
/// </summary>
public static class JsonOutput
{
    private static readonly JsonSerializerOptions Options = new(CliJsonContext.Default.Options)
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    /// <summary>
    /// Serializes <paramref name="value"/> to a compact JSON string.
    /// </summary>
    /// <typeparam name="T">A type registered in the source-generated JSON context.</typeparam>
    /// <param name="value">The document.</param>
    /// <returns>The JSON text, without a trailing newline.</returns>
    public static string Serialize<T>(T value) =>
        JsonSerializer.Serialize(value, (JsonTypeInfo<T>)Options.GetTypeInfo(typeof(T)));

    /// <summary>
    /// Writes <paramref name="value"/> as one JSON line.
    /// </summary>
    /// <typeparam name="T">A type registered in the source-generated JSON context.</typeparam>
    /// <param name="writer">The destination (stdout for results, stderr for envelopes).</param>
    /// <param name="value">The document.</param>
    public static void WriteLine<T>(TextWriter writer, T value)
    {
        ArgumentNullException.ThrowIfNull(writer);
        writer.Write(Serialize(value));
        writer.Write('\n');
    }

    /// <summary>
    /// Writes the <c>jsonl</c> summary line that marks a clean finish.
    /// </summary>
    /// <param name="writer">stdout.</param>
    /// <param name="total">How many records were written before it.</param>
    /// <param name="nextCursor">The next page's cursor, or <see langword="null"/>.</param>
    public static void WriteSummary(TextWriter writer, int total, string? nextCursor = null) =>
        WriteLine(writer, new SummaryLine(nextCursor, 1, total, "summary"));
}
```

### `src/__ToolName__.Core/Output/LogFormat.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// The shape of diagnostics on stderr (<c>--log-format</c>).
/// </summary>
public enum LogFormat
{
    /// <summary>Plain <c>debug: …</c> lines.</summary>
    Text,

    /// <summary>One JSON object per diagnostic line.</summary>
    Json,
}
```

### `src/__ToolName__.Core/Output/LogLine.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// One structured diagnostic line on stderr (<c>--log-format json</c>).
/// </summary>
/// <param name="Level">The level, e.g. <c>debug</c>.</param>
/// <param name="Message">The diagnostic.</param>
public sealed record LogLine(string Level, string Message);
```

### `src/__ToolName__.Core/Output/OutputFormat.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// The shape of a command's result on stdout (<c>--format</c>; <c>--json</c> is an alias for <see cref="Json"/>).
/// </summary>
public enum OutputFormat
{
    /// <summary>Human-readable text (the default).</summary>
    Text,

    /// <summary>Exactly one JSON document.</summary>
    Json,

    /// <summary>One JSON object per line, ending with a summary line.</summary>
    Jsonl,
}
```

### `src/__ToolName__.Core/Output/SummaryLine.cs`

```csharp
namespace __ToolName__.Core.Output;

/// <summary>
/// The final line of every <c>jsonl</c> stream; its presence marks a clean finish.
/// </summary>
/// <param name="NextCursor">The cursor of the next page, or <see langword="null"/>.</param>
/// <param name="SchemaVersion">The schema version.</param>
/// <param name="Total">How many records were written.</param>
/// <param name="Type">Always <c>summary</c>.</param>
public sealed record SummaryLine(string? NextCursor, int SchemaVersion, int Total, string Type);
```

### `src/__ToolName__.Core/Schema/ArgumentSpec.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// A positional argument.
/// </summary>
/// <param name="Arity">Accepted value count, e.g. <c>0..1</c>.</param>
/// <param name="Default">The default value, or <see langword="null"/>.</param>
/// <param name="Description">What the argument is.</param>
/// <param name="Name">The argument name.</param>
/// <param name="Type">The JSON-schema-like type: <c>string</c>, <c>integer</c>, <c>number</c>, <c>boolean</c>, <c>path</c> or <c>array</c>.</param>
/// <param name="Values">The accepted values when restricted, otherwise empty.</param>
public sealed record ArgumentSpec(
    string Arity,
    string? Default,
    string Description,
    string Name,
    string Type,
    IReadOnlyList<string> Values);
```

### `src/__ToolName__.Core/Schema/CommandSafety.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// The safety metadata of a command, as an MCP wrapper maps it to tool annotations.
/// </summary>
/// <param name="Destructive">Whether it can destroy data (needs <c>--yes</c>, offers <c>--dry-run</c>).</param>
/// <param name="Idempotent">Whether re-running with the same input is a no-op.</param>
/// <param name="ReadOnly">Whether it never changes anything.</param>
public sealed record CommandSafety(bool Destructive, bool Idempotent, bool ReadOnly)
{
    /// <summary>
    /// Gets the safety of a command that only reads.
    /// </summary>
    public static CommandSafety ReadOnlyCommand { get; } = new(false, true, true);
}
```

### `src/__ToolName__.Core/Schema/CommandSpec.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// A framework-neutral description of one command, built from the parser's command tree and used by
/// <c>__BinName__ schema</c>, the completion scripts and the generated CLI reference. Properties are declared in
/// alphabetical order so the serialized keys are sorted.
/// </summary>
/// <param name="Arguments">Positional arguments, in order.</param>
/// <param name="Commands">Subcommands, sorted by name.</param>
/// <param name="Description">When to use the command, in one line.</param>
/// <param name="Name">The command name (the binary name for the root).</param>
/// <param name="Options">Options accepted by this command, sorted by name (recursive ones are listed where declared).</param>
/// <param name="OutputSchemaVersion">The <c>schemaVersion</c> of the command's JSON output, or <see langword="null"/>.</param>
/// <param name="Path">The full command path, e.g. <c>__BinName__ greet</c>.</param>
/// <param name="Safety">Whether the command reads, mutates or destroys.</param>
public sealed record CommandSpec(
    IReadOnlyList<ArgumentSpec> Arguments,
    IReadOnlyList<CommandSpec> Commands,
    string Description,
    string Name,
    IReadOnlyList<OptionSpec> Options,
    int? OutputSchemaVersion,
    string Path,
    CommandSafety Safety);
```

### `src/__ToolName__.Core/Schema/ExitCodeSpec.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// One exit code in the <c>schema</c> output.
/// </summary>
/// <param name="Code">The numeric code.</param>
/// <param name="Meaning">The caller reaction it asks for.</param>
/// <param name="Name">The stable identifier.</param>
public sealed record ExitCodeSpec(int Code, string Meaning, string Name);
```

### `src/__ToolName__.Core/Schema/OptionSpec.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// An option (flag).
/// </summary>
/// <param name="Aliases">Other spellings, sorted (e.g. <c>-q</c>).</param>
/// <param name="Default">The default value, or <see langword="null"/>.</param>
/// <param name="Description">What the option does.</param>
/// <param name="Name">The canonical name, e.g. <c>--format</c>.</param>
/// <param name="Recursive">Whether subcommands inherit it.</param>
/// <param name="Repeatable">Whether it may be given more than once (e.g. <c>-vv</c>).</param>
/// <param name="Required">Whether it must be given.</param>
/// <param name="Type">The JSON-schema-like value type; <c>boolean</c> for flags.</param>
/// <param name="Values">The accepted values when restricted, otherwise empty.</param>
public sealed record OptionSpec(
    IReadOnlyList<string> Aliases,
    string? Default,
    string Description,
    string Name,
    bool Recursive,
    bool Repeatable,
    bool Required,
    string Type,
    IReadOnlyList<string> Values);
```

### `src/__ToolName__.Core/Schema/SchemaBuilder.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// Builds the <c>schema</c> document from a command tree, normalized for deterministic output.
/// </summary>
public static class SchemaBuilder
{
    /// <summary>
    /// Builds the schema document.
    /// </summary>
    /// <param name="root">The root command spec.</param>
    /// <returns>The document, with subcommands and options sorted by name and exit codes ascending.</returns>
    public static SchemaDocument Build(CommandSpec root)
    {
        ArgumentNullException.ThrowIfNull(root);
        var exitCodes = ExitCodes.All
            .Select(code => new ExitCodeSpec((int)code, ExitCodes.MeaningOf(code), ExitCodes.NameOf(code)))
            .ToList();
        return new SchemaDocument(Normalize(root), exitCodes, ToolInfo.BinName, 1, ToolInfo.Version);
    }

    /// <summary>
    /// Sorts subcommands and options by ordinal name, recursively (arguments keep their positional order).
    /// </summary>
    /// <param name="command">The command spec.</param>
    /// <returns>The normalized spec.</returns>
    public static CommandSpec Normalize(CommandSpec command)
    {
        ArgumentNullException.ThrowIfNull(command);
        return command with
        {
            Commands = command.Commands.Select(Normalize).OrderBy(c => c.Name, StringComparer.Ordinal).ToList(),
            Options = command.Options
                .Select(o => o with { Aliases = o.Aliases.Order(StringComparer.Ordinal).ToList() })
                .OrderBy(o => o.Name, StringComparer.Ordinal)
                .ToList(),
        };
    }
}
```

### `src/__ToolName__.Core/Schema/SchemaDocument.cs`

```csharp
namespace __ToolName__.Core.Schema;

/// <summary>
/// The document printed by <c>__BinName__ schema</c>.
/// </summary>
/// <param name="Command">The root command and its tree.</param>
/// <param name="ExitCodes">Every exit code the tool can emit, ascending.</param>
/// <param name="Name">The binary name.</param>
/// <param name="SchemaVersion">The schema document version.</param>
/// <param name="Version">The tool version.</param>
public sealed record SchemaDocument(
    CommandSpec Command,
    IReadOnlyList<ExitCodeSpec> ExitCodes,
    string Name,
    int SchemaVersion,
    string Version);
```

### `src/__ToolName__.Core/Terminal/ColorPolicy.cs`

```csharp
using __ToolName__.Core.Output;

namespace __ToolName__.Core.Terminal;

/// <summary>
/// Decides, per stream, whether colour and styling may be written (CLI contract §3).
/// </summary>
public static class ColorPolicy
{
    /// <summary>
    /// Decides colour for results on stdout.
    /// </summary>
    /// <param name="settings">The parsed global flags.</param>
    /// <param name="environment">The terminal snapshot.</param>
    /// <returns><see langword="true"/> when stdout may carry ANSI styling.</returns>
    public static bool ForStdout(GlobalSettings settings, TerminalEnvironment environment)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(environment);
        return !settings.MachineMode && Decide(settings, environment, environment.StdoutIsTerminal);
    }

    /// <summary>
    /// Decides colour for messages on stderr.
    /// </summary>
    /// <param name="settings">The parsed global flags.</param>
    /// <param name="environment">The terminal snapshot.</param>
    /// <returns><see langword="true"/> when stderr may carry ANSI styling.</returns>
    public static bool ForStderr(GlobalSettings settings, TerminalEnvironment environment)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(environment);
        return !settings.MachineMode
            && !settings.Quiet
            && settings.LogFormat == LogFormat.Text
            && Decide(settings, environment, environment.StderrIsTerminal);
    }

    private static bool Decide(GlobalSettings settings, TerminalEnvironment environment, bool isTerminal)
    {
        // --no-color and NO_COLOR always win, then TERM=dumb; FORCE_COLOR/CLICOLOR_FORCE override "not a terminal".
        if (settings.NoColor || !string.IsNullOrEmpty(environment.Get("NO_COLOR")))
        {
            return false;
        }

        if (string.Equals(environment.Get("TERM"), "dumb", StringComparison.Ordinal))
        {
            return false;
        }

        return isTerminal || environment.IsTruthy("FORCE_COLOR") || environment.IsTruthy("CLICOLOR_FORCE");
    }
}
```

### `src/__ToolName__.Core/Terminal/PromptPolicy.cs`

```csharp
using __ToolName__.Core.Output;

namespace __ToolName__.Core.Terminal;

/// <summary>
/// Decides whether the tool may ask the user a question (CLI contract §3). When it may not, the command must fail
/// fast — exit 2 for a missing value, 5 for a missing confirmation — naming the flag that supplies the answer.
/// </summary>
public static class PromptPolicy
{
    /// <summary>
    /// Decides whether prompting is allowed.
    /// </summary>
    /// <param name="settings">The parsed global flags.</param>
    /// <param name="environment">The terminal snapshot.</param>
    /// <returns><see langword="true"/> only when stdin and stderr are terminals and nothing disables input.</returns>
    public static bool CanPrompt(GlobalSettings settings, TerminalEnvironment environment)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(environment);
        return environment.StdinIsTerminal
            && environment.StderrIsTerminal
            && !settings.NoInput
            && !settings.MachineMode
            && !environment.IsTruthy(ToolInfo.EnvPrefix + "NO_INPUT")
            && !string.Equals(environment.Get("CI"), "true", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Builds the fail-fast error for a confirmation that could not be asked.
    /// </summary>
    /// <param name="action">What would have been confirmed, e.g. <c>delete 3 items</c>.</param>
    /// <param name="confirmCommand">The exact command line that confirms it (usually with <c>--yes</c>).</param>
    /// <returns>An exception with exit code 5 whose details carry <c>confirmCommand</c>.</returns>
    public static CliException ConfirmationRequired(string action, string confirmCommand) =>
        new(
            ExitCode.ConfirmationRequired,
            $"Refusing to {action} without confirmation.",
            $"Re-run with --yes to confirm: {confirmCommand}",
            details: new Dictionary<string, string> { ["confirmCommand"] = confirmCommand });
}
```

### `src/__ToolName__.Core/Terminal/ShutdownCoordinator.cs`

```csharp
using System.Runtime.InteropServices;

namespace __ToolName__.Core.Terminal;

/// <summary>
/// Turns SIGINT/SIGTERM into cancellation (CLI contract §2): the first signal cancels <see cref="Token"/> so the
/// running command can clean up and the entry point exits 130/143; a second signal exits immediately.
/// </summary>
public sealed class ShutdownCoordinator : IDisposable
{
    private readonly CancellationTokenSource _cancellation = new();
    private readonly Action<int> _immediateExit;
    private readonly List<PosixSignalRegistration> _registrations = [];
    private int _signalExitCode;

    /// <summary>
    /// Initializes a new instance of the <see cref="ShutdownCoordinator"/> class without registering any handler.
    /// </summary>
    /// <param name="immediateExit">Called with 130/143 on a second signal; defaults to <see cref="Environment.Exit"/>.</param>
    public ShutdownCoordinator(Action<int>? immediateExit = null)
    {
        _immediateExit = immediateExit ?? Environment.Exit;
    }

    /// <summary>
    /// Gets the token cancelled by the first SIGINT/SIGTERM.
    /// </summary>
    public CancellationToken Token => _cancellation.Token;

    /// <summary>
    /// Gets the exit code the received signal asks for, or <see langword="null"/> when no signal arrived.
    /// </summary>
    public ExitCode? SignalExitCode => Volatile.Read(ref _signalExitCode) is var code and not 0 ? (ExitCode)code : null;

    /// <summary>
    /// Registers SIGINT and SIGTERM handlers for the lifetime of the returned coordinator.
    /// </summary>
    /// <returns>The coordinator; dispose it to unregister.</returns>
    public static ShutdownCoordinator Install()
    {
        var coordinator = new ShutdownCoordinator();
        coordinator._registrations.Add(PosixSignalRegistration.Create(PosixSignal.SIGINT, coordinator.OnSignal));
        coordinator._registrations.Add(PosixSignalRegistration.Create(PosixSignal.SIGTERM, coordinator.OnSignal));
        return coordinator;
    }

    /// <inheritdoc/>
    public void Dispose()
    {
        foreach (var registration in _registrations)
        {
            registration.Dispose();
        }

        _registrations.Clear();
        _cancellation.Dispose();
    }

    /// <summary>
    /// Handles one signal: the first cancels <see cref="Token"/>, a second exits immediately.
    /// </summary>
    /// <param name="signal">The received signal.</param>
    internal void Handle(PosixSignal signal)
    {
        var code = signal == PosixSignal.SIGTERM ? ExitCode.Terminated : ExitCode.Interrupted;
        if (Interlocked.CompareExchange(ref _signalExitCode, (int)code, 0) != 0)
        {
            _immediateExit((int)code);
            return;
        }

        _cancellation.Cancel();
    }

    private void OnSignal(PosixSignalContext context)
    {
        // Keep the process alive so the command can clean up; the entry point exits with 130/143.
        context.Cancel = true;
        Handle(context.Signal);
    }
}
```

### `src/__ToolName__.Core/Terminal/TerminalEnvironment.cs`

```csharp
namespace __ToolName__.Core.Terminal;

/// <summary>
/// A snapshot of what the terminal policies decide on: which standard streams are terminals, and the environment
/// variables that influence colour and prompting. Tests build one directly; the tool uses <see cref="FromSystem"/>.
/// </summary>
/// <param name="StdinIsTerminal">Whether stdin is an interactive terminal.</param>
/// <param name="StdoutIsTerminal">Whether stdout is an interactive terminal.</param>
/// <param name="StderrIsTerminal">Whether stderr is an interactive terminal.</param>
/// <param name="Variables">The relevant environment variables (missing = unset).</param>
public sealed record TerminalEnvironment(
    bool StdinIsTerminal,
    bool StdoutIsTerminal,
    bool StderrIsTerminal,
    IReadOnlyDictionary<string, string> Variables)
{
    /// <summary>
    /// The environment variables the policies read.
    /// </summary>
    public static readonly IReadOnlyList<string> RelevantVariables =
    [
        "CI",
        "CLICOLOR_FORCE",
        "FORCE_COLOR",
        "NO_COLOR",
        "TERM",
        ToolInfo.EnvPrefix + "NO_INPUT",
    ];

    /// <summary>
    /// Captures the current process's streams and environment.
    /// </summary>
    /// <returns>The snapshot.</returns>
    public static TerminalEnvironment FromSystem()
    {
        var variables = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var name in RelevantVariables)
        {
            var value = Environment.GetEnvironmentVariable(name);
            if (value is not null)
            {
                variables[name] = value;
            }
        }

        return new TerminalEnvironment(
            !Console.IsInputRedirected,
            !Console.IsOutputRedirected,
            !Console.IsErrorRedirected,
            variables);
    }

    /// <summary>
    /// Gets an environment variable from the snapshot.
    /// </summary>
    /// <param name="name">The variable name.</param>
    /// <returns>Its value, or <see langword="null"/> when unset.</returns>
    public string? Get(string name) => Variables.TryGetValue(name, out var value) ? value : null;

    /// <summary>
    /// Gets a value indicating whether a variable is set to a non-empty value other than <c>0</c>/<c>false</c>.
    /// </summary>
    /// <param name="name">The variable name.</param>
    /// <returns><see langword="true"/> when the variable is truthy.</returns>
    public bool IsTruthy(string name) =>
        Get(name) is { Length: > 0 } value
        && value != "0"
        && !value.Equals("false", StringComparison.OrdinalIgnoreCase);
}
```
