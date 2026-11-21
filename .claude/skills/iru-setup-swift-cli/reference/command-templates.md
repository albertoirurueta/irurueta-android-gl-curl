# Command templates — `Sources/__Bin__/`

The executable target: a thin `swift-argument-parser` tree. Each subcommand declares its flags, its
`DescribedCommand.metadata` (safety + exit codes for `schema`) and one line calling the Kit. The root's
`static func main() async` hands the process to `CommandRunner.main`, which is what turns usage errors into exit 2.
Conventions are in `package-templates.md`.

### `Sources/__Bin__/CompletionCommand.swift`

```swift
import ArgumentParser
import __Bin__Kit

/// `__bin__ completion <shell>`.
struct CompletionCommand: AsyncParsableCommand, DescribedCommand {
    static let configuration = CommandConfiguration(
        commandName: "completion",
        abstract: "Print the shell completion script for bash, zsh or fish.",
        discussion: "Example: source <(\(Tool.name) completion bash)"
    )

    static let metadata = CommandMetadata(safety: .readOnly)

    @OptionGroup var global: GlobalOptions

    @Argument(help: "The shell: bash, zsh or fish.")
    var shell: CompletionShellName

    func run() async throws {
        try CompletionAction.run(
            root: __Bin__Command.self, shell: shell, session: CommandContext.current.session(for: global))
    }
}
```

### `Sources/__Bin__/GreetCommand.swift`

```swift
import ArgumentParser
import __Bin__Kit

/// `__bin__ greet [NAME]`.
struct GreetCommand: AsyncParsableCommand, DescribedCommand {
    static let configuration = CommandConfiguration(
        commandName: "greet",
        abstract: "Print a greeting for NAME (read-only).",
        discussion: """
            Use it to check that the tool runs. NAME `-` reads one line from stdin; with no NAME the tool asks for \
            one on a terminal and fails with exit 2 otherwise.
            Example: \(Tool.name) greet Ada --json
            """
    )

    static let metadata = CommandMetadata(safety: .readOnly, exitCodes: [.validation])

    @OptionGroup var global: GlobalOptions

    @Argument(help: "The name to greet (`-` reads it from stdin).")
    var name: String?

    func run() async throws {
        try GreetAction.run(name: name, session: CommandContext.current.session(for: global))
    }
}
```

### `Sources/__Bin__/__Bin__Command.swift`

```swift
import ArgumentParser
import __Bin__Kit

/// The `__bin__` root command, a thin entry point.
///
/// All behaviour lives in `__Bin__Kit`.
@main
struct __Bin__Command: AsyncParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: Tool.name,
        abstract: "__Description__",
        discussion: """
            Results go to stdout, messages and errors to stderr. Every command accepts --format text|json|jsonl \
            (--json), --no-color, --no-input, --quiet, --verbose and --log-format. Exit codes: 0 ok, 1 internal, \
            2 usage, 3 validation, 4 not found, 5 confirmation required, 6 retryable, 7 auth, 130/143 signals.
            Example: \(Tool.name) greet Ada --json
            """,
        version: toolVersion,
        subcommands: [GreetCommand.self, CompletionCommand.self, SchemaCommand.self]
    )

    /// Runs the tool under the CLI contract instead of swift-argument-parser's default `main()` (which exits 64
    /// on a usage error).
    static func main() async {
        await CommandRunner.main(Self.self)
    }
}
```

### `Sources/__Bin__/SchemaCommand.swift`

```swift
import ArgumentParser
import __Bin__Kit

/// `__bin__ schema [COMMAND]`.
struct SchemaCommand: AsyncParsableCommand, DescribedCommand {
    static let configuration = CommandConfiguration(
        commandName: "schema",
        abstract: "Print the machine-readable command tree as JSON.",
        discussion: """
            Lists every command with its arguments, flags, exit codes and safety metadata — the shape an MCP \
            wrapper maps to tool definitions. Pass a command name to print only that command.
            Example: \(Tool.name) schema greet
            """
    )

    static let metadata = CommandMetadata(safety: .readOnly, exitCodes: [.notFound])

    @OptionGroup var global: GlobalOptions

    @Argument(help: "Print only this command.")
    var command: String?

    @Flag(help: "Render the AsciiDoc CLI reference instead of JSON (used by scripts/generate-docs.sh).")
    var asciidoc = false

    func run() async throws {
        try SchemaAction.run(
            root: __Bin__Command.self,
            command: command,
            asciidoc: asciidoc,
            session: CommandContext.current.session(for: global)
        )
    }
}
```
