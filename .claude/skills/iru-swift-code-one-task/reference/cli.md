# Swift command-line tools (swift-argument-parser)

How to implement a task in a Swift command-line tool — the layout `iru-setup-swift-cli` scaffolds, or any SwiftPM
package with an `.executableTarget` depending on `ArgumentParser`. Read it together with `cli-contract.md` (the
behaviour every command must keep), `code-style.md` and `testing.md`. Every snippet below was type-checked against
Swift 6.4 / swift-argument-parser 1.8.2 inside a scaffolded package.

## Recognise the shape

| Signal | Meaning |
|---|---|
| `Package.swift` with `.executableTarget(name: "<Bin>", dependencies: [.product(name: "ArgumentParser", …)])` and an `.executable(name: "<bin>", …)` product | a CLI package |
| `Sources/<Bin>Kit/` library target (+ `Version.swift`, `CLIExitCode.swift`, `CommandRunner.swift`) | the `iru-setup-swift-cli` contract layout: logic in the Kit, thin commands |
| `Tests/<Bin>KitTests/` (in-process) and `Tests/<Bin>CLITests/` (`Process` on the built binary) | the two test layers |
| `scripts/generate-docs.sh` | man page / completions / `schema.json` / Antora partial generator |

A CLI package without a Kit (all logic in the executable target) is still a CLI: follow its structure, put new logic
in a separate type the tests can reach (`@testable import <Bin>` works for executable targets), and report the
divergence rather than restructuring the package.

## Where code goes

- **`Sources/<Bin>/`** — one `AsyncParsableCommand` struct per subcommand: `configuration` (name, one-line
  `abstract` saying *when* to use it, `discussion` ending in a real `Example:` line), `@OptionGroup var global:
  GlobalOptions`, the command's own `@Argument`/`@Option`/`@Flag`s, `static let metadata = CommandMetadata(…)`
  (`DescribedCommand`), and a `run()` that is a single call into the Kit with
  `CommandContext.current.session(for: global)`. Register it in the root's `subcommands:` list.
- **`Sources/<Bin>Kit/`** — everything else: the action (`enum <Name>Action { static func run(…, session:) throws }`),
  pure logic (a `struct …Service` with typed throws), result types (`Encodable`, `schemaVersion`), new error codes.
  This is where the 80% coverage bar is measured; `Sources/<Bin>/` is only exercised end to end.
- Never touch `CommandRunner`, `CLIExitCode`'s numbers, `Platform.swift` or the envelope shape for a feature task —
  they are the contract. A new failure kind maps onto an existing exit code; finer detail goes in `CLIError.code`
  (SCREAMING_SNAKE) and `details`.

## swift-argument-parser patterns

```swift
struct ListCommand: AsyncParsableCommand, DescribedCommand {
    static let configuration = CommandConfiguration(
        commandName: "list",
        abstract: "List the configured items (read-only).",
        discussion: "Example: tool list --limit 5 --json"
    )
    static let metadata = CommandMetadata(safety: .readOnly)

    @OptionGroup var global: GlobalOptions

    @Option(help: "Maximum items to print (default 20).")
    var limit = 20

    @Option(help: "Continue after this cursor.")
    var cursor: String?

    func validate() throws {
        guard (1...500).contains(limit) else { throw ValidationError("--limit must be between 1 and 500.") }
    }

    func run() async throws {
        try await ListAction.run(limit: limit, cursor: cursor, session: CommandContext.current.session(for: global))
    }
}
```

- **Flag-shape problems** (ranges, mutually exclusive flags) → `validate()` throwing `ValidationError` → exit **2**
  with the usage line. **Operand/data problems** (a name that's empty, a file that doesn't parse) → thrown from the
  Kit as `CLIError(.validation, …)` → exit **3**. Missing resources → `.notFound` (4).
- Enumerated values: a Kit `enum …: String, CaseIterable, ExpressibleByArgument` — help and `schema` list the values,
  and a wrong value is a usage error. Library types that aren't `ExpressibleByArgument` (e.g. `CompletionShell`) get
  a Kit enum mapped onto them, never a `@retroactive` conformance.
- swift-argument-parser does **no prefix/abbreviation matching** of subcommands or long flags, and `--` ends option
  parsing (contract §5) — keep both; never add a hand-rolled argument pre-processor.
- Options are not inherited by subcommands: global flags stay in `GlobalOptions` (`@OptionGroup`) and go after the
  subcommand name. Don't add flags to the root command.
- Keep `CommandConfiguration(version: toolVersion)` — never a second version literal (`iru-swift-bump-version`
  rewrites `Version.swift` only).
- Never print with `print()` in commands or the Kit: results go through `session.writeLine`/`writeJSON`
  (stdout), messages through `session.diagnostics` (stderr), errors are **thrown**, never printed — the runner
  reports each failure exactly once (text or envelope, per `--format`).

## Implementing the contract in a new command

- **Output**: text for humans by default; `--json` → exactly one `JSONOutput` document with `schemaVersion: 1`;
  `jsonl` → one object per line plus `Summary(total:nextCursor:)` last. Lists take `--limit` (small default) and
  `--cursor`, and signal truncation (`truncated`, `nextCursor`). `JSONOutput` sorts keys — keep result types free of
  timestamps, absolute paths and colour so goldens stay byte-stable. A new field is additive; renaming/removing one
  bumps the type's `schemaVersion`.
- **Colour**: style only through `session.outputStyle`/`errorStyle` (already off for pipes, `NO_COLOR`,
  `TERM=dumb`, `--no-color`, machine output); never convey meaning by colour alone.
- **Prompts**: `session.prompt(_:)` returns `nil` when prompting isn't allowed (no TTY on stdin/stderr,
  `--no-input`, `<BIN>_NO_INPUT`, `CI=true`) — then fail fast: exit 2 for a missing value (name the flag), exit 5 for
  a confirmation.
- **Mutating commands** get `--dry-run` (exit 0, same code path up to the write, a structured plan) and `--yes`
  (`--force` only for overriding a safety check — a different flag), `metadata` with `readOnly: false` and the right
  `destructive`/`idempotent`/`requiresConfirmation`, and exit code 5 in `exitCodes`:

  ```swift
  let plan = Plan(items: names.sorted())
  if dryRun {
      try session.writeJSON(plan)
      return
  }
  guard yes || session.prompt("Delete \(plan.items.count) item(s)? [y/N]")?.lowercased() == "y" else {
      throw CLIError(
          .confirmationRequired,
          message: "Purging \(plan.items.count) item(s) needs confirmation.",
          hint: "Re-run with --yes, or with --dry-run to see the plan.",
          details: [
              "confirmCommand": .string("\(Tool.name) purge --yes " + names.joined(separator: " ")),
              "plan": .array(plan.items.map(JSONValue.string)),
          ]
      )
  }
  ```

- **Timeouts** (`--timeout`, network/long work) end as exit 6 with `retryable: true`:

  ```swift
  try await withThrowingTaskGroup(of: T.self) { group in
      group.addTask { try await operation() }
      group.addTask {
          try await Task.sleep(for: .seconds(seconds))
          throw CLIError(.retryable, message: "Timed out after \(seconds)s.", hint: "Retry, or raise --timeout.")
      }
      defer { group.cancelAll() }
      guard let result = try await group.next() else { throw CLIError(.internalError, message: "No result.") }
      return result
  }
  ```

- **Signals**: SIGINT/SIGTERM are already trapped by `CommandRunner.main` (cleanup → one stderr line → 130/143). A
  command that creates temporary files registers their removal with `session.context.cleanup.register { … }` and
  writes final files atomically (temp file + `FileManager.replaceItemAt`/rename). Don't install other signal
  handlers.
- **Broken pipe**: write through `session` (never `FileHandle.standardOutput.write(_:)` — the non-throwing API raises
  an Objective-C exception on EPIPE); a `StreamError.brokenPipe` propagating out of `run()` ends the process quietly
  with exit 0.
- **Child processes**: `Process` with `executableURL` (absolute or resolved), an `arguments` array with `--` before
  user operands, and a minimal `environment` — never `/bin/sh -c` with an interpolated string:

  ```swift
  let process = Process()
  process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
  process.arguments = ["log", "--oneline", "--", path]
  process.environment = ["PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"]
  try process.run()
  process.waitUntilExit()
  ```

- **Paths from arguments**: resolve (`URL.standardizedFileURL`, `resolvingSymlinksInPath()`) and check they stay
  under the intended root before reading or writing. **Secrets** never go in messages, envelopes, `--verbose` output
  or URLs; tokens come from `<BIN>_TOKEN` or `--token-stdin`, never an echoing prompt.
- **Startup**: no network, config writes or update checks on the default path; initialise expensive things lazily
  inside the command that needs them.
- **Self-description**: every new command conforms to `DescribedCommand` — `schema` then lists its flags, exit codes
  and safety automatically (it reads `--experimental-dump-help` in-process). If the repository commits the generated
  Antora partial (`docs/modules/ROOT/partials/cli-reference.adoc`), say in the report that
  `scripts/generate-docs.sh` must be re-run (it builds in release mode, ~1 min) — don't hand-edit the partial.

## Testing a CLI task

- **Kit tests** (`Tests/<Bin>KitTests`, Swift Testing) cover the action and logic in-process with the scaffold's
  `FakeProcess` helper (captured stdout/stderr, scripted stdin, chosen environment and `TerminalInfo`): text output,
  JSON golden (exact bytes), `jsonl` summary, each `CLIError` path with its exit code, prompt vs no-TTY behaviour, and
  colour on/off. `CommandRunner.run(<Root>.self, arguments:context:)` drives the whole tree in-process when a test
  needs parsing too (usage errors → 2, help/version on stdout).
- **CLI tests** (`Tests/<Bin>CLITests`) run the built binary through the scaffold's `ToolProcess.run(_:environment:stdin:)`
  and assert stdout, stderr and the exit status — add one per exit code the new command can produce from the command
  line, plus a JSON golden. The binary is found next to the test bundle (`Bundle(for:)` on a test-target class on
  macOS — `Bundle.main` is `swiftpm-testing-helper` there — and `CommandLine.arguments[0]`'s directory on Linux);
  `<BIN>_TEST_BINARY=<path>` points the suite at a release or packaged binary. Child processes get a minimal
  environment (`PATH`, `LANG`) so the developer's `NO_COLOR`/`CI` never leak in, and stdout/stderr go to temporary
  files (no pipe deadlock).
- These targets need no simulator, no `.simulator-test.lock` and no containers; `swift build --build-tests` is the
  compile-check.

## Linux portability

The tool ships for macOS and Linux (glibc, and fully static via the Static Linux SDK's musl). Keep it building on
all three:

- System calls go through the Kit's `Platform.swift` only — the one file with `#if canImport(Darwin) / Glibc / Musl`
  imports. Don't `import Darwin` elsewhere; a test that needs `kill`/`SIGINT` repeats the same `#if canImport` block.
- Foundation on Linux is swift-corelibs-foundation / swift-foundation: `Process`, `FileManager`, `JSONEncoder`,
  `ProcessInfo`, `URL` work; Apple-only frameworks (`os.Logger`, `OSAllocatedUnfairLock`, `NSWorkspace`, Combine,
  Security/Keychain) don't — use `Synchronization.Mutex`, plain stderr diagnostics, and `#if canImport(…)` around any
  optional Apple-only integration. `URLSession` lives in `FoundationNetworking` on Linux (`#if canImport(FoundationNetworking)`).
- `Bundle` behaves differently (no `.xctest` bundle on Linux) — never locate resources through `Bundle.main` in the
  Kit; SwiftPM `resources:` use `Bundle.module`.
- **Static Linux SDK** (`swift build -c release --swift-sdk <arch>-swift-linux-musl`): needs the swift.org toolchain
  whose version matches the installed SDK exactly (Xcode's toolchain can't use it), links everything statically
  (larger binaries; `FoundationInternationalization` adds ICU data), and musl's resolver and `dlopen` behave
  differently from glibc's — avoid runtime plug-in loading, and test DNS/locale-sensitive code on the static build.
  When the CI matrix builds it, a musl-only compile error is a real failure of the task.
- Windows is not a target of this layout (`Platform.swift` has no ucrt branch); don't add Windows-only code.

## Sources

- irurueta docs: ai/cli-for-agents/frameworks-swift-and-dotnet.adoc (swift-argument-parser, the 64 → 2 override,
  `--experimental-dump-help`, SwiftTUI's status), streams-and-exit-codes.adoc, tty-colour-and-interactivity.adoc,
  machine-readable-output.adoc, safety-and-idempotency.adoc, self-describing-help.adoc, testing-clis.adoc,
  security.adoc, configuration-and-auth.adoc — https://www.irurueta.com/ai/cli-for-agents/index.html
- irurueta docs: apps/apple/testing-with-swift-testing.adoc (Swift Testing suites, parameterized tests, test doubles)
- swift-argument-parser documentation: <https://swiftpackageindex.com/apple/swift-argument-parser/documentation/argumentparser>,
  sources <https://github.com/apple/swift-argument-parser> (`ParsableCommand.main`, `exitCode(for:)`,
  `fullMessage(for:columns:)`, `completionScript(for:)`, `CompletionShell`, the `GenerateManual` plugin)
- Swift Static Linux SDK: <https://www.swift.org/documentation/articles/static-linux-getting-started.html>
- Swift on Linux / Foundation: <https://github.com/swiftlang/swift-foundation>, <https://github.com/swiftlang/swift-corelibs-foundation>
- Command Line Interface Guidelines: <https://clig.dev>; NO_COLOR: <https://no-color.org>
