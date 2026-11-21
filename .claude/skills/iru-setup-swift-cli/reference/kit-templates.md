# Kit templates — `Sources/__Bin__Kit/`

The library target that holds every behaviour of the tool (the contract implementation and the sample command), so
`Tests/__Bin__KitTests` can unit-test it in-process and reach ≥ 80% line coverage (the verified scaffold measures
~95%). Conventions are in `package-templates.md`. Files, by role:

| File | Role (CLI contract section) |
|---|---|
| `Version.swift` | `toolVersion` — the single version literal `iru-swift-bump-version` rewrites (§7) |
| `Tool.swift` | the executable name and the `__BIN_ENV__` environment prefix |
| `CLIExitCode.swift` | the exit-code enum, exactly the contract table: 0–7, 130, 143 (§2) |
| `CLIError.swift`, `JSONValue.swift` | the error type and the envelope's free-form `details` (§4) |
| `Output.swift` | `OutputFormat` (+ detection from raw args for pre-parse errors), deterministic `JSONOutput` (sorted keys), `ErrorEnvelope`, `ErrorReporter`, `Diagnostics` (`--quiet`/`--verbose`/`--log-format`) (§1, §4) |
| `Terminal.swift` | `TerminalInfo` (isatty per stream), `ColorPolicy`, `PromptPolicy`, `Style` (§3) |
| `Platform.swift` | the only `#if canImport(Darwin)/Glibc/Musl` file: `isatty`, `write(2)` with EPIPE → `StreamError.brokenPipe`, signals, `exit`/`_exit` |
| `Streams.swift` | `TextOutput`/`TextInput` and their live (file descriptor) and test (captured/scripted) implementations (§1) |
| `GlobalOptions.swift` | the global flags as an `@OptionGroup` (`--format`, `--json`, `--no-color`, `--no-input`, `-q`, `-v`, `--log-format`) |
| `CommandContext.swift` | the injected I/O context (`@TaskLocal current`), `Session` (resolved policy per invocation), `CleanupRegistry` |
| `SignalTrap.swift` | SIGINT/SIGTERM via `DispatchSource` signal sources → cleanup, one stderr line, exit 130/143; second signal `_exit` (§2) |
| `CommandRunner.swift` | replaces swift-argument-parser's `main()`: usage errors → **2** (the library's default is 64), help/version/dump to stdout, envelope on failure, EPIPE → quiet exit 0 |
| `Greeting.swift` | the sample `greet` logic (`GreetingService`, `GreetAction`) and the `jsonl` `Summary` line |
| `CommandSchema.swift` | the `schema` tree built from `--experimental-dump-help` plus each command's `DescribedCommand.metadata` (exit codes, safety) (§7) |
| `SchemaAction.swift` | `schema`/`completion` behaviour and the AsciiDoc CLI reference renderer |
| `__Bin__Kit.docc/__Bin__Kit.md` | the DocC landing page (`swift package generate-documentation --target __Bin__Kit`) |

### `Sources/__Bin__Kit/CLIError.swift`

```swift
/// A failure the tool reports to its caller: an exit code plus the fields of the JSON error envelope.
public struct CLIError: Error, Equatable, Sendable {
    /// The process exit code this failure maps to.
    public var exitCode: CLIExitCode
    /// The stable SCREAMING_SNAKE identifier; defaults to the exit code's name.
    public var code: String
    /// A one-sentence description of what went wrong.
    ///
    /// Never contains secrets.
    public var message: String
    /// How to fix it; never empty for exit codes 2–7.
    public var hint: String
    /// Structured, machine-readable detail (e.g. the plan, a confirm command, the usage line).
    public var details: [String: JSONValue]

    /// Whether retrying with the same arguments may succeed (true only for ``CLIExitCode/retryable``).
    public var retryable: Bool { exitCode == .retryable }

    /// Creates an error.
    /// - Parameters:
    ///   - exitCode: The exit code the failure maps to.
    ///   - message: What went wrong.
    ///   - hint: How to fix it; `nil` uses the exit code's default hint.
    ///   - code: A finer-grained identifier; `nil` uses the exit code's name.
    ///   - details: Structured detail for machine callers.
    public init(
        _ exitCode: CLIExitCode,
        message: String,
        hint: String? = nil,
        code: String? = nil,
        details: [String: JSONValue] = [:]
    ) {
        self.exitCode = exitCode
        self.message = message
        self.hint = hint ?? exitCode.defaultHint
        self.code = code ?? exitCode.name
        self.details = details
    }
}
```

### `Sources/__Bin__Kit/CLIExitCode.swift`

```swift
/// The process exit codes this tool emits — one code per caller reaction.
///
/// The numbers follow the catalog's CLI contract (section 2) and must never be renumbered: scripts and agents
/// branch on them. A new failure kind maps onto an existing reaction; finer detail goes in the error envelope's
/// `code` and `details`.
public enum CLIExitCode: Int32, CaseIterable, Sendable {
    /// Success, including an idempotent no-op and a `--dry-run` plan.
    case ok = 0
    /// An unexpected failure (a bug).
    case internalError = 1
    /// A bad flag, argument or unknown command.
    case usage = 2
    /// Well-formed input that was rejected.
    case validation = 3
    /// The named resource doesn't exist.
    case notFound = 4
    /// A destructive action needs confirmation and none was given.
    case confirmationRequired = 5
    /// A transient failure; retrying with the same arguments may succeed.
    case retryable = 6
    /// Missing or invalid credentials.
    case auth = 7
    /// Interrupted by SIGINT (128 + 2).
    case interrupted = 130
    /// Terminated by SIGTERM (128 + 15).
    case terminated = 143

    /// The stable SCREAMING_SNAKE identifier used as the error envelope's `code`.
    public var name: String {
        switch self {
        case .ok: "OK"
        case .internalError: "INTERNAL"
        case .usage: "USAGE"
        case .validation: "VALIDATION"
        case .notFound: "NOT_FOUND"
        case .confirmationRequired: "CONFIRMATION_REQUIRED"
        case .retryable: "RETRYABLE"
        case .auth: "AUTH"
        case .interrupted: "INTERRUPTED"
        case .terminated: "TERMINATED"
        }
    }

    /// What the caller should do when it sees this code.
    public var meaning: String {
        switch self {
        case .ok: "Success. Continue."
        case .internalError: "Unexpected failure (a bug). Report it; don't retry blindly."
        case .usage: "Bad flag, argument or unknown command. Fix the command line."
        case .validation: "Input was rejected. Read the problems, fix the data."
        case .notFound: "The named resource doesn't exist. Look it up first."
        case .confirmationRequired: "Confirmation needed. Re-run with the confirm command from the envelope."
        case .retryable: "Transient failure. Wait, then retry with the same arguments."
        case .auth: "Missing or invalid credentials. Log in or set the token variable."
        case .interrupted: "Interrupted by SIGINT."
        case .terminated: "Terminated by SIGTERM."
        }
    }

    /// The hint used when an error doesn't supply its own.
    public var defaultHint: String {
        switch self {
        case .ok, .interrupted, .terminated: ""
        case .internalError: "This is a bug in \(Tool.name); please report it with the command line you ran."
        case .usage: "Run `\(Tool.name) --help` to see the available commands and flags."
        case .validation: "Fix the input and run the command again."
        case .notFound: "Check the name and try again."
        case .confirmationRequired: "Re-run with --yes to confirm, or with --dry-run to see the plan."
        case .retryable: "Wait a moment, then retry with the same arguments."
        case .auth: "Set \(Tool.environmentPrefix)TOKEN or log in, then retry."
        }
    }

    /// The exit status for a terminating signal: 128 plus the signal number (130 for SIGINT, 143 for SIGTERM).
    /// - Parameter signal: The signal number.
    /// - Returns: The process exit status to report.
    public static func status(forSignal signal: Int32) -> Int32 {
        128 + signal
    }
}
```

### `Sources/__Bin__Kit/CommandContext.swift`

```swift
import Foundation
import Synchronization

/// Cleanup actions (temporary files, partial output) run once before the process exits on a signal.
public final class CleanupRegistry: Sendable {
    private let actions = Mutex<[@Sendable () -> Void]>([])

    /// Creates an empty registry.
    public init() {}

    /// Registers an action.
    /// - Parameter action: The action to run on interruption.
    public func register(_ action: @escaping @Sendable () -> Void) {
        actions.withLock { $0.append(action) }
    }

    /// Runs every registered action once, most recent first, and forgets them.
    public func runAll() {
        let pending = actions.withLock { actions in
            defer { actions.removeAll() }
            return actions
        }
        for action in pending.reversed() {
            action()
        }
    }
}

/// The process's I/O and environment, injected so commands can be tested without a real terminal.
public struct CommandContext: Sendable {
    /// The context of the running command (the live process unless a test overrides it).
    @TaskLocal public static var current = CommandContext.live()

    /// Where results go.
    public var stdout: any TextOutput
    /// Where messages, prompts and errors go.
    public var stderr: any TextOutput
    /// Where answers to prompts and `-` operands come from.
    public var stdin: any TextInput
    /// The process environment.
    public var environment: [String: String]
    /// Which streams are terminals.
    public var terminal: TerminalInfo
    /// Actions to run when a signal interrupts the command.
    public var cleanup: CleanupRegistry

    /// Creates a context.
    /// - Parameters:
    ///   - stdout: Where results go.
    ///   - stderr: Where messages go.
    ///   - stdin: Where input comes from.
    ///   - environment: The environment variables.
    ///   - terminal: Which streams are terminals.
    ///   - cleanup: The cleanup registry.
    public init(
        stdout: any TextOutput,
        stderr: any TextOutput,
        stdin: any TextInput,
        environment: [String: String],
        terminal: TerminalInfo,
        cleanup: CleanupRegistry = CleanupRegistry()
    ) {
        self.stdout = stdout
        self.stderr = stderr
        self.stdin = stdin
        self.environment = environment
        self.terminal = terminal
        self.cleanup = cleanup
    }

    /// The real process streams, environment and terminal state.
    /// - Returns: A context bound to file descriptors 0, 1 and 2.
    public static func live() -> CommandContext {
        CommandContext(
            stdout: FileDescriptorOutput(fileDescriptor: Platform.standardOutput),
            stderr: FileDescriptorOutput(fileDescriptor: Platform.standardError),
            stdin: StandardInput(),
            environment: ProcessInfo.processInfo.environment,
            terminal: .current
        )
    }

    /// Resolves the per-invocation output policy for a command's parsed global options.
    /// - Parameter options: The parsed global options.
    /// - Returns: The session the command writes through.
    public func session(for options: GlobalOptions) -> Session {
        Session(context: self, options: options)
    }
}

/// One command invocation's resolved output policy: format, colour per stream, diagnostics and prompting.
public struct Session: Sendable {
    /// The I/O context.
    public let context: CommandContext
    /// The effective result format.
    public let format: OutputFormat
    /// Colour styling for stdout.
    public let outputStyle: Style
    /// Colour styling for stderr.
    public let errorStyle: Style
    /// Informational/diagnostic messages on stderr.
    public let diagnostics: Diagnostics
    /// Whether the command may prompt.
    public let canPrompt: Bool

    /// Resolves the policy.
    /// - Parameters:
    ///   - context: The I/O context.
    ///   - options: The parsed global options.
    public init(context: CommandContext, options: GlobalOptions) {
        self.context = context
        format = options.effectiveFormat
        outputStyle = Style(
            isEnabled: ColorPolicy.isEnabled(
                streamIsTerminal: context.terminal.stdoutIsTerminal,
                environment: context.environment,
                noColorFlag: options.noColor,
                machineOutput: options.effectiveFormat.isMachineReadable
            )
        )
        let errorStyle = Style(
            isEnabled: ColorPolicy.isEnabled(
                streamIsTerminal: context.terminal.stderrIsTerminal,
                environment: context.environment,
                noColorFlag: options.noColor,
                machineOutput: options.effectiveFormat.isMachineReadable || options.logFormat == .json
            )
        )
        self.errorStyle = errorStyle
        diagnostics = Diagnostics(
            output: context.stderr,
            verbosity: options.verbose,
            quiet: options.quiet,
            format: options.logFormat,
            style: errorStyle
        )
        canPrompt = PromptPolicy.canPrompt(
            terminal: context.terminal,
            environment: context.environment,
            noInputFlag: options.noInput
        )
    }

    /// Writes one line of result text to stdout.
    /// - Parameter line: The line, without its newline.
    /// - Throws: ``StreamError`` when stdout is closed.
    public func writeLine(_ line: String) throws(StreamError) {
        try context.stdout.write(line + "\n")
    }

    /// Writes a value as one line of deterministic JSON to stdout.
    /// - Parameter value: The value to encode.
    /// - Throws: ``StreamError`` when stdout is closed, or ``CLIError`` when encoding fails.
    public func writeJSON(_ value: some Encodable) throws {
        try writeLine(try JSONOutput.encode(value))
    }

    /// Asks a question on stderr and reads the answer from stdin — only when ``canPrompt``.
    /// - Parameter question: The question, without a trailing colon.
    /// - Returns: The trimmed answer, or `nil` when prompting isn't allowed or input ended.
    public func prompt(_ question: String) -> String? {
        guard canPrompt else { return nil }
        try? context.stderr.write("\(question): ")
        return context.stdin.readLine()?.trimmingCharacters(in: .whitespaces)
    }
}
```

### `Sources/__Bin__Kit/CommandRunner.swift`

```swift
import ArgumentParser

/// Parses and runs a command tree under the CLI contract, returning the process exit status.
///
/// It replaces swift-argument-parser's default `main()` so that: usage errors exit **2** (the library's default is
/// `EX_USAGE`, 64), `--help`/`--version` go to stdout with exit 0, every failure is reported once on stderr (a JSON
/// envelope in machine mode), and a closed stdout ends the run quietly.
public enum CommandRunner {
    /// Runs the live process: ignores SIGPIPE, traps SIGINT/SIGTERM, runs `root` with the process arguments, exits.
    /// - Parameter root: The root command type.
    public static func main<Root: ParsableCommand>(_ root: Root.Type) async -> Never {
        Platform.ignoreBrokenPipeSignal()
        let context = CommandContext.live()
        let trap = SignalTrap(signals: [Platform.interruptSignal, Platform.terminateSignal]) { signal in
            context.cleanup.runAll()
            let reason = signal == Platform.interruptSignal ? "interrupted" : "terminated"
            try? context.stderr.write("\(Tool.name): \(reason)\n")
        }
        let status = await run(root, arguments: Array(CommandLine.arguments.dropFirst()), context: context)
        trap.cancel()
        Platform.exitProcess(status)
    }

    /// Parses `arguments`, runs the selected command with `context` as ``CommandContext/current``, and maps the
    /// outcome to an exit status.
    /// - Parameters:
    ///   - root: The root command type.
    ///   - arguments: The arguments, without the executable path.
    ///   - context: The I/O context.
    /// - Returns: The process exit status.
    public static func run<Root: ParsableCommand>(
        _ root: Root.Type,
        arguments: [String],
        context: CommandContext
    ) async -> Int32 {
        let format = OutputFormat.detect(in: arguments)
        let style = Style(
            isEnabled: ColorPolicy.isEnabled(
                streamIsTerminal: context.terminal.stderrIsTerminal,
                environment: context.environment,
                noColorFlag: arguments.prefix { $0 != "--" }.contains("--no-color"),
                machineOutput: format.isMachineReadable
            )
        )
        do {
            var command = try root.parseAsRoot(arguments)
            try await CommandContext.$current.withValue(context) {
                if var asyncCommand = command as? any AsyncParsableCommand {
                    try await asyncCommand.run()
                } else {
                    try command.run()
                }
            }
            return CLIExitCode.ok.rawValue
        } catch let error as CLIError {
            ErrorReporter.report(error, format: format, to: context.stderr, style: style)
            return error.exitCode.rawValue
        } catch StreamError.brokenPipe {
            return CLIExitCode.ok.rawValue
        } catch {
            return report(libraryError: error, root: root, context: context, format: format, style: style)
        }
    }

    private static func report<Root: ParsableCommand>(
        libraryError error: any Error,
        root: Root.Type,
        context: CommandContext,
        format: OutputFormat,
        style: Style
    ) -> Int32 {
        let exitCode = root.exitCode(for: error)
        if exitCode == .success {
            // --help, --version and --experimental-dump-help are results: stdout, exit 0.
            let message = root.fullMessage(for: error, columns: nil)
            do {
                try context.stdout.write(message.hasSuffix("\n") ? message : message + "\n")
            } catch {
                return CLIExitCode.ok.rawValue
            }
            return CLIExitCode.ok.rawValue
        }
        if exitCode == .validationFailure {
            let usageError = usage(from: error, root: root)
            ErrorReporter.report(usageError, format: format, to: context.stderr, style: style)
            return usageError.exitCode.rawValue
        }
        if error is ExitCode {
            return exitCode.rawValue
        }
        let internalError = CLIError(.internalError, message: "Unexpected error: \(root.message(for: error))")
        ErrorReporter.report(internalError, format: format, to: context.stderr, style: style)
        return internalError.exitCode.rawValue
    }

    /// Converts an argument-parsing error into a ``CLIExitCode/usage`` error with the usage line and help pointer.
    /// - Parameters:
    ///   - error: The error thrown by `parseAsRoot`.
    ///   - root: The root command type.
    /// - Returns: The usage error to report.
    public static func usage<Root: ParsableCommand>(from error: any Error, root: Root.Type) -> CLIError {
        let full = root.fullMessage(for: error, columns: nil)
        let lines = full.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        let usageLine = lines.first { $0.hasPrefix("Usage:") }.map {
            String($0.dropFirst("Usage:".count)).trimmingCharacters(in: .whitespaces)
        }
        let helpPointer = lines.first { $0.hasPrefix("See '") }
        var details: [String: JSONValue] = [:]
        if let usageLine {
            details["usage"] = .string(usageLine)
        }
        var message = root.message(for: error)
        if message.hasPrefix("Error: ") {
            message.removeFirst("Error: ".count)
        }
        return CLIError(.usage, message: message, hint: helpPointer, details: details)
    }
}
```

### `Sources/__Bin__Kit/CommandSchema.swift`

```swift
import ArgumentParser
import Foundation

/// Safety metadata a command declares for `schema` (and for MCP tool annotations built from it).
public struct CommandSafety: Codable, Equatable, Sendable {
    /// The command changes nothing.
    public let readOnly: Bool
    /// The command can destroy data.
    public let destructive: Bool
    /// Running it twice with the same input has the effect of running it once.
    public let idempotent: Bool
    /// The command talks to systems outside the machine.
    public let openWorld: Bool
    /// The command refuses to act without `--yes` (exit 5) when it can't prompt.
    public let requiresConfirmation: Bool

    /// Creates safety metadata.
    /// - Parameters:
    ///   - readOnly: The command changes nothing.
    ///   - destructive: The command can destroy data.
    ///   - idempotent: Repeating it is harmless.
    ///   - openWorld: The command reaches outside the machine.
    ///   - requiresConfirmation: The command needs `--yes` when it can't prompt.
    public init(readOnly: Bool, destructive: Bool, idempotent: Bool, openWorld: Bool, requiresConfirmation: Bool) {
        self.readOnly = readOnly
        self.destructive = destructive
        self.idempotent = idempotent
        self.openWorld = openWorld
        self.requiresConfirmation = requiresConfirmation
    }

    /// A local, read-only, idempotent command.
    public static let readOnly = CommandSafety(
        readOnly: true,
        destructive: false,
        idempotent: true,
        openWorld: false,
        requiresConfirmation: false
    )
}

/// What a command declares about itself beyond its arguments: safety and the exit codes it can return.
public struct CommandMetadata: Equatable, Sendable {
    /// The command's safety metadata.
    public let safety: CommandSafety
    /// Every exit code the command can return.
    public let exitCodes: [CLIExitCode]

    /// Creates command metadata.
    /// - Parameters:
    ///   - safety: The command's safety metadata.
    ///   - exitCodes: Every exit code the command can return (0, 1, 2, 130 and 143 are always added).
    public init(safety: CommandSafety, exitCodes: [CLIExitCode] = []) {
        self.safety = safety
        let always: [CLIExitCode] = [.ok, .internalError, .usage, .interrupted, .terminated]
        self.exitCodes = Set(always + exitCodes).sorted { $0.rawValue < $1.rawValue }
    }
}

/// A command that describes itself in `schema` output.
///
/// Every subcommand should conform.
public protocol DescribedCommand: ParsableCommand {
    /// The command's safety and exit-code metadata.
    static var metadata: CommandMetadata { get }
}

/// The machine-readable command tree printed by `schema` (CLI contract section 7).
public struct CommandSchema: Encodable, Equatable, Sendable {
    /// A flag or option.
    public struct Flag: Encodable, Equatable, Sendable {
        /// The preferred spelling, e.g. `--format` or `-q`.
        public let name: String
        /// Other spellings.
        public let aliases: [String]
        /// `boolean`, `count`, `enum` or `string`.
        public let type: String
        /// Whether the flag must be given.
        public let required: Bool
        /// The default value, when there is one.
        public let defaultValue: String?
        /// The accepted values of an `enum`.
        public let values: [String]?
        /// The help text.
        public let description: String

        private enum CodingKeys: String, CodingKey {
            case name, aliases, type, required, values, description
            case defaultValue = "default"
        }
    }

    /// A positional argument.
    public struct Argument: Encodable, Equatable, Sendable {
        /// The value name, e.g. `name`.
        public let name: String
        /// `enum` or `string`.
        public let type: String
        /// Whether the argument must be given.
        public let required: Bool
        /// The accepted values of an `enum`.
        public let values: [String]?
        /// The help text.
        public let description: String
    }

    /// One command.
    public struct Command: Encodable, Equatable, Sendable {
        /// The command path below the root, e.g. `greet`.
        public let name: String
        /// The one-line summary.
        public let summary: String
        /// The positional arguments.
        public let args: [Argument]
        /// The command-specific flags (global flags are listed once, at the top level).
        public let flags: [Flag]
        /// Every exit code the command can return.
        public let exitCodes: [Int32]
        /// The command's safety metadata.
        public let safety: CommandSafety?
    }

    /// An exit code and its meaning.
    public struct ExitCodeInfo: Encodable, Equatable, Sendable {
        /// The numeric code.
        public let code: Int32
        /// The SCREAMING_SNAKE name.
        public let name: String
        /// What the caller should do.
        public let meaning: String
    }

    /// The output schema version.
    public let schemaVersion: Int
    /// The executable's name.
    public let name: String
    /// The tool's version.
    public let version: String
    /// Flags every command accepts.
    public let globalFlags: [Flag]
    /// Every command.
    public let commands: [Command]
    /// The exit-code table.
    public let exitCodes: [ExitCodeInfo]

    /// Builds the schema from swift-argument-parser's `--experimental-dump-help` JSON.
    ///
    /// The dump format is marked experimental upstream, so only the few fields needed here are decoded and
    /// unknown fields are ignored.
    /// - Parameters:
    ///   - dumpHelp: The dump-help JSON text.
    ///   - metadata: Each command's metadata, keyed by command path (e.g. `greet`).
    /// - Returns: The schema.
    /// - Throws: ``CLIError`` with ``CLIExitCode/internalError`` when the dump can't be decoded.
    public static func build(dumpHelp: String, metadata: [String: CommandMetadata]) throws(CLIError) -> CommandSchema {
        let tool: DumpTool
        do {
            tool = try JSONDecoder().decode(DumpTool.self, from: Data(dumpHelp.utf8))
        } catch {
            throw CLIError(.internalError, message: "Could not read the command tree: \(error).")
        }
        var commands: [Command] = []
        var globalFlags: [String: Flag] = [:]
        collect(tool.command.subcommands, prefix: "", metadata: metadata, into: &commands, globals: &globalFlags)
        return CommandSchema(
            schemaVersion: 1,
            name: tool.command.commandName,
            version: toolVersion,
            globalFlags: globalFlags.values.sorted { $0.name < $1.name },
            commands: commands,
            exitCodes: CLIExitCode.allCases.map { ExitCodeInfo(code: $0.rawValue, name: $0.name, meaning: $0.meaning) }
        )
    }

    /// Selects one command (progressive discovery: `schema greet`).
    /// - Parameter name: The command path.
    /// - Returns: A schema holding only that command.
    /// - Throws: ``CLIError`` with ``CLIExitCode/notFound`` when no command has that name.
    public func only(_ name: String) throws(CLIError) -> CommandSchema {
        let selected = commands.filter { $0.name == name }
        guard !selected.isEmpty else {
            throw CLIError(
                .notFound,
                message: "No command named '\(name)'.",
                hint: "Run `\(self.name) schema` to list the commands.",
                details: ["available": .array(commands.map { .string($0.name) })]
            )
        }
        return CommandSchema(
            schemaVersion: schemaVersion,
            name: self.name,
            version: version,
            globalFlags: globalFlags,
            commands: selected,
            exitCodes: exitCodes
        )
    }

    private static func collect(
        _ dumped: [DumpCommand],
        prefix: String,
        metadata: [String: CommandMetadata],
        into commands: inout [Command],
        globals: inout [String: Flag]
    ) {
        // `help` is swift-argument-parser's synthesized subcommand, not part of the tool's surface.
        for command in dumped where command.shouldDisplay && command.commandName != "help" {
            let path = prefix.isEmpty ? command.commandName : "\(prefix) \(command.commandName)"
            var args: [Argument] = []
            var flags: [Flag] = []
            for argument in command.arguments where argument.shouldDisplay {
                if argument.kind == "positional" {
                    args.append(argument.asArgument())
                    continue
                }
                guard let flag = argument.asFlag() else { continue }
                if let long = argument.longName, GlobalOptions.longNames.contains(long) {
                    globals[flag.name] = flag
                } else {
                    flags.append(flag)
                }
            }
            let info = metadata[path]
            commands.append(
                Command(
                    name: path,
                    summary: command.abstract ?? "",
                    args: args,
                    flags: flags,
                    exitCodes: (info?.exitCodes ?? CommandMetadata(safety: .readOnly).exitCodes).map(\.rawValue),
                    safety: info?.safety
                )
            )
            collect(command.subcommands, prefix: path, metadata: metadata, into: &commands, globals: &globals)
        }
    }
}

// MARK: - swift-argument-parser dump-help (the subset this tool reads)

private struct DumpTool: Decodable {
    let command: DumpCommand
}

private struct DumpCommand: Decodable {
    private enum CodingKeys: String, CodingKey {
        case commandName, abstract, shouldDisplay, arguments, subcommands
    }

    let commandName: String
    let abstract: String?
    let shouldDisplay: Bool
    let arguments: [DumpArgument]
    let subcommands: [DumpCommand]

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        commandName = try container.decode(String.self, forKey: .commandName)
        abstract = try container.decodeIfPresent(String.self, forKey: .abstract)
        shouldDisplay = try container.decodeIfPresent(Bool.self, forKey: .shouldDisplay) ?? true
        arguments = try container.decodeIfPresent([DumpArgument].self, forKey: .arguments) ?? []
        subcommands = try container.decodeIfPresent([DumpCommand].self, forKey: .subcommands) ?? []
    }
}

private struct DumpArgument: Decodable {
    struct Name: Decodable {
        let kind: String
        let name: String

        var spelled: String { kind == "short" ? "-\(name)" : "--\(name)" }
    }

    let kind: String
    let shouldDisplay: Bool
    let isOptional: Bool
    let isRepeating: Bool
    let names: [Name]?
    let preferredName: Name?
    let valueName: String?
    let defaultValue: String?
    let allValues: [String]?
    let abstract: String?

    var longName: String? {
        names?.first { $0.kind != "short" }?.name
    }

    func asArgument() -> CommandSchema.Argument {
        CommandSchema.Argument(
            name: valueName ?? "value",
            type: allValues == nil ? "string" : "enum",
            required: !isOptional,
            values: allValues,
            description: abstract ?? ""
        )
    }

    func asFlag() -> CommandSchema.Flag? {
        guard let preferredName else { return nil }
        if ["help", "version"].contains(preferredName.name) { return nil }
        let type: String
        if kind == "flag" {
            type = isRepeating ? "count" : "boolean"
        } else {
            type = allValues == nil ? "string" : "enum"
        }
        let aliases = (names ?? []).map(\.spelled).filter { $0 != preferredName.spelled }
        return CommandSchema.Flag(
            name: preferredName.spelled,
            aliases: aliases,
            type: type,
            required: !isOptional,
            defaultValue: defaultValue,
            values: allValues,
            description: abstract ?? ""
        )
    }
}
```

### `Sources/__Bin__Kit/GlobalOptions.swift`

```swift
import ArgumentParser

/// The flags every command accepts (CLI contract sections 1, 3 and 4), mixed in with `@OptionGroup`.
///
/// swift-argument-parser doesn't inherit options into subcommands, so each subcommand declares
/// `@OptionGroup var global: GlobalOptions` and the flags go after the subcommand name: `__bin__ greet --json`.
public struct GlobalOptions: ParsableArguments, Sendable {
    /// The long names of every global flag; `schema` lists them once under `globalFlags`.
    public static let longNames: Set<String> = [
        "format", "json", "no-color", "no-input", "quiet", "verbose", "log-format",
    ]

    /// The result format.
    @Option(name: .long, help: "Output format: text, json or jsonl.")
    public var format: OutputFormat = .text

    /// Alias for `--format json`.
    @Flag(name: .long, help: "Alias for --format json.")
    public var json = false

    /// Disables colour on both streams.
    @Flag(name: .customLong("no-color"), help: "Disable colour (also NO_COLOR=1).")
    public var noColor = false

    /// Never prompt; fail with a hint instead.
    @Flag(name: .customLong("no-input"), help: "Never prompt; fail instead (also \(Tool.environmentPrefix)NO_INPUT=1).")
    public var noInput = false

    /// Suppresses informational stderr output (never errors).
    @Flag(name: .shortAndLong, help: "Suppress informational messages on stderr.")
    public var quiet = false

    /// Adds diagnostics on stderr; repeatable.
    @Flag(name: .shortAndLong, help: "Add diagnostics on stderr (repeatable).")
    public var verbose: Int

    /// The diagnostics format.
    @Option(name: .customLong("log-format"), help: "Diagnostics format on stderr: text or json.")
    public var logFormat: LogFormat = .text

    /// Creates the default options (no flags given).
    public init() {}

    /// The effective result format: `--json` wins over `--format`.
    public var effectiveFormat: OutputFormat { json ? .json : format }
}
```

### `Sources/__Bin__Kit/Greeting.swift`

```swift
import Foundation

/// The `greet` result: the JSON document printed with `--json`.
public struct Greeting: Codable, Equatable, Sendable {
    /// The full greeting text.
    public let greeting: String
    /// The name that was greeted.
    public let name: String
    /// The output schema version.
    public let schemaVersion: Int

    /// Creates a greeting result.
    /// - Parameters:
    ///   - greeting: The full greeting text.
    ///   - name: The name that was greeted.
    public init(greeting: String, name: String) {
        self.greeting = greeting
        self.name = name
        schemaVersion = 1
    }
}

/// The pure logic behind `greet`: validate a name and build the greeting.
public struct GreetingService: Sendable {
    /// The longest accepted name, in characters.
    public static let maximumNameLength = 64

    /// Creates the service.
    public init() {}

    /// Greets a name.
    /// - Parameter name: The name to greet; surrounding whitespace is ignored.
    /// - Returns: The greeting.
    /// - Throws: ``CLIError`` with ``CLIExitCode/validation`` when the name is empty or longer than
    ///   ``maximumNameLength`` characters.
    public func greet(_ name: String) throws(CLIError) -> Greeting {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw CLIError(
                .validation, message: "The name is empty.", hint: "Pass a non-empty NAME.", code: "EMPTY_NAME")
        }
        guard trimmed.count <= Self.maximumNameLength else {
            throw CLIError(
                .validation,
                message: "The name is longer than \(Self.maximumNameLength) characters.",
                hint: "Pass a NAME of at most \(Self.maximumNameLength) characters.",
                code: "NAME_TOO_LONG",
                details: ["length": .int(trimmed.count), "maximum": .int(Self.maximumNameLength)]
            )
        }
        return Greeting(greeting: "Hello, \(trimmed)!", name: trimmed)
    }
}

/// The `greet` command's behaviour, kept out of the executable so it is unit-tested.
public enum GreetAction {
    /// Resolves the name (argument, `-` for one stdin line, or a prompt), greets it and writes the result.
    /// - Parameters:
    ///   - name: The NAME operand, `-`, or `nil` when omitted.
    ///   - session: The invocation's output policy.
    /// - Throws: ``CLIError`` (usage when no name can be obtained, validation for a bad name) or ``StreamError``.
    public static func run(name: String?, session: Session) throws {
        let resolved: String
        if name == "-" {
            resolved = session.context.stdin.readLine() ?? ""
        } else if let name {
            resolved = name
        } else if let answer = session.prompt("Name") {
            resolved = answer
        } else {
            throw CLIError(
                .usage,
                message: "Missing NAME.",
                hint: "Pass NAME, e.g. `\(Tool.name) greet Ada`, or `-` to read it from stdin.",
                code: "MISSING_NAME"
            )
        }
        session.diagnostics.debug("greeting \(resolved.count) character(s)")
        let greeting = try GreetingService().greet(resolved)
        switch session.format {
        case .text:
            try session.writeLine("Hello, \(session.outputStyle.bold(greeting.name))!")
        case .json:
            try session.writeJSON(greeting)
        case .jsonl:
            try session.writeJSON(greeting)
            try session.writeJSON(Summary(total: 1))
        }
    }
}

/// The final line of every `jsonl` stream: marks a complete, untruncated result.
public struct Summary: Encodable, Equatable, Sendable {
    /// Where to continue, or `nil` when there is nothing more.
    public let nextCursor: String?
    /// The output schema version.
    public let schemaVersion: Int
    /// How many records were written.
    public let total: Int
    /// Always `summary`.
    public let type: String

    /// Creates a summary line.
    /// - Parameters:
    ///   - total: How many records were written.
    ///   - nextCursor: Where to continue, or `nil`.
    public init(total: Int, nextCursor: String? = nil) {
        self.total = total
        self.nextCursor = nextCursor
        schemaVersion = 1
        type = "summary"
    }

    /// Encodes the summary, writing `nextCursor` as an explicit `null`.
    /// - Parameter encoder: The encoder.
    /// - Throws: Any encoder error.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(nextCursor, forKey: .nextCursor)
        try container.encode(schemaVersion, forKey: .schemaVersion)
        try container.encode(total, forKey: .total)
        try container.encode(type, forKey: .type)
    }

    private enum CodingKeys: String, CodingKey {
        case nextCursor
        case schemaVersion
        case total
        case type
    }
}
```

### `Sources/__Bin__Kit/JSONValue.swift`

```swift
/// A JSON value used for the free-form `details` object of the error envelope.
public enum JSONValue: Encodable, Equatable, Sendable {
    /// A JSON string.
    case string(String)
    /// A JSON integer.
    case int(Int)
    /// A JSON boolean.
    case bool(Bool)
    /// A JSON array.
    case array([JSONValue])
    /// A JSON object.
    case object([String: JSONValue])
    /// JSON `null`.
    case null

    /// Encodes the wrapped value as its plain JSON form.
    /// - Parameter encoder: The encoder to write to.
    /// - Throws: Any error raised by the encoder.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
}
```

### `Sources/__Bin__Kit/Output.swift`

```swift
import ArgumentParser
import Foundation

/// The result format selected with `--format` (or `--json`).
public enum OutputFormat: String, CaseIterable, ExpressibleByArgument, Sendable {
    /// Human-readable text (the default).
    case text
    /// Exactly one JSON document on stdout.
    case json
    /// One JSON object per line, ending with a summary line.
    case jsonl

    /// Whether the format is machine-readable (`json` or `jsonl`).
    public var isMachineReadable: Bool { self != .text }

    /// Best-effort detection from raw arguments, for errors raised before parsing succeeded.
    ///
    /// Looks at `--json`, `--format <value>` and `--format=<value>`, stopping at `--`.
    /// - Parameter arguments: The command-line arguments, without the executable path.
    /// - Returns: The format the caller asked for, or ``text``.
    public static func detect(in arguments: [String]) -> OutputFormat {
        var detected = OutputFormat.text
        var index = arguments.startIndex
        while index < arguments.endIndex {
            let argument = arguments[index]
            if argument == "--" { break }
            if argument == "--json" {
                detected = .json
            } else if argument == "--format", index + 1 < arguments.endIndex {
                detected = OutputFormat(rawValue: arguments[index + 1]) ?? detected
                index += 1
            } else if argument.hasPrefix("--format=") {
                detected = OutputFormat(rawValue: String(argument.dropFirst("--format=".count))) ?? detected
            }
            index += 1
        }
        return detected
    }
}

/// The diagnostic (stderr) format selected with `--log-format`.
public enum LogFormat: String, CaseIterable, ExpressibleByArgument, Sendable {
    /// `tool: message` lines.
    case text
    /// One `{"level":…,"message":…}` object per line.
    case json
}

/// Deterministic JSON: sorted keys, compact separators, unescaped slashes — same input, same bytes.
public enum JSONOutput {
    /// Encodes a value as one line of JSON (no trailing newline).
    /// - Parameter value: The value to encode.
    /// - Returns: The JSON text.
    /// - Throws: ``CLIError`` with ``CLIExitCode/internalError`` when the value can't be encoded.
    public static func encode(_ value: some Encodable) throws(CLIError) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data: Data
        do {
            data = try encoder.encode(value)
        } catch {
            throw CLIError(.internalError, message: "Could not encode the output as JSON: \(error).")
        }
        // JSONEncoder always produces valid UTF-8.
        return String(bytes: data, encoding: .utf8) ?? ""
    }
}

/// The JSON error envelope written to stderr in machine mode (CLI contract section 4).
public struct ErrorEnvelope: Encodable, Equatable, Sendable {
    /// The error body.
    public struct Body: Encodable, Equatable, Sendable {
        /// The stable SCREAMING_SNAKE identifier.
        public let code: String
        /// Structured detail.
        public let details: [String: JSONValue]
        /// The process exit status.
        public let exitCode: Int32
        /// How to fix it.
        public let hint: String
        /// What went wrong.
        public let message: String
        /// Whether retrying may succeed.
        public let retryable: Bool
    }

    /// The error body.
    public let error: Body
    /// The envelope schema version.
    public let schemaVersion: Int

    /// Builds the envelope for an error.
    /// - Parameter error: The error to describe.
    public init(_ error: CLIError) {
        self.error = Body(
            code: error.code,
            details: error.details,
            exitCode: error.exitCode.rawValue,
            hint: error.hint,
            message: error.message,
            retryable: error.retryable
        )
        self.schemaVersion = 1
    }
}

/// Writes a ``CLIError`` to stderr: a JSON envelope in machine mode, `tool: …`/`hint: …` lines otherwise.
public enum ErrorReporter {
    /// Reports an error.
    ///
    /// Write failures are ignored: there is nowhere left to report them.
    /// - Parameters:
    ///   - error: The error to report.
    ///   - format: The output format the caller asked for.
    ///   - output: Where to write (standard error).
    ///   - style: The stderr colour style.
    public static func report(_ error: CLIError, format: OutputFormat, to output: any TextOutput, style: Style) {
        try? output.write(render(error, format: format, style: style))
    }

    /// Renders an error report.
    /// - Parameters:
    ///   - error: The error to render.
    ///   - format: The output format the caller asked for.
    ///   - style: The stderr colour style.
    /// - Returns: The text to write to stderr, newline-terminated.
    public static func render(_ error: CLIError, format: OutputFormat, style: Style) -> String {
        if format.isMachineReadable, let json = try? JSONOutput.encode(ErrorEnvelope(error)) {
            return json + "\n"
        }
        var lines = ["\(style.red(Tool.name + ":")) \(error.message)"]
        if case .string(let usage) = error.details["usage"] {
            lines.append("usage: \(usage)")
        }
        if !error.hint.isEmpty {
            lines.append("hint: \(error.hint)")
        }
        return lines.joined(separator: "\n") + "\n"
    }
}

/// Informational and diagnostic messages on stderr, honouring `--quiet`, `--verbose` and `--log-format`.
public struct Diagnostics: Sendable {
    private let output: any TextOutput
    private let verbosity: Int
    private let quiet: Bool
    private let format: LogFormat
    private let style: Style

    /// Creates a diagnostics writer.
    /// - Parameters:
    ///   - output: Where to write (standard error).
    ///   - verbosity: How many times `--verbose` was given.
    ///   - quiet: Whether `--quiet` was given.
    ///   - format: The `--log-format`.
    ///   - style: The stderr colour style.
    public init(output: any TextOutput, verbosity: Int, quiet: Bool, format: LogFormat, style: Style) {
        self.output = output
        self.verbosity = verbosity
        self.quiet = quiet
        self.format = format
        self.style = style
    }

    /// An informational message; suppressed by `--quiet`.
    /// - Parameter message: The message.
    public func info(_ message: String) {
        guard !quiet else { return }
        emit(level: "info", message: message, prefix: Tool.name + ":")
    }

    /// A diagnostic message; shown only with `--verbose`.
    /// - Parameter message: The message.
    public func debug(_ message: String) {
        guard verbosity > 0, !quiet else { return }
        emit(level: "debug", message: message, prefix: "debug:")
    }

    /// A warning; shown unless `--quiet`.
    /// - Parameter message: The message.
    public func warning(_ message: String) {
        guard !quiet else { return }
        emit(level: "warning", message: message, prefix: style.yellow("warning:"))
    }

    private func emit(level: String, message: String, prefix: String) {
        let line: String
        if format == .json, let json = try? JSONOutput.encode(["level": level, "message": message]) {
            line = json
        } else {
            line = "\(prefix) \(message)"
        }
        try? output.write(line + "\n")
    }
}
```

### `Sources/__Bin__Kit/Platform.swift`

```swift
#if canImport(Darwin)
    import Darwin
#elseif canImport(Glibc)
    import Glibc
#elseif canImport(Musl)
    import Musl
#endif

/// The few C library calls the tool needs, behind one `#if canImport` so every other file stays portable across
/// macOS (Darwin), glibc Linux and the fully static Linux SDK (musl).
public enum Platform {
    /// The file descriptor of standard input.
    public static let standardInput: Int32 = 0
    /// The file descriptor of standard output.
    public static let standardOutput: Int32 = 1
    /// The file descriptor of standard error.
    public static let standardError: Int32 = 2
    /// The `SIGINT` signal number.
    public static let interruptSignal: Int32 = SIGINT
    /// The `SIGTERM` signal number.
    public static let terminateSignal: Int32 = SIGTERM

    /// Whether a file descriptor refers to a terminal.
    /// - Parameter fileDescriptor: The descriptor to check.
    /// - Returns: `true` when `isatty(3)` reports a terminal.
    public static func isTerminal(_ fileDescriptor: Int32) -> Bool {
        isatty(fileDescriptor) == 1
    }

    /// Writes the whole buffer to a file descriptor, retrying on `EINTR` and partial writes.
    /// - Parameters:
    ///   - bytes: The bytes to write.
    ///   - fileDescriptor: The descriptor to write to.
    /// - Throws: ``StreamError/brokenPipe`` when the reader went away (`EPIPE`), or
    ///   ``StreamError/writeFailed(errno:)`` for any other write error.
    public static func writeAll(_ bytes: [UInt8], to fileDescriptor: Int32) throws(StreamError) {
        var offset = 0
        while offset < bytes.count {
            let written = bytes[offset...].withUnsafeBytes { buffer in
                write(fileDescriptor, buffer.baseAddress, buffer.count)
            }
            if written >= 0 {
                offset += written
                continue
            }
            let code = errno
            if code == EINTR { continue }
            if code == EPIPE { throw .brokenPipe }
            throw .writeFailed(errno: code)
        }
    }

    /// Makes writes to a closed pipe fail with `EPIPE` instead of killing the process with `SIGPIPE`.
    public static func ignoreBrokenPipeSignal() {
        signal(SIGPIPE, SIG_IGN)
    }

    /// Stops the default action of a signal so a dispatch source can observe it instead.
    /// - Parameter number: The signal number.
    public static func ignoreSignal(_ number: Int32) {
        signal(number, SIG_IGN)
    }

    /// Restores the default action of a signal.
    /// - Parameter number: The signal number.
    public static func restoreSignal(_ number: Int32) {
        signal(number, SIG_DFL)
    }

    /// Exits the process after flushing C stdio buffers and running `atexit` handlers.
    /// - Parameter status: The exit status.
    public static func exitProcess(_ status: Int32) -> Never {
        exit(status)
    }

    /// Exits the process immediately, without running any cleanup (a second Ctrl-C).
    /// - Parameter status: The exit status.
    public static func exitImmediately(_ status: Int32) -> Never {
        _exit(status)
    }
}
```

### `Sources/__Bin__Kit/SchemaAction.swift`

```swift
import ArgumentParser
import Foundation

/// The `schema` and `completion` commands' behaviour, generic over the root command so it is unit-tested.
public enum SchemaAction {
    /// The root command's `--experimental-dump-help` JSON, captured in-process.
    /// - Parameter root: The root command type.
    /// - Returns: The dump-help JSON text.
    public static func dumpHelp<Root: ParsableCommand>(of root: Root.Type) -> String {
        do {
            _ = try root.parseAsRoot(["--experimental-dump-help"])
            return ""
        } catch {
            return root.fullMessage(for: error, columns: nil)
        }
    }

    /// Every ``DescribedCommand`` below `root`, keyed by command path.
    /// - Parameter root: The root command type.
    /// - Returns: The metadata map ``CommandSchema/build(dumpHelp:metadata:)`` expects.
    public static func metadata<Root: ParsableCommand>(of root: Root.Type) -> [String: CommandMetadata] {
        var result: [String: CommandMetadata] = [:]
        func visit(_ commands: [any ParsableCommand.Type], prefix: String) {
            for command in commands {
                let name = command._commandName
                let path = prefix.isEmpty ? name : "\(prefix) \(name)"
                if let described = command as? any DescribedCommand.Type {
                    result[path] = described.metadata
                }
                visit(command.configuration.subcommands, prefix: path)
            }
        }
        visit(root.configuration.subcommands, prefix: "")
        return result
    }

    /// Writes the command tree: JSON (whatever `--format`), one command when `command` is given, or the
    /// AsciiDoc CLI reference with `asciidoc`.
    /// - Parameters:
    ///   - root: The root command type.
    ///   - command: A command path to print alone, or `nil` for the whole tree.
    ///   - asciidoc: Whether to render the AsciiDoc reference instead of JSON.
    ///   - session: The invocation's output policy.
    /// - Throws: ``CLIError`` (not found, internal) or ``StreamError``.
    public static func run<Root: ParsableCommand>(
        root: Root.Type,
        command: String?,
        asciidoc: Bool,
        session: Session
    ) throws {
        var schema = try CommandSchema.build(dumpHelp: dumpHelp(of: root), metadata: metadata(of: root))
        if let command {
            schema = try schema.only(command)
        }
        if asciidoc {
            try session.context.stdout.write(CLIReference.asciidoc(for: schema))
        } else {
            try session.writeJSON(schema)
        }
    }
}

/// The shells `completion` can generate a script for (what swift-argument-parser supports).
public enum CompletionShellName: String, CaseIterable, ExpressibleByArgument, Sendable {
    /// GNU Bash.
    case bash
    /// Z shell.
    case zsh
    /// fish.
    case fish

    /// The matching swift-argument-parser shell.
    public var shell: CompletionShell {
        switch self {
        case .bash: .bash
        case .zsh: .zsh
        case .fish: .fish
        }
    }
}

/// The `completion` command's behaviour.
public enum CompletionAction {
    /// Writes the completion script for `shell`, generated from the command tree.
    /// - Parameters:
    ///   - root: The root command type.
    ///   - shell: The target shell.
    ///   - session: The invocation's output policy.
    /// - Throws: ``StreamError`` when stdout is closed.
    public static func run<Root: ParsableCommand>(
        root: Root.Type,
        shell: CompletionShellName,
        session: Session
    ) throws {
        let script = root.completionScript(for: shell.shell)
        try session.context.stdout.write(script.hasSuffix("\n") ? script : script + "\n")
    }
}

/// Renders the command tree as an AsciiDoc partial for the Antora site (`scripts/generate-docs.sh`).
public enum CLIReference {
    /// The AsciiDoc reference.
    /// - Parameter schema: The command tree.
    /// - Returns: AsciiDoc text, newline-terminated.
    public static func asciidoc(for schema: CommandSchema) -> String {
        var out: [String] = [
            "// Generated by `\(schema.name) schema --asciidoc` from the command tree. Do not edit by hand.",
            "",
            "== Global flags",
            "",
            "Every command accepts these flags after its name, e.g. `\(schema.name) <command> --json`.",
            "",
        ]
        out += flagTable(schema.globalFlags)
        for command in schema.commands {
            out += [
                "", "== `\(schema.name) \(command.name)`", "",
                command.summary.isEmpty ? "_No summary._" : command.summary, "",
            ]
            if !command.args.isEmpty {
                out += ["[cols=\"1,1,1,3\"]", "|===", "|Argument |Type |Required |Description", ""]
                for argument in command.args {
                    let required = argument.required ? "yes" : "no"
                    out.append(
                        "|`<\(argument.name)>` |\(argument.type) |\(required) |\(cell(argument.description))"
                    )
                }
                out += ["|===", ""]
            }
            if !command.flags.isEmpty {
                out += flagTable(command.flags)
                out.append("")
            }
            out.append("Exit codes: \(command.exitCodes.map(String.init).joined(separator: ", ")).")
            if let safety = command.safety {
                out += ["", "Safety: \(safetyText(safety))."]
            }
        }
        out += ["", "== Exit codes", "", "[cols=\"1,2,5\"]", "|===", "|Code |Name |Meaning", ""]
        for info in schema.exitCodes {
            out.append("|\(info.code) |`\(info.name)` |\(cell(info.meaning))")
        }
        out.append("|===")
        return out.joined(separator: "\n") + "\n"
    }

    private static func flagTable(_ flags: [CommandSchema.Flag]) -> [String] {
        var rows = ["[cols=\"2,1,1,3\"]", "|===", "|Flag |Type |Default |Description", ""]
        for flag in flags {
            let names = ([flag.name] + flag.aliases).map { "`\($0)`" }.joined(separator: ", ")
            let type = flag.values.map { "\(flag.type) (\($0.joined(separator: ", ")))" } ?? flag.type
            rows.append("|\(names) |\(type) |\(flag.defaultValue.map { "`\($0)`" } ?? "") |\(cell(flag.description))")
        }
        rows.append("|===")
        return rows
    }

    private static func safetyText(_ safety: CommandSafety) -> String {
        var traits = [safety.readOnly ? "read-only" : "writes"]
        if safety.destructive { traits.append("destructive") }
        if safety.idempotent { traits.append("idempotent") }
        if safety.openWorld { traits.append("reaches the network") }
        if safety.requiresConfirmation { traits.append("needs --yes without a terminal") }
        return traits.joined(separator: ", ")
    }

    private static func cell(_ text: String) -> String {
        text.replacingOccurrences(of: "|", with: "\\|")
    }
}
```

### `Sources/__Bin__Kit/SignalTrap.swift`

```swift
import Dispatch
import Synchronization

/// Turns SIGINT/SIGTERM into a clean exit: run cleanup once, print one line, exit 128 + signal (130/143).
///
/// A second signal while cleanup runs exits immediately. Each handled signal is ignored at the C level and
/// observed through a `DispatchSource`, so the handler runs as ordinary Swift code on a dispatch queue, not inside
/// an async-signal context.
public final class SignalTrap: Sendable {
    /// Ends the process: `(status, immediately)`; `immediately` is `true` for a repeated signal.
    public typealias Terminator = @Sendable (Int32, Bool) -> Void

    private let signals: [Int32]
    private let sources: Mutex<[any DispatchSourceSignal]>

    /// Installs the trap.
    /// - Parameters:
    ///   - signals: The signals to handle (normally SIGINT and SIGTERM).
    ///   - queue: The queue the handlers run on.
    ///   - onSignal: Cleanup to run on the first signal (receives the signal number).
    ///   - terminate: Ends the process; defaults to `exit(3)`, or `_exit(2)` for a repeated signal.
    public init(
        signals: [Int32],
        queue: DispatchQueue = DispatchQueue(label: "signal-trap"),
        onSignal: @escaping @Sendable (Int32) -> Void,
        terminate: @escaping Terminator = SignalTrap.exitProcess
    ) {
        self.signals = signals
        sources = Mutex([])
        let received = SignalCounter()
        let installed = signals.map { number -> any DispatchSourceSignal in
            Platform.ignoreSignal(number)
            let source = DispatchSource.makeSignalSource(signal: number, queue: queue)
            source.setEventHandler {
                let count = received.increment()
                let status = CLIExitCode.status(forSignal: number)
                if count > 1 {
                    terminate(status, true)
                    return
                }
                onSignal(number)
                terminate(status, false)
            }
            source.resume()
            return source
        }
        sources.withLock { $0 = installed }
    }

    /// Stops observing the signals and restores their default actions.
    public func cancel() {
        sources.withLock { sources in
            for source in sources {
                source.cancel()
            }
            sources.removeAll()
        }
        for number in signals {
            Platform.restoreSignal(number)
        }
    }

    /// The default terminator: `exit(3)` on the first signal, `_exit(2)` on a repeated one.
    /// - Parameters:
    ///   - status: The exit status.
    ///   - immediately: Whether to skip cleanup.
    public static func exitProcess(_ status: Int32, immediately: Bool) {
        if immediately {
            Platform.exitImmediately(status)
        }
        Platform.exitProcess(status)
    }
}

/// Counts delivered signals across handler invocations.
private final class SignalCounter: Sendable {
    private let count = Mutex(0)

    func increment() -> Int {
        count.withLock { count in
            count += 1
            return count
        }
    }
}
```

### `Sources/__Bin__Kit/Streams.swift`

```swift
import Synchronization

/// A failure writing to an output stream.
public enum StreamError: Error, Equatable, Sendable {
    /// The reader closed the pipe (`tool … | head -n1`); stop writing and exit quietly.
    case brokenPipe
    /// Any other write failure, with the C `errno` value.
    case writeFailed(errno: Int32)
}

/// A destination for text: standard output, standard error, or an in-memory capture in tests.
public protocol TextOutput: Sendable {
    /// Writes text exactly as given (no newline is added).
    /// - Parameter text: The text to write.
    /// - Throws: ``StreamError`` when the write fails.
    func write(_ text: String) throws(StreamError)
}

/// Writes to a file descriptor with `write(2)`, reporting a closed pipe as ``StreamError/brokenPipe``.
public struct FileDescriptorOutput: TextOutput {
    /// The descriptor written to.
    public let fileDescriptor: Int32

    /// Creates an output for a file descriptor.
    /// - Parameter fileDescriptor: The descriptor to write to (1 for stdout, 2 for stderr).
    public init(fileDescriptor: Int32) {
        self.fileDescriptor = fileDescriptor
    }

    /// Writes the UTF-8 bytes of `text`.
    /// - Parameter text: The text to write.
    /// - Throws: ``StreamError`` when the write fails.
    public func write(_ text: String) throws(StreamError) {
        try Platform.writeAll(Array(text.utf8), to: fileDescriptor)
    }
}

/// An in-memory output that records everything written to it; used by tests.
public final class CapturedOutput: TextOutput {
    private let buffer = Mutex("")
    private let failure: StreamError?

    /// Creates a capture, optionally failing every write with `failure`.
    /// - Parameter failure: The error every write throws, or `nil` to record writes.
    public init(failing failure: StreamError? = nil) {
        self.failure = failure
    }

    /// Everything written so far.
    public var text: String {
        buffer.withLock { $0 }
    }

    /// Records `text`, or throws the configured failure.
    /// - Parameter text: The text to record.
    /// - Throws: The failure passed to ``init(failing:)``, if any.
    public func write(_ text: String) throws(StreamError) {
        if let failure { throw failure }
        buffer.withLock { $0 += text }
    }
}

/// A source of input lines: standard input, or scripted lines in tests.
public protocol TextInput: Sendable {
    /// Reads one line without its trailing newline.
    /// - Returns: The line, or `nil` at end of input.
    func readLine() -> String?
}

/// Reads lines from the process's standard input.
public struct StandardInput: TextInput {
    /// Creates a standard-input reader.
    public init() {}

    /// Reads one line from standard input.
    /// - Returns: The line, or `nil` at end of input.
    public func readLine() -> String? {
        Swift.readLine(strippingNewline: true)
    }
}

/// Returns pre-recorded lines in order; used by tests.
public final class ScriptedInput: TextInput {
    private let lines: Mutex<[String]>

    /// Creates an input that yields `lines`, then end of input.
    /// - Parameter lines: The lines to return.
    public init(_ lines: [String] = []) {
        self.lines = Mutex(lines)
    }

    /// Returns the next scripted line.
    /// - Returns: The line, or `nil` when none remain.
    public func readLine() -> String? {
        lines.withLock { $0.isEmpty ? nil : $0.removeFirst() }
    }
}
```

### `Sources/__Bin__Kit/Terminal.swift`

```swift
/// Which of the three standard streams are connected to a terminal.
public struct TerminalInfo: Equatable, Sendable {
    /// Whether standard input is a terminal.
    public var stdinIsTerminal: Bool
    /// Whether standard output is a terminal.
    public var stdoutIsTerminal: Bool
    /// Whether standard error is a terminal.
    public var stderrIsTerminal: Bool

    /// Creates a terminal description.
    /// - Parameters:
    ///   - stdinIsTerminal: Whether standard input is a terminal.
    ///   - stdoutIsTerminal: Whether standard output is a terminal.
    ///   - stderrIsTerminal: Whether standard error is a terminal.
    public init(stdinIsTerminal: Bool, stdoutIsTerminal: Bool, stderrIsTerminal: Bool) {
        self.stdinIsTerminal = stdinIsTerminal
        self.stdoutIsTerminal = stdoutIsTerminal
        self.stderrIsTerminal = stderrIsTerminal
    }

    /// The state of the current process's streams, from `isatty(3)`.
    public static var current: TerminalInfo {
        TerminalInfo(
            stdinIsTerminal: Platform.isTerminal(Platform.standardInput),
            stdoutIsTerminal: Platform.isTerminal(Platform.standardOutput),
            stderrIsTerminal: Platform.isTerminal(Platform.standardError)
        )
    }

    /// No stream is a terminal (pipes, files, CI).
    public static let none = TerminalInfo(stdinIsTerminal: false, stdoutIsTerminal: false, stderrIsTerminal: false)
}

/// Decides whether a stream gets ANSI colour (CLI contract section 3).
public enum ColorPolicy {
    /// Whether to colour one stream.
    ///
    /// Colour is off when `--no-color` is given, `NO_COLOR` is set and non-empty, the output is machine-readable
    /// (`--format json|jsonl`) or `--quiet`; otherwise `FORCE_COLOR`/`CLICOLOR_FORCE` (non-empty, not `0`) force
    /// it on, `TERM=dumb` turns it off, and finally the stream must be a terminal.
    /// - Parameters:
    ///   - streamIsTerminal: Whether the stream is a terminal.
    ///   - environment: The process environment.
    ///   - noColorFlag: Whether `--no-color` was given.
    ///   - machineOutput: Whether the stream carries machine-readable output.
    /// - Returns: `true` when escape sequences may be written to the stream.
    public static func isEnabled(
        streamIsTerminal: Bool,
        environment: [String: String],
        noColorFlag: Bool,
        machineOutput: Bool
    ) -> Bool {
        if noColorFlag || machineOutput || isSet(environment["NO_COLOR"]) {
            return false
        }
        if isForced(environment["FORCE_COLOR"]) || isForced(environment["CLICOLOR_FORCE"]) {
            return true
        }
        if environment["TERM"] == "dumb" {
            return false
        }
        return streamIsTerminal
    }

    private static func isSet(_ value: String?) -> Bool {
        guard let value else { return false }
        return !value.isEmpty
    }

    private static func isForced(_ value: String?) -> Bool {
        isSet(value) && value != "0"
    }
}

/// Decides whether the tool may ask the user a question (CLI contract section 3).
public enum PromptPolicy {
    /// Whether prompting is allowed.
    ///
    /// Prompts need stdin and stderr to be terminals, and none of `--no-input`, `<PREFIX>NO_INPUT` (any value but
    /// `0`/empty) or `CI=true`.
    /// - Parameters:
    ///   - terminal: Which streams are terminals.
    ///   - environment: The process environment.
    ///   - noInputFlag: Whether `--no-input` was given.
    /// - Returns: `true` when a prompt may be shown.
    public static func canPrompt(terminal: TerminalInfo, environment: [String: String], noInputFlag: Bool) -> Bool {
        guard terminal.stdinIsTerminal, terminal.stderrIsTerminal, !noInputFlag else { return false }
        if let value = environment["\(Tool.environmentPrefix)NO_INPUT"], !value.isEmpty, value != "0" {
            return false
        }
        return environment["CI"]?.lowercased() != "true"
    }
}

/// ANSI styling that collapses to plain text when colour is disabled.
public struct Style: Equatable, Sendable {
    /// Whether escape sequences are emitted.
    public let isEnabled: Bool

    /// Creates a style.
    /// - Parameter isEnabled: Whether escape sequences are emitted.
    public init(isEnabled: Bool) {
        self.isEnabled = isEnabled
    }

    /// Bold text.
    /// - Parameter text: The text to style.
    /// - Returns: The styled text, or `text` unchanged when colour is off.
    public func bold(_ text: String) -> String {
        wrap(text, code: "1")
    }

    /// Red text, for error prefixes.
    /// - Parameter text: The text to style.
    /// - Returns: The styled text, or `text` unchanged when colour is off.
    public func red(_ text: String) -> String {
        wrap(text, code: "31")
    }

    /// Yellow text, for warning prefixes.
    /// - Parameter text: The text to style.
    /// - Returns: The styled text, or `text` unchanged when colour is off.
    public func yellow(_ text: String) -> String {
        wrap(text, code: "33")
    }

    private func wrap(_ text: String, code: String) -> String {
        isEnabled ? "\u{1B}[\(code)m\(text)\u{1B}[0m" : text
    }
}
```

### `Sources/__Bin__Kit/Tool.swift`

```swift
/// Identity constants shared by the command tree and its error/diagnostic output.
public enum Tool {
    /// The executable's name, used as the prefix of every message written to stderr.
    public static let name = "__bin__"

    /// The prefix of every environment variable the tool reads, e.g. `__BIN_ENV__NO_INPUT`.
    public static let environmentPrefix = "__BIN_ENV__"
}
```

### `Sources/__Bin__Kit/Version.swift`

```swift
/// The tool's version, printed by `--version` and embedded in the `schema` output.
///
/// Keep this a single string literal on its own line: release tooling (`iru-swift-bump-version`) rewrites it.
/// The integration branch carries a `-dev` pre-release suffix; a release drops it.
public let toolVersion = "0.1.0-dev"
```

### `Sources/__Bin__Kit/__Bin__Kit.docc/__Bin__Kit.md`

```markdown
# ``__Bin__Kit``

The logic behind the `__bin__` command-line tool, kept out of the executable so it is unit-tested.

## Overview

`__bin__` is a thin `swift-argument-parser` command tree; everything it does lives here: the exit-code table,
the JSON error envelope, the colour and prompt policies, signal and broken-pipe handling, the `schema` command
tree, and the sample `greet` logic. ``CommandRunner`` runs the tree under the agent-friendly CLI contract.

## Topics

### Running commands

- ``CommandRunner``
- ``CommandContext``
- ``Session``
- ``GlobalOptions``
- ``Tool``
- ``toolVersion``

### Exit codes and errors

- ``CLIExitCode``
- ``CLIError``
- ``ErrorEnvelope``
- ``ErrorReporter``
- ``JSONValue``

### Output and terminals

- ``OutputFormat``
- ``LogFormat``
- ``JSONOutput``
- ``Summary``
- ``Diagnostics``
- ``ColorPolicy``
- ``PromptPolicy``
- ``Style``
- ``TerminalInfo``

### Streams, signals and the platform

- ``TextOutput``
- ``FileDescriptorOutput``
- ``CapturedOutput``
- ``TextInput``
- ``StandardInput``
- ``ScriptedInput``
- ``StreamError``
- ``SignalTrap``
- ``CleanupRegistry``
- ``Platform``

### Self-description

- ``CommandSchema``
- ``CommandMetadata``
- ``CommandSafety``
- ``DescribedCommand``
- ``SchemaAction``
- ``CompletionAction``
- ``CompletionShellName``
- ``CLIReference``

### The sample command

- ``GreetingService``
- ``Greeting``
- ``GreetAction``
```
