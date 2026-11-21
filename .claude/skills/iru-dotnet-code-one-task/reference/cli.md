# .NET command-line tools (System.CommandLine 2.0 + Spectre.Console, Native AOT)

How to implement a task in a .NET command-line tool — the layout `iru-setup-dotnet-cli` scaffolds, or any
`<OutputType>Exe</OutputType>` project built on System.CommandLine. Read it together with `cli-contract.md` (the
behaviour every command must keep). The `list` example below was compiled with warnings as errors (StyleCop, CA,
trim and AOT analyzers), run (`--json`, a validator error → exit 2) and Native AOT-published against .NET SDK
10.0.402, System.CommandLine 2.0.13 and Spectre.Console 0.57.2 inside a scaffolded solution on 2026-10-16.

## Recognise the shape

| Signal | Meaning |
|---|---|
| `src/<Tool>/<Tool>.csproj`: `Exe`, `AssemblyName <bin>`, `PublishAot`, `PackAsTool`, a `System.CommandLine` reference | a CLI project (the `iru-setup-dotnet-cli` layout) |
| `src/<Tool>.Core/` with `ExitCode.cs`, `CliException.cs`, `Output/CliJsonContext.cs`, `Terminal/ColorPolicy.cs` | the contract lives in Core: logic and policies, no System.CommandLine/Spectre dependency |
| `tests/<Tool>.Core.Tests` and `tests/<Tool>.Cli.Tests` (`CliRunner`, `[TestCategory("PublishedBinary")]`) | the two test layers |
| `src/<Tool>/Commands/BrowseCommand.cs` + `src/<Tool>/Tui/BrowseView.cs` + a `Terminal.Gui` reference | the optional full-screen TUI (`ui: tui`) — read `ui-contract.md` + `ui-implementation.md` for any task touching it |
| `System.CommandLine` `2.0.0-beta*`, `SetHandler`, `InvocationContext`, `CommandLineBuilder` | the **old beta API** — removed in 2.0 GA; follow the project's version, don't mix APIs, and report it |
| `Spectre.Console.Cli` (`CommandApp`, `Command<TSettings>`) | a different framework: follow it; map its usage-error exit code to 2 in one place if it isn't already |

A CLI without a Core project (logic in `Program.cs`) is still a CLI: put new logic in a separate class the tests can
reach, and report the divergence rather than restructuring the solution.

## Where code goes

- **`src/<Tool>.Core/`** — everything testable: the command's logic (a static class or a small service), its result
  record(s) (properties declared **alphabetically**, `int SchemaVersion` included), new error codes (a
  `CliException` factory), validation. The 80% coverage bar is measured here; it must stay free of
  System.CommandLine, Spectre.Console and Terminal.Gui types.
- **`src/<Tool>/Commands/<Name>Command.cs`** — one static class per command with `Create(GlobalOptions, CliStreams)`
  returning a `ToolCommand`: the arguments/options, the action (parse → call Core → write output), nothing else.
  Register it in `CliApp.BuildRootCommand` (`root.Subcommands.Add(...)`).
- **Never touch** for a feature task: `ExitCode` numbers, the envelope records, `ErrorReporter`, `ShutdownCoordinator`,
  `CliApp.RunAsync`'s error mapping — they are the contract.

## System.CommandLine 2.0 patterns

```csharp
internal static class ListCommand
{
    public static Command Create(GlobalOptions globals, CliStreams streams)
    {
        var limit = new Option<int>("--limit")
        {
            Description = "Maximum number of items to return.",
            DefaultValueFactory = _ => 20,
        };
        limit.Validators.Add(result =>
        {
            if (result.GetValueOrDefault<int>() is < 1 or > 500)
            {
                result.AddError("--limit must be between 1 and 500.");   // parse error -> exit 2
            }
        });
        var cursor = new Option<string?>("--cursor") { Description = "Continue after this cursor." };

        var command = new ToolCommand(
            "list",
            "List items, newest first. Use it to find a NAME for the other commands.",
            CommandSafety.ReadOnlyCommand,
            outputSchemaVersion: 1,
            $"{ToolInfo.BinName} list --limit 5 --json")
        {
            limit,
            cursor,
        };
        command.SetAction(async (parseResult, cancellationToken) =>
        {
            var settings = globals.Read(parseResult);
            var page = await ItemCatalog.ListAsync(parseResult.GetValue(limit), parseResult.GetValue(cursor), cancellationToken)
                .ConfigureAwait(false);
            ItemOutput.Write(streams, settings, page);   // Core decides json / jsonl / text
            return (int)ExitCode.Ok;
        });
        return command;
    }
}
```

- 2.0 GA API only: `new Option<T>("--name", "-n") { Description, DefaultValueFactory, Required, Recursive, Arity }`,
  `new Argument<T>("name") { Arity = ArgumentArity.ZeroOrOne, DefaultValueFactory }`, `AcceptOnlyFromAmong(...)` for
  fixed values, `Validators.Add(result => result.AddError(...))`, `SetAction(parseResult => int)` or
  `SetAction(async (parseResult, cancellationToken) => int)`, `parseResult.GetValue(option)`. `SetHandler`,
  `InvocationContext`, `CommandLineBuilder` and `IConsole` are beta-only — never introduce them.
- Long-running or I/O work uses the **async** overload and passes `cancellationToken` everywhere — it is cancelled by
  SIGINT/SIGTERM (`ShutdownCoordinator`), and the process then exits 130/143. A synchronous action can't observe it.
- Usage errors are reported by `CliApp` **before** invocation (exit 2 with "Run `<bin> <cmd> --help`"); never print
  help text for a runtime error, and never call `Environment.Exit` from a command — return the exit code or throw
  `CliException`.
- Don't re-enable what the scaffold turned off: response files (`ParserConfiguration.ResponseFileTokenReplacer =
  null`), the default exception handler, the library's own process-termination handling. 2.0 has no prefix
  matching of commands/options — don't add aliases that make two spellings ambiguous.
- One-line `Description` saying **when** to use the command, plus at least one real example in `ToolCommand`'s
  examples (shown under "Examples:" in `--help`). Every option is a long `--kebab-case` name; add a short alias only
  for very common flags. Global flags (`--format/--json`, `--no-color`, `--no-input`, `-q`, `-v`, `--log-format`) are
  `Recursive` on the root — never redeclare them on a subcommand.
- A mutating command gets `--dry-run` (exit 0, structured plan, same code path up to the write) and, if destructive,
  `--yes`; its `CommandSafety` says so (`new CommandSafety(Destructive: true, Idempotent: …, ReadOnly: false)`).
  Without `--yes`: prompt only when `PromptPolicy.CanPrompt(settings, streams.Terminal)`, otherwise throw
  `PromptPolicy.ConfirmationRequired(action, confirmCommand)` (exit 5).
- Hidden maintainer commands (`command.Hidden = true`, like `gen-docs`) are skipped by `schema`, completions and the
  reference.

## Output, errors and colour

- **Results** go to `streams.Out` only. JSON: `JsonOutput.WriteLine(streams.Out, result)`; `jsonl`: one
  `WriteLine` per record then `JsonOutput.WriteSummary(streams.Out, total, nextCursor)`; lists carry `total`,
  `nextCursor` and `truncated`. **Register every new output type** in `Output/CliJsonContext.cs`
  (`[JsonSerializable(typeof(ItemPage))]`) — `JsonOutput.Serialize<T>` throws at run time for an unregistered type.
- Key order is deterministic because record properties are declared alphabetically (`CamelCase` naming policy);
  dictionaries are `SortedDictionary<string, …>` with `StringComparer.Ordinal`; arrays have a documented sort key.
  No timestamps, absolute paths or machine-specific values unless the command is about them.
- **Errors**: `throw new CliException(ExitCode.NotFound, "No item named 'x'.", "Run `<bin> list` to see the names.",
  code: "ITEM_NOT_FOUND", details: …)` — the hint is mandatory for codes 2–7, never contains secrets. `CliApp` turns it
  into the envelope (JSON mode) or `<bin>: message` + `hint:` lines, with the right exit code. Validation of
  well-formed input → 3; transient failures → 6 (`Retryable` is derived).
- **Text and styling**: build a console with `ConsoleFactory.Create(streams.Out, ColorPolicy.ForStdout(settings,
  streams.Terminal), streams.Terminal.StdoutIsTerminal)` and write with Spectre markup, always escaping data:
  `console.MarkupLine($"[green]{Markup.Escape(text)}[/]")` (`MarkupLineInterpolated` escapes automatically). Never use
  the static `AnsiConsole` — it does its own colour detection and writes to the real stdout, bypassing the policy and
  the tests' captured streams. Tables (`new Table()`) are fine on stdout in text mode; when stdout isn't a terminal
  the factory disables wrapping.
- **Progress/spinners** (`console.Status()`, `console.Progress()`) only on a console built for `streams.Error` and
  only when `streams.Terminal.StderrIsTerminal && ColorPolicy.ForStderr(...)`; otherwise at most a plain
  `DiagnosticLog.Debug` line. Never on stdout.
- **Diagnostics**: `new DiagnosticLog(streams.Error, settings).Debug("…")` — shown with `-v`, hidden by `-q`, JSON with
  `--log-format json`.
- Output that can flood an agent's context is a bug: bounded lists (`--limit`, small default, `--cursor`), and
  truncation is always signalled.

## Native AOT and trimming

The CLI project sets `PublishAot` and `IsAotCompatible`, Core sets `IsAotCompatible`, and `TreatWarningsAsErrors` turns
every trim/AOT analyzer warning (IL2026, IL2075, IL3050, …) into a **build** error — so these rules are checked on
every `dotnet build`, and `dotnet publish -r <rid>` checks the rest.

- **JSON only through source generation**: `JsonSerializer.Serialize(value)`/`Deserialize<T>(json)` without a
  `JsonTypeInfo`, `JsonSerializerOptions` without a `TypeInfoResolver`, and `JsonNode`/`JsonDocument` →
  `Deserialize<T>()` reflection paths are forbidden. Use `JsonOutput` (writing) or, for reading, a Core method that
  calls `JsonSerializer.Deserialize(json, CliJsonContext.Default.<Type>)` (register the type; the context is
  `internal` to Core).
- **No reflection over unknown types**: no `Type.GetType("…")`, `Activator.CreateInstance(type)`,
  `GetType().GetMethod(...)`/`GetProperties()`, `Assembly.GetTypes()`, `dynamic`, `Expression.Compile()`,
  `Reflection.Emit`. Use generics, `switch` over known types, or explicit registration. `Enum.GetValues<T>()` /
  `Enum.GetNames<T>()` (generic) are fine.
- `[DynamicallyAccessedMembers(...)]` on a `Type` parameter/field only when reflection over that type is genuinely
  unavoidable and the type is statically known at the call sites; never silence a warning with
  `[UnconditionalSuppressMessage]`/`#pragma warning disable IL…`/`<NoWarn>` without proving the code is safe — and say
  so in the report.
- A new NuGet package must be trimming/AOT-compatible (`IsTrimmable`/`IsAotCompatible` in its assembly metadata, no
  IL warnings on publish); if it isn't, that is a blocker to report, not something to suppress.
- `InvariantGlobalization` is on: culture-specific formatting/parsing is invariant — use `CultureInfo.InvariantCulture`
  explicitly anyway (CA1305) and never rely on the user's locale for output that is data.
- Keep startup cheap: no network, config writes or update checks on the default path; lazy-initialise expensive
  services inside the action that needs them.

## Signals, broken pipes, files and processes

- Cancellation: pass the action's `CancellationToken` down; on cancellation clean up (delete temp files) and let
  `OperationCanceledException` propagate — `CliApp`/`Program` print one line and exit 130/143.
- Writes to a closed pipe (`<bin> … | head -n1`) end quietly: .NET ignores `EPIPE` on Unix console streams and
  `CliApp` maps a pipe `IOException` to exit 0 — don't catch and print it yourself.
- Write files with `AtomicFile.WriteAllText` (temp + rename). Resolve paths from arguments with `Path.GetFullPath` and
  check they stay under the intended root before reading or writing.
- Child processes: `ProcessStartInfo` with `ArgumentList.Add(...)` per argument (never a single argument string,
  never `UseShellExecute = true`, never `cmd /c`/`sh -c`), an absolute or resolved program path, and `--` before
  user-supplied operands.

## Terminal UI (`ui: tui`, Terminal.Gui v2)

- State and transitions live in Core (`Browse/BrowseState` style) and are unit-tested; the view, composed in
  `Tui/BrowseView.cs` (a `Create(state, title)` factory the tests can render without the command), only renders and
  forwards events; `Commands/BrowseCommand.cs` owns the TTY gate, the cancellation timer and `app.Run`. Lists use
  `ListView`/`TableView` over the state's data, and every view change brings `[TestCategory("UI")]` view tests with
  `.ui-screenshots/` text captures — all in `ui-implementation.md`.
- The command starts only when `streams.Terminal.StdinIsTerminal && StdoutIsTerminal` and not in machine mode;
  otherwise it throws a `CliException(ExitCode.Usage, …)` whose hint names the non-interactive `--json` command that
  gives the same data. Every function the TUI offers must exist as such a command.
- `using var app = Application.Create(); app.Init(); … app.Run(window);` (instance API — not the v1 static
  `Application.Init()/Top`). Stop on SIGINT/SIGTERM with a UI-thread timer that polls the token
  (`app.AddTimeout(TimeSpan.FromMilliseconds(100), () => { … app.RequestStop(); return false; })`), so the terminal
  is restored before the process exits 130/143.

## Testing

- **Core** (`tests/<Tool>.Core.Tests`, MSTest 4): one test class per type; every new branch, validation message and
  exit code; JSON goldens as exact strings (`Assert.AreEqual("""{"a":1,"schemaVersion":1}""", JsonOutput.Serialize(x))`).
  MSTest 4 style: `Assert.ThrowsExactly`, `Assert.Contains(substring, value)`, `Assert.StartsWith`,
  `Assert.HasCount`, `Assert.ContainsSingle`, `Assert.IsEmpty` — not the removed `ExpectedException`/
  `Assert.ThrowsException`, and not `StringAssert`/`CollectionAssert` (MSTEST0046/0068).
- **Commands** (`tests/<Tool>.Cli.Tests`): run the real command tree in-process with `CliRunner.RunAsync(args)` (piped
  terminal) or `CliRunner.RunAsync(CliRunner.Interactive with { Variables = … }, args)`, and assert all three of exit
  code, stdout and stderr: the success output (text and `--json` golden), each exit code the command can emit, stdout
  empty on failure, the envelope in JSON mode, no `\u001b` when piped or with `NO_COLOR`/`--no-color`/`TERM=dumb`, and
  no prompt without a terminal (exit 2/5 instead). The schema test (`Schema_KeysAreSorted_AndOutputIsStable`) covers
  new commands automatically.
- Tests never touch the real console, environment variables of the process, or the user's home directory: pass a
  `TerminalEnvironment` and temp directories.
- `[TestCategory("PublishedBinary")]` tests run only when `<BIN>_BINARY` points to a published binary (CI); keep new
  ones few and contract-level.

## Docs generated from the command tree

`docs/modules/ROOT/pages/cli-reference.adoc` (committed), the man page and completion scripts are generated by the
hidden `gen-docs` command — after adding or changing a command or option, regenerate them
(`dotnet run --project src/<Tool> -c Release -- gen-docs --output-dir .`) or, if this task can't run it, say in the
report that the group skill must. Never edit `cli-reference.adoc` by hand.
