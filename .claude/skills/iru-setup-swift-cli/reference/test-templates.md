# Test templates — `Tests/`

`__Bin__KitTests` (Swift Testing, in-process, the coverage gate) and `__Bin__CLITests` (Swift Testing, black-box:
runs the built executable with `Process` and asserts stdout, stderr and exit status for every exit code the command
line can produce — 0, 2, 3, 4, 130, 143 —, no-TTY behaviour, `NO_COLOR`/`FORCE_COLOR`, JSON golden output and a
closed stdout). Exit codes 1, 5, 6 and 7 can't be produced by the sample commands and are covered in-process by
`CommandRunnerTests` through a test-only command tree. Conventions are in `package-templates.md`.

### `Tests/__Bin__CLITests/CLITests.swift`

```swift
import Foundation
import Testing

#if canImport(Glibc)
    import Glibc
#elseif canImport(Musl)
    import Musl
#endif

/// End-to-end contract tests on the real executable: stdout, stderr and the exit status for every exit code the
/// tool can emit from the command line, no-TTY behaviour, NO_COLOR, JSON golden output, signals and EPIPE.
@Suite struct CLITests {
    @Test func versionGoesToStdoutAndExitsZero() throws {
        let result = try ToolProcess.run(["--version"])
        #expect(result.status == 0)
        #expect(result.stdout.range(of: #"^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?\n$"#, options: .regularExpression) != nil)
        #expect(result.stderr.isEmpty)
    }

    @Test(arguments: [["--help"], ["greet", "--help"], ["schema", "--help"], ["help", "greet"]])
    func helpGoesToStdoutAndExitsZero(arguments: [String]) throws {
        let result = try ToolProcess.run(arguments)
        #expect(result.status == 0)
        #expect(result.stdout.contains("USAGE:"))
        #expect(result.stderr.isEmpty)
    }

    @Test func greetTextOnStdoutOnly() throws {
        let result = try ToolProcess.run(["greet", "Ada"])
        #expect(result.status == 0)
        #expect(result.stdout == "Hello, Ada!\n")
        #expect(result.stderr.isEmpty)
    }

    @Test(arguments: [
        ["greet", "Ada", "--json"], ["greet", "Ada", "--format", "json"], ["greet", "--json", "--", "Ada"],
    ])
    func greetJSONGolden(arguments: [String]) throws {
        let result = try ToolProcess.run(arguments)
        #expect(result.status == 0)
        #expect(result.stdout == #"{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}"# + "\n")
        #expect(result.stderr.isEmpty)
    }

    @Test func greetJSONLinesEndWithASummary() throws {
        let result = try ToolProcess.run(["greet", "Ada", "--format", "jsonl"])
        #expect(result.status == 0)
        #expect(
            result.stdout == #"{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}"# + "\n"
                + #"{"nextCursor":null,"schemaVersion":1,"total":1,"type":"summary"}"# + "\n"
        )
    }

    @Test func dashReadsTheNameFromStdin() throws {
        let result = try ToolProcess.run(["greet", "-"], stdin: "Grace\n")
        #expect(result.status == 0)
        #expect(result.stdout == "Hello, Grace!\n")
    }

    @Test(arguments: [
        ["bogus"], ["greet", "--bogus"], ["completion"], ["completion", "powershell"], ["greet", "--format", "xml"],
    ])
    func usageErrorsExitTwoWithAHelpPointer(arguments: [String]) throws {
        let result = try ToolProcess.run(arguments)
        #expect(result.status == 2)
        #expect(result.stdout.isEmpty)
        #expect(result.stderr.hasPrefix("__bin__: "))
        #expect(result.stderr.contains("--help"))
    }

    @Test func missingNameWithoutATerminalFailsFastInsteadOfPrompting() throws {
        let result = try ToolProcess.run(["greet"])
        #expect(result.status == 2)
        #expect(result.stdout.isEmpty)
        #expect(result.stderr.contains("Missing NAME"))
        #expect(!result.stderr.contains("Name:"))
    }

    @Test func usageErrorInJSONModeIsAnEnvelopeOnStderr() throws {
        let result = try ToolProcess.run(["greet", "--bogus", "--json"])
        #expect(result.status == 2)
        #expect(result.stdout.isEmpty)
        let envelope = try #require(try JSONSerialization.jsonObject(with: Data(result.stderr.utf8)) as? [String: Any])
        let error = try #require(envelope["error"] as? [String: Any])
        #expect(error["code"] as? String == "USAGE")
        #expect(error["exitCode"] as? Int == 2)
        #expect(envelope["schemaVersion"] as? Int == 1)
    }

    @Test func validationErrorGolden() throws {
        let result = try ToolProcess.run(["greet", "", "--json"])
        #expect(result.status == 3)
        #expect(result.stdout.isEmpty)
        #expect(
            result.stderr
                == #"{"error":{"code":"EMPTY_NAME","details":{},"exitCode":3,"hint":"Pass a non-empty NAME.","#
                + #""message":"The name is empty.","retryable":false},"schemaVersion":1}"#
                + "\n"
        )
        let text = try ToolProcess.run(["greet", String(repeating: "x", count: 65)])
        #expect(text.status == 3)
        #expect(text.stderr.hasPrefix("__bin__: The name is longer than 64 characters.\nhint: "))
    }

    @Test func unknownSchemaCommandIsNotFound() throws {
        let result = try ToolProcess.run(["schema", "nope", "--json"])
        #expect(result.status == 4)
        #expect(result.stderr.contains(#""code":"NOT_FOUND""#))
    }

    @Test func schemaIsJSONWithExitCodesAndSafety() throws {
        let result = try ToolProcess.run(["schema"])
        #expect(result.status == 0)
        let schema = try #require(try JSONSerialization.jsonObject(with: Data(result.stdout.utf8)) as? [String: Any])
        #expect(schema["name"] as? String == "__bin__")
        let commands = try #require(schema["commands"] as? [[String: Any]])
        #expect(commands.compactMap { $0["name"] as? String } == ["greet", "completion", "schema"])
        #expect(commands.allSatisfy { $0["safety"] != nil && $0["exitCodes"] != nil })
        #expect((schema["exitCodes"] as? [[String: Any]])?.count == 10)
    }

    @Test(arguments: ["bash", "zsh", "fish"])
    func completionScriptsAreGenerated(shell: String) throws {
        let result = try ToolProcess.run(["completion", shell])
        #expect(result.status == 0)
        #expect(result.stdout.contains("__bin__"))
        #expect(result.stderr.isEmpty)
    }

    @Test func noEscapeBytesWhenPiped() throws {
        for arguments in [["greet", "Ada"], ["bogus"], ["greet", ""]] {
            let result = try ToolProcess.run(arguments)
            #expect(!result.stdout.contains("\u{1B}"))
            #expect(!result.stderr.contains("\u{1B}"))
        }
    }

    @Test func forcedColourIsStillDisabledByNoColorAndTheFlag() throws {
        let forced = try ToolProcess.run(["greet", "Ada"], environment: ["FORCE_COLOR": "1"])
        #expect(forced.stdout.contains("\u{1B}["))
        let noColor = try ToolProcess.run(["greet", "Ada"], environment: ["FORCE_COLOR": "1", "NO_COLOR": "1"])
        #expect(noColor.stdout == "Hello, Ada!\n")
        let flag = try ToolProcess.run(["greet", "Ada", "--no-color"], environment: ["FORCE_COLOR": "1"])
        #expect(flag.stdout == "Hello, Ada!\n")
        let json = try ToolProcess.run(["greet", "Ada", "--json"], environment: ["FORCE_COLOR": "1"])
        #expect(!json.stdout.contains("\u{1B}"))
    }

    @Test func quietAndVerboseOnlyChangeStderr() throws {
        let verbose = try ToolProcess.run(["greet", "Ada", "-v"])
        #expect(verbose.stdout == "Hello, Ada!\n")
        #expect(verbose.stderr == "debug: greeting 3 character(s)\n")
        let json = try ToolProcess.run(["greet", "Ada", "-v", "--log-format", "json"])
        #expect(json.stderr == #"{"level":"debug","message":"greeting 3 character(s)"}"# + "\n")
        let quiet = try ToolProcess.run(["greet", "Ada", "-v", "--quiet"])
        #expect(quiet.stdout == "Hello, Ada!\n")
        #expect(quiet.stderr.isEmpty)
    }

    @Test func closedStdoutExitsQuietly() throws {
        let pipe = Pipe()
        try pipe.fileHandleForReading.close()
        let process = ToolProcess.configured(["completion", "zsh"])
        let errors = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: errors.path, contents: nil)
        defer { try? FileManager.default.removeItem(at: errors) }
        process.standardOutput = pipe.fileHandleForWriting
        process.standardError = try FileHandle(forWritingTo: errors)
        try process.run()
        process.waitUntilExit()
        #expect(process.terminationStatus == 0)
        #expect(try String(contentsOf: errors, encoding: .utf8).isEmpty)
    }

    @Test(arguments: [(SIGINT, Int32(130), "interrupted"), (SIGTERM, Int32(143), "terminated")])
    func signalsExitWith128PlusTheSignal(signal: Int32, status: Int32, word: String) async throws {
        // `greet -` blocks reading stdin, which stays open until the signal arrives.
        let input = Pipe()
        let errors = Pipe()
        let process = ToolProcess.configured(["greet", "-"])
        process.standardInput = input
        process.standardOutput = FileHandle.nullDevice
        process.standardError = errors
        try process.run()
        try await Task.sleep(for: .milliseconds(500))
        kill(process.processIdentifier, signal)
        let message = errors.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        try input.fileHandleForWriting.close()
        #expect(process.terminationStatus == status)
        #expect(String(bytes: message, encoding: .utf8) == "__bin__: \(word)\n")
    }
}
```

### `Tests/__Bin__CLITests/ToolProcess.swift`

```swift
import Foundation

/// Runs the built `__bin__` executable as a child process: the black-box layer of the test suite.
///
/// The binary is `$__BIN_ENV__TEST_BINARY` when set (to run this same suite against a packaged artifact), otherwise
/// the `__bin__` product in SwiftPM's products directory (`swift test` builds it first because this test target
/// depends on the executable target).
enum ToolProcess {
    struct Result {
        let status: Int32
        let stdout: String
        let stderr: String
    }

    private final class BundleMarker {}

    static let executable: URL = {
        if let override = ProcessInfo.processInfo.environment["__BIN_ENV__TEST_BINARY"], !override.isEmpty {
            return URL(fileURLWithPath: override)
        }
        #if os(macOS)
            // `swift test` runs Swift Testing through swiftpm-testing-helper, so Bundle.main is the toolchain's; the
            // test bundle (<products>/__Bin__CLITests.xctest) is found through a class it defines.
            let products = Bundle(for: BundleMarker.self).bundleURL.deletingLastPathComponent()
        #else
            // On Linux the test runner is <products>/<package>PackageTests.xctest, an executable in the products dir.
            let products = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
        #endif
        return products.appendingPathComponent("__bin__")
    }()

    /// A minimal environment, so colour/CI variables from the developer's shell or the CI runner don't leak in.
    static let baseEnvironment: [String: String] = [
        "PATH": "/usr/bin:/bin",
        "LANG": "C.UTF-8",
    ]

    /// Runs the tool to completion. stdout and stderr go to temporary files (never a pipe deadlock); stdin is the
    /// given text, or empty.
    static func run(_ arguments: [String], environment: [String: String] = [:], stdin: String = "") throws -> Result {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let paths = ["stdin", "stdout", "stderr"].map { directory.appendingPathComponent($0) }
        try Data(stdin.utf8).write(to: paths[0])
        FileManager.default.createFile(atPath: paths[1].path, contents: nil)
        FileManager.default.createFile(atPath: paths[2].path, contents: nil)

        let process = configured(arguments, environment: environment)
        process.standardInput = try FileHandle(forReadingFrom: paths[0])
        process.standardOutput = try FileHandle(forWritingTo: paths[1])
        process.standardError = try FileHandle(forWritingTo: paths[2])
        try process.run()
        process.waitUntilExit()
        return Result(
            status: process.terminationStatus,
            stdout: try String(contentsOf: paths[1], encoding: .utf8),
            stderr: try String(contentsOf: paths[2], encoding: .utf8)
        )
    }

    /// A process for the tool, not yet started.
    static func configured(_ arguments: [String], environment: [String: String] = [:]) -> Process {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        var base = baseEnvironment
        // `swift test --enable-code-coverage` sets LLVM_PROFILE_FILE; forwarding it merges the instrumented child's
        // profile into SwiftPM's coverage data (so Sources/__Bin__/ is measured too) instead of leaving a stray
        // default.profraw in the working directory.
        if let profile = ProcessInfo.processInfo.environment["LLVM_PROFILE_FILE"] {
            base["LLVM_PROFILE_FILE"] = profile
        }
        process.environment = base.merging(environment) { _, new in new }
        return process
    }
}
```

### `Tests/__Bin__KitTests/CommandRunnerTests.swift`

```swift
import ArgumentParser
import Testing

@testable import __Bin__Kit

@Suite struct CommandRunnerTests {
    private func run(_ arguments: [String], process: FakeProcess = FakeProcess()) async -> (Int32, FakeProcess) {
        (await CommandRunner.run(TestRoot.self, arguments: arguments, context: process.context), process)
    }

    @Test func successRunsTheCommandWithTheInjectedContext() async {
        let (status, process) = await run(["succeed"])
        #expect(status == 0)
        #expect(process.out == "ok\n")
        #expect(process.err.isEmpty)
    }

    @Test func synchronousCommandsRunToo() async {
        let (status, process) = await run(["sync"])
        #expect(status == 0)
        #expect(process.out == "sync\n")
    }

    @Test(arguments: [["--help"], ["succeed", "--help"], ["--version"], ["--experimental-dump-help"]])
    func helpVersionAndDumpAreResultsOnStdout(arguments: [String]) async {
        let (status, process) = await run(arguments)
        #expect(status == 0)
        #expect(!process.out.isEmpty)
        #expect(process.out.hasSuffix("\n"))
        #expect(process.err.isEmpty)
    }

    @Test func versionIsPrintedVerbatim() async {
        let (_, process) = await run(["--version"])
        #expect(process.out == "9.9.9\n")
    }

    @Test(arguments: [["bogus"], ["succeed", "--bogus"], ["fail"], ["succeed", "--mode", "xml"]])
    func usageErrorsExitTwoNotSixtyFour(arguments: [String]) async {
        let (status, process) = await run(arguments)
        #expect(status == CLIExitCode.usage.rawValue)
        #expect(process.out.isEmpty)
        #expect(process.err.hasPrefix("testtool:") || process.err.hasPrefix("\(Tool.name):"))
        #expect(process.err.contains("hint: See '"))
    }

    @Test func usageErrorInJSONModeIsAnEnvelope() async {
        let (status, process) = await run(["succeed", "--bogus", "--json"])
        #expect(status == 2)
        #expect(process.err.hasPrefix(#"{"error":{"code":"USAGE","details":{"usage":"#))
        #expect(process.err.contains(#""exitCode":2"#))
    }

    @Test func cliErrorsMapToTheirExitCode() async {
        let (status, process) = await run(["fail", "--kind", "validation", "--format", "json"])
        #expect(status == 3)
        #expect(process.err.contains(#""code":"VALIDATION""#))
        let (retry, retryProcess) = await run(["fail", "--kind", "retry", "--json"])
        #expect(retry == 6)
        #expect(retryProcess.err.contains(#""retryable":true"#))
    }

    @Test func unexpectedErrorsAreInternal() async {
        let (status, process) = await run(["fail", "--kind", "generic"])
        #expect(status == 1)
        #expect(process.err.contains("Unexpected error"))
        #expect(process.err.contains("hint: This is a bug"))
    }

    @Test func thrownExitCodesPassThroughSilently() async {
        let (status, process) = await run(["fail", "--kind", "exit5"])
        #expect(status == 5)
        #expect(process.err.isEmpty)
    }

    @Test func brokenPipeEndsQuietly() async {
        let (status, process) = await run(["fail", "--kind", "broken-pipe"])
        #expect(status == 0)
        #expect(process.err.isEmpty)
        let closed = FakeProcess(stdout: .brokenPipe)
        let (helpStatus, helpProcess) = await run(["--help"], process: closed)
        #expect(helpStatus == 0)
        #expect(helpProcess.err.isEmpty)
    }

    @Test func noColorOnStderrWhenNotATerminalOrWhenAsked() async {
        let terminal = TerminalInfo(stdinIsTerminal: false, stdoutIsTerminal: false, stderrIsTerminal: true)
        let (_, coloured) = await run(["bogus"], process: FakeProcess(terminal: terminal))
        #expect(coloured.err.contains("\u{1B}["))
        let (_, plain) = await run(["bogus", "--no-color"], process: FakeProcess(terminal: terminal))
        #expect(!plain.err.contains("\u{1B}["))
    }
}
```

### `Tests/__Bin__KitTests/ExitCodeAndErrorTests.swift`

```swift
import Testing

@testable import __Bin__Kit

@Suite struct ExitCodeAndErrorTests {
    @Test func exitCodesMatchTheContractTable() {
        let table = Dictionary(uniqueKeysWithValues: CLIExitCode.allCases.map { ($0.name, $0.rawValue) })
        #expect(
            table == [
                "OK": 0, "INTERNAL": 1, "USAGE": 2, "VALIDATION": 3, "NOT_FOUND": 4,
                "CONFIRMATION_REQUIRED": 5, "RETRYABLE": 6, "AUTH": 7, "INTERRUPTED": 130, "TERMINATED": 143,
            ])
    }

    @Test(arguments: CLIExitCode.allCases)
    func everyCodeHasAMeaningAndAHintWhenItIsAFailure(code: CLIExitCode) {
        #expect(!code.meaning.isEmpty)
        let isActionableFailure = (1...7).contains(code.rawValue)
        #expect(code.defaultHint.isEmpty == !isActionableFailure)
    }

    @Test func signalStatusIs128PlusTheSignal() {
        #expect(CLIExitCode.status(forSignal: Platform.interruptSignal) == CLIExitCode.interrupted.rawValue)
        #expect(CLIExitCode.status(forSignal: Platform.terminateSignal) == CLIExitCode.terminated.rawValue)
    }

    @Test func errorDefaultsComeFromTheExitCode() {
        let error = CLIError(.notFound, message: "Missing.")
        #expect(error.code == "NOT_FOUND")
        #expect(error.hint == CLIExitCode.notFound.defaultHint)
        #expect(error.details.isEmpty)
        #expect(!error.retryable)
        #expect(CLIError(.retryable, message: "Later.").retryable)
    }

    @Test func errorKeepsExplicitFields() {
        let error = CLIError(
            .auth, message: "No token.", hint: "Log in.", code: "NO_TOKEN", details: ["a": .bool(true)])
        #expect(error.code == "NO_TOKEN")
        #expect(error.hint == "Log in.")
        #expect(error.details == ["a": .bool(true)])
    }
}
```

### `Tests/__Bin__KitTests/GreetingTests.swift`

```swift
import Testing

@testable import __Bin__Kit

@Suite struct GreetingTests {
    @Test func greetsATrimmedName() throws {
        #expect(try GreetingService().greet("  Ada ") == Greeting(greeting: "Hello, Ada!", name: "Ada"))
    }

    @Test func acceptsTheMaximumLength() throws {
        let name = String(repeating: "a", count: GreetingService.maximumNameLength)
        #expect(try GreetingService().greet(name).name == name)
    }

    @Test(arguments: ["", "   ", String(repeating: "a", count: GreetingService.maximumNameLength + 1)])
    func rejectsInvalidNamesWithValidation(name: String) {
        #expect {
            try GreetingService().greet(name)
        } throws: { error in
            (error as? CLIError)?.exitCode == .validation
        }
    }

    @Test func textOutput() throws {
        let process = FakeProcess()
        try GreetAction.run(name: "Ada", session: try process.session())
        #expect(process.out == "Hello, Ada!\n")
        #expect(process.err.isEmpty)
    }

    @Test func boldNameOnATerminal() throws {
        let process = FakeProcess(
            terminal: TerminalInfo(stdinIsTerminal: false, stdoutIsTerminal: true, stderrIsTerminal: false))
        try GreetAction.run(name: "Ada", session: try process.session())
        #expect(process.out == "Hello, \u{1B}[1mAda\u{1B}[0m!\n")
    }

    @Test func jsonGolden() throws {
        let process = FakeProcess()
        try GreetAction.run(name: "Ada", session: try process.session(["--json"]))
        #expect(process.out == #"{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}"# + "\n")
    }

    @Test func jsonlEndsWithASummary() throws {
        let process = FakeProcess()
        try GreetAction.run(name: "Ada", session: try process.session(["--format", "jsonl", "-v"]))
        let lines = process.out.split(separator: "\n")
        #expect(lines.count == 2)
        #expect(lines.last == #"{"nextCursor":null,"schemaVersion":1,"total":1,"type":"summary"}"#)
        #expect(process.err == "debug: greeting 3 character(s)\n")
    }

    @Test func dashReadsTheNameFromStdin() throws {
        let process = FakeProcess(stdin: ["Grace"])
        try GreetAction.run(name: "-", session: try process.session())
        #expect(process.out == "Hello, Grace!\n")
    }

    @Test func dashWithEmptyStdinIsAValidationError() throws {
        let process = FakeProcess()
        #expect(throws: CLIError.self) { try GreetAction.run(name: "-", session: try process.session()) }
    }

    @Test func promptsForAMissingNameOnATerminal() throws {
        let process = FakeProcess(
            stdin: ["Linus"],
            terminal: TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: false, stderrIsTerminal: true))
        try GreetAction.run(name: nil, session: try process.session())
        #expect(process.out == "Hello, Linus!\n")
        #expect(process.err == "Name: ")
    }

    @Test func missingNameWithoutATerminalIsAUsageError() throws {
        let process = FakeProcess()
        #expect {
            try GreetAction.run(name: nil, session: try process.session())
        } throws: { error in
            (error as? CLIError)?.exitCode == .usage
        }
        #expect(process.out.isEmpty)
    }
}
```

### `Tests/__Bin__KitTests/OutputTests.swift`

```swift
import Testing

@testable import __Bin__Kit

@Suite struct OutputTests {
    @Test(arguments: [
        ([], OutputFormat.text),
        (["--json"], .json),
        (["greet", "--format", "jsonl"], .jsonl),
        (["--format=json"], .json),
        (["--format", "bogus"], .text),
        (["--format"], .text),
        (["--", "--json"], .text),
    ])
    func detectsTheRequestedFormat(arguments: [String], expected: OutputFormat) {
        #expect(OutputFormat.detect(in: arguments) == expected)
    }

    @Test func machineReadableFormats() {
        #expect(!OutputFormat.text.isMachineReadable)
        #expect(OutputFormat.json.isMachineReadable)
        #expect(OutputFormat.jsonl.isMachineReadable)
    }

    @Test func jsonIsCompactSortedAndKeepsSlashes() throws {
        let value: [String: JSONValue] = [
            "b": .array([.int(1), .bool(false), .null]),
            "a": .object(["path": .string("a/b")]),
        ]
        #expect(try JSONOutput.encode(value) == #"{"a":{"path":"a/b"},"b":[1,false,null]}"#)
    }

    @Test func encodingFailureIsAnInternalError() {
        struct Broken: Encodable {
            func encode(to encoder: any Encoder) throws {
                throw CLIError(.validation, message: "nope")
            }
        }
        #expect(throws: CLIError.self) { try JSONOutput.encode(Broken()) }
    }

    @Test func errorEnvelopeGolden() throws {
        let error = CLIError(.notFound, message: "No item named 'x'.", hint: "Run `tool list`.")
        #expect(
            try JSONOutput.encode(ErrorEnvelope(error))
                == #"{"error":{"code":"NOT_FOUND","details":{},"exitCode":4,"hint":"Run `tool list`.","#
                + #""message":"No item named 'x'.","retryable":false},"schemaVersion":1}"#
        )
    }

    @Test func textReportHasPrefixUsageAndHint() {
        let error = CLIError(.usage, message: "Bad flag.", hint: "See help.", details: ["usage": .string("__bin__ x")])
        let plain = ErrorReporter.render(error, format: .text, style: Style(isEnabled: false))
        #expect(plain == "\(Tool.name): Bad flag.\nusage: __bin__ x\nhint: See help.\n")
        let coloured = ErrorReporter.render(error, format: .text, style: Style(isEnabled: true))
        #expect(coloured.hasPrefix("\u{1B}[31m\(Tool.name):\u{1B}[0m"))
    }

    @Test func textReportOmitsAnEmptyHint() {
        let error = CLIError(.internalError, message: "Boom.", hint: "")
        #expect(ErrorReporter.render(error, format: .text, style: Style(isEnabled: false)) == "\(Tool.name): Boom.\n")
    }

    @Test func machineReportIsOneEnvelopeLine() {
        let output = CapturedOutput()
        ErrorReporter.report(
            CLIError(.validation, message: "Bad."), format: .jsonl, to: output, style: Style(isEnabled: true))
        #expect(output.text.hasPrefix(#"{"error":{"code":"VALIDATION""#))
        #expect(output.text.hasSuffix("}\n"))
        #expect(!output.text.contains("\u{1B}"))
    }

    @Test func diagnosticsHonourQuietVerboseAndFormat() {
        let output = CapturedOutput()
        let quiet = Diagnostics(
            output: output, verbosity: 2, quiet: true, format: .text, style: Style(isEnabled: false))
        quiet.info("hidden")
        quiet.debug("hidden")
        quiet.warning("hidden")
        #expect(output.text.isEmpty)

        let normal = Diagnostics(
            output: output, verbosity: 0, quiet: false, format: .text, style: Style(isEnabled: false))
        normal.debug("hidden")
        normal.info("shown")
        normal.warning("careful")
        #expect(output.text == "\(Tool.name): shown\nwarning: careful\n")

        let json = CapturedOutput()
        Diagnostics(output: json, verbosity: 1, quiet: false, format: .json, style: Style(isEnabled: false)).debug("d")
        #expect(json.text == #"{"level":"debug","message":"d"}"# + "\n")
    }

    @Test func summaryLineHasExplicitNullCursor() throws {
        #expect(
            try JSONOutput.encode(Summary(total: 2))
                == #"{"nextCursor":null,"schemaVersion":1,"total":2,"type":"summary"}"#)
        #expect(try JSONOutput.encode(Summary(total: 1, nextCursor: "c2")).contains(#""nextCursor":"c2""#))
    }
}
```

### `Tests/__Bin__KitTests/SchemaTests.swift`

```swift
import ArgumentParser
import Foundation
import Testing

@testable import __Bin__Kit

@Suite struct SchemaTests {
    @Test func dumpHelpIsCapturedInProcess() {
        let dump = SchemaAction.dumpHelp(of: TestRoot.self)
        #expect(dump.contains(#""commandName" : "testtool""#))
    }

    @Test func metadataIsCollectedFromDescribedCommands() {
        let metadata = SchemaAction.metadata(of: TestRoot.self)
        #expect(Set(metadata.keys) == ["succeed", "sync"])
        #expect(metadata["sync"]?.exitCodes.contains(.confirmationRequired) == true)
    }

    @Test func schemaListsCommandsFlagsAndExitCodes() throws {
        let schema = try CommandSchema.build(
            dumpHelp: SchemaAction.dumpHelp(of: TestRoot.self),
            metadata: SchemaAction.metadata(of: TestRoot.self)
        )
        #expect(schema.schemaVersion == 1)
        #expect(schema.name == "testtool")
        #expect(schema.version == toolVersion)
        #expect(schema.commands.map(\.name) == ["succeed", "fail", "sync", "nested", "nested leaf"])
        #expect(
            schema.globalFlags.map(\.name).sorted() == [
                "--format", "--json", "--log-format", "--no-color", "--no-input", "--quiet", "--verbose",
            ])
        let verbose = try #require(schema.globalFlags.first { $0.name == "--verbose" })
        #expect(verbose.type == "count")
        #expect(verbose.aliases == ["-v"])
        let succeed = try #require(schema.commands.first { $0.name == "succeed" })
        #expect(succeed.args.map(\.name) == ["operand"])
        #expect(succeed.flags.first?.type == "enum")
        #expect(succeed.flags.first?.values == ["text", "json", "jsonl"])
        #expect(succeed.safety == .readOnly)
        let fail = try #require(schema.commands.first { $0.name == "fail" })
        #expect(fail.safety == nil)
        #expect(fail.flags.first?.required == true)
        #expect(schema.exitCodes.count == CLIExitCode.allCases.count)
    }

    @Test func onlySelectsOneCommandOrIsNotFound() throws {
        let schema = try CommandSchema.build(dumpHelp: SchemaAction.dumpHelp(of: TestRoot.self), metadata: [:])
        #expect(try schema.only("sync").commands.map(\.name) == ["sync"])
        #expect {
            try schema.only("missing")
        } throws: { error in
            (error as? CLIError)?.exitCode == .notFound
        }
    }

    @Test func invalidDumpIsAnInternalError() {
        #expect {
            try CommandSchema.build(dumpHelp: "not json", metadata: [:])
        } throws: { error in
            (error as? CLIError)?.exitCode == .internalError
        }
    }

    @Test func runPrintsJSONOrAsciiDoc() throws {
        let json = FakeProcess()
        try SchemaAction.run(root: TestRoot.self, command: "succeed", asciidoc: false, session: try json.session())
        let object = try JSONSerialization.jsonObject(with: Data(json.out.utf8)) as? [String: Any]
        #expect(object?["schemaVersion"] as? Int == 1)

        let doc = FakeProcess()
        try SchemaAction.run(root: TestRoot.self, command: nil, asciidoc: true, session: try doc.session())
        #expect(doc.out.hasPrefix("// Generated by `testtool schema --asciidoc`"))
        #expect(doc.out.contains("== `testtool sync`"))
        #expect(doc.out.contains("Safety: writes, destructive, reaches the network, needs --yes without a terminal."))
        #expect(doc.out.contains("Confirm \\| proceed."))
        #expect(doc.out.contains("|`<operand>` |string |no |An operand."))
        #expect(doc.out.contains("_No summary._"))
        #expect(doc.out.contains("|130 |`INTERRUPTED`"))
    }

    @Test(arguments: CompletionShellName.allCases)
    func completionScriptsAreGeneratedFromTheTree(shell: CompletionShellName) throws {
        let process = FakeProcess()
        try CompletionAction.run(root: TestRoot.self, shell: shell, session: try process.session())
        #expect(process.out.contains("testtool"))
        #expect(process.out.hasSuffix("\n"))
    }
}
```

### `Tests/__Bin__KitTests/SessionTests.swift`

```swift
import Testing

@testable import __Bin__Kit

@Suite struct SessionTests {
    private let allTerminals = TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: true, stderrIsTerminal: true)

    @Test func jsonAliasWinsOverFormat() throws {
        let process = FakeProcess()
        #expect(try process.session(["--format", "jsonl", "--json"]).format == .json)
        #expect(try process.session(["--format", "jsonl"]).format == .jsonl)
    }

    @Test func colourFollowsTheTerminalAndFlags() throws {
        let tty = FakeProcess(terminal: allTerminals)
        #expect(try tty.session().outputStyle.isEnabled)
        #expect(try tty.session().errorStyle.isEnabled)
        #expect(try !tty.session(["--no-color"]).outputStyle.isEnabled)
        #expect(try !tty.session(["--json"]).outputStyle.isEnabled)
        #expect(try !tty.session(["--log-format", "json"]).errorStyle.isEnabled)
        #expect(try !FakeProcess().session().outputStyle.isEnabled)
    }

    @Test func promptsOnlyOnATerminal() throws {
        let tty = FakeProcess(stdin: ["  Ada  "], terminal: allTerminals)
        #expect(try tty.session().prompt("Name") == "Ada")
        #expect(tty.err == "Name: ")
        #expect(try tty.session(["--no-input"]).prompt("Name") == nil)
        #expect(try FakeProcess(stdin: ["Ada"]).session().prompt("Name") == nil)
    }

    @Test func writesLinesAndJSON() throws {
        let process = FakeProcess()
        let session = try process.session()
        try session.writeLine("text")
        try session.writeJSON(["k": 1])
        #expect(process.out == "text\n{\"k\":1}\n")
    }

    @Test func taskLocalContextDefaultsToTheLiveProcess() {
        #expect(CommandContext.current.stdout is FileDescriptorOutput)
    }
}
```

### `Tests/__Bin__KitTests/SignalTrapTests.swift`

```swift
import Synchronization
import Testing

@testable import __Bin__Kit

#if canImport(Darwin)
    import Darwin
#elseif canImport(Glibc)
    import Glibc
#elseif canImport(Musl)
    import Musl
#endif

@Suite(.serialized) struct SignalTrapTests {
    private final class Recorder: Sendable {
        let events = Mutex<[String]>([])

        func record(_ event: String) {
            events.withLock { $0.append(event) }
        }

        func wait(for count: Int) async -> [String] {
            for _ in 0..<200 {
                let snapshot = events.withLock { $0 }
                if snapshot.count >= count { return snapshot }
                try? await Task.sleep(for: .milliseconds(10))
            }
            return events.withLock { $0 }
        }
    }

    @Test func firstSignalCleansUpThenExitsSecondExitsImmediately() async {
        let recorder = Recorder()
        let trap = SignalTrap(
            signals: [SIGUSR1],
            onSignal: { recorder.record("cleanup \($0)") },
            terminate: { status, immediately in recorder.record("exit \(status) \(immediately)") }
        )
        defer { trap.cancel() }
        kill(getpid(), SIGUSR1)
        _ = await recorder.wait(for: 2)
        kill(getpid(), SIGUSR1)
        let events = await recorder.wait(for: 3)
        #expect(events == ["cleanup \(SIGUSR1)", "exit \(128 + SIGUSR1) false", "exit \(128 + SIGUSR1) true"])
    }
}
```

### `Tests/__Bin__KitTests/StreamTests.swift`

```swift
import Foundation
import Testing

@testable import __Bin__Kit

@Suite struct StreamTests {
    @Test func fileDescriptorOutputWritesAllBytes() throws {
        let pipe = Pipe()
        let output = FileDescriptorOutput(fileDescriptor: pipe.fileHandleForWriting.fileDescriptor)
        try output.write("héllo\n")
        try pipe.fileHandleForWriting.close()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        #expect(String(bytes: data, encoding: .utf8) == "héllo\n")
    }

    @Test func closedReaderIsABrokenPipe() throws {
        Platform.ignoreBrokenPipeSignal()
        let pipe = Pipe()
        try pipe.fileHandleForReading.close()
        let output = FileDescriptorOutput(fileDescriptor: pipe.fileHandleForWriting.fileDescriptor)
        #expect(throws: StreamError.brokenPipe) { try output.write("x") }
    }

    @Test func otherWriteErrorsCarryErrno() {
        #expect(throws: StreamError.self) { try FileDescriptorOutput(fileDescriptor: -1).write("x") }
    }

    @Test func capturedOutputRecordsOrFails() {
        let output = CapturedOutput()
        try? output.write("a")
        try? output.write("b")
        #expect(output.text == "ab")
        #expect(throws: StreamError.brokenPipe) { try CapturedOutput(failing: .brokenPipe).write("x") }
    }

    @Test func scriptedInputReturnsLinesThenNil() {
        let input = ScriptedInput(["one", "two"])
        #expect(input.readLine() == "one")
        #expect(input.readLine() == "two")
        #expect(input.readLine() == nil)
    }

    @Test func cleanupRunsOnceMostRecentFirst() {
        let registry = CleanupRegistry()
        let log = CapturedOutput()
        registry.register { try? log.write("first ") }
        registry.register { try? log.write("second ") }
        registry.runAll()
        registry.runAll()
        #expect(log.text == "second first ")
    }

    @Test func liveContextUsesTheStandardDescriptors() throws {
        let context = CommandContext.live()
        #expect((context.stdout as? FileDescriptorOutput)?.fileDescriptor == Platform.standardOutput)
        #expect((context.stderr as? FileDescriptorOutput)?.fileDescriptor == Platform.standardError)
        #expect(context.stdin is StandardInput)
        #expect(!context.environment.isEmpty)
    }
}
```

### `Tests/__Bin__KitTests/TerminalTests.swift`

```swift
import Testing

@testable import __Bin__Kit

@Suite struct TerminalTests {
    @Test(arguments: [
        // (tty, env, --no-color, machine, expected)
        (true, [String: String](), false, false, true),
        (false, [:], false, false, false),
        (true, ["NO_COLOR": "1"], false, false, false),
        (true, ["NO_COLOR": ""], false, false, true),
        (true, [:], true, false, false),
        (true, [:], false, true, false),
        (true, ["TERM": "dumb"], false, false, false),
        (false, ["FORCE_COLOR": "1"], false, false, true),
        (false, ["CLICOLOR_FORCE": "1"], false, false, true),
        (false, ["FORCE_COLOR": "0"], false, false, false),
        (false, ["FORCE_COLOR": "1", "NO_COLOR": "1"], false, false, false),
        (false, ["FORCE_COLOR": "1"], true, false, false),
    ])
    func colourPolicy(tty: Bool, environment: [String: String], noColor: Bool, machine: Bool, expected: Bool) {
        #expect(
            ColorPolicy.isEnabled(
                streamIsTerminal: tty, environment: environment, noColorFlag: noColor, machineOutput: machine)
                == expected
        )
    }

    @Test(arguments: [
        (
            TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: false, stderrIsTerminal: true), [String: String](),
            false, true
        ),
        (TerminalInfo(stdinIsTerminal: false, stdoutIsTerminal: true, stderrIsTerminal: true), [:], false, false),
        (TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: true, stderrIsTerminal: false), [:], false, false),
        (TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: true, stderrIsTerminal: true), [:], true, false),
        (
            TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: true, stderrIsTerminal: true),
            ["\(Tool.environmentPrefix)NO_INPUT": "1"], false, false
        ),
        (
            TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: true, stderrIsTerminal: true),
            ["\(Tool.environmentPrefix)NO_INPUT": "0"], false, true
        ),
        (
            TerminalInfo(stdinIsTerminal: true, stdoutIsTerminal: true, stderrIsTerminal: true), ["CI": "true"], false,
            false
        ),
    ])
    func promptPolicy(terminal: TerminalInfo, environment: [String: String], noInput: Bool, expected: Bool) {
        #expect(PromptPolicy.canPrompt(terminal: terminal, environment: environment, noInputFlag: noInput) == expected)
    }

    @Test func styleWrapsOnlyWhenEnabled() {
        let enabled = Style(isEnabled: true)
        #expect(enabled.bold("x") == "\u{1B}[1mx\u{1B}[0m")
        #expect(enabled.red("x") == "\u{1B}[31mx\u{1B}[0m")
        #expect(enabled.yellow("x") == "\u{1B}[33mx\u{1B}[0m")
        let disabled = Style(isEnabled: false)
        #expect(disabled.bold("x") == "x")
        #expect(disabled.red("x") == "x")
    }

    @Test func liveTerminalStateIsReadable() {
        #expect(!Platform.isTerminal(-1))
        _ = TerminalInfo.current
    }
}
```

### `Tests/__Bin__KitTests/TestSupport.swift`

```swift
import ArgumentParser
import __Bin__Kit

/// An in-memory process: captured stdout/stderr, scripted stdin, a chosen environment and terminal state.
struct FakeProcess {
    let stdout = CapturedOutput()
    let stderr = CapturedOutput()
    let context: CommandContext

    init(
        stdin: [String] = [],
        environment: [String: String] = [:],
        terminal: TerminalInfo = .none,
        stdout failing: StreamError? = nil
    ) {
        let stdout = failing.map { CapturedOutput(failing: $0) } ?? CapturedOutput()
        self.init(stdout: stdout, stdin: stdin, environment: environment, terminal: terminal)
    }

    private init(stdout: CapturedOutput, stdin: [String], environment: [String: String], terminal: TerminalInfo) {
        let stderr = CapturedOutput()
        context = CommandContext(
            stdout: stdout,
            stderr: stderr,
            stdin: ScriptedInput(stdin),
            environment: environment,
            terminal: terminal
        )
        self.stdoutCapture = stdout
        self.stderrCapture = stderr
    }

    private let stdoutCapture: CapturedOutput
    private let stderrCapture: CapturedOutput

    var out: String { stdoutCapture.text }
    var err: String { stderrCapture.text }

    func session(_ arguments: [String] = []) throws -> Session {
        context.session(for: try GlobalOptions.parse(arguments))
    }
}

/// A small command tree exercising every path of `CommandRunner`.
struct TestRoot: AsyncParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "testtool",
        abstract: "A test tool.",
        version: "9.9.9",
        subcommands: [Succeed.self, Fail.self, SyncCommand.self, Nested.self]
    )
}

struct Succeed: AsyncParsableCommand, DescribedCommand {
    static let configuration = CommandConfiguration(commandName: "succeed", abstract: "Writes ok.")
    static let metadata = CommandMetadata(safety: .readOnly)

    @OptionGroup var global: GlobalOptions

    @Option(help: "A choice.") var mode: OutputFormat = .text

    @Argument(help: "An operand.") var operand: String?

    func run() async throws {
        try CommandContext.current.session(for: global).writeLine("ok")
    }
}

struct Fail: AsyncParsableCommand {
    enum Kind: String, ExpressibleByArgument {
        case validation, generic, exit5
        case brokenPipe = "broken-pipe"
        case retry
    }

    struct GenericError: Error {}

    static let configuration = CommandConfiguration(commandName: "fail", abstract: "Fails on purpose.")

    @OptionGroup var global: GlobalOptions

    @Option var kind: Kind

    func run() async throws {
        switch kind {
        case .validation: throw CLIError(.validation, message: "Bad data.")
        case .retry: throw CLIError(.retryable, message: "Try later.")
        case .generic: throw GenericError()
        case .exit5: throw ExitCode(5)
        case .brokenPipe: throw StreamError.brokenPipe
        }
    }
}

struct SyncCommand: ParsableCommand, DescribedCommand {
    static let configuration = CommandConfiguration(
        commandName: "sync", abstract: "A synchronous command.", shouldDisplay: true)
    static let metadata = CommandMetadata(
        safety: CommandSafety(
            readOnly: false, destructive: true, idempotent: false, openWorld: true, requiresConfirmation: true),
        exitCodes: [.confirmationRequired]
    )

    @Flag(help: "Confirm | proceed.") var yes = false

    func run() throws {
        try CommandContext.current.stdout.write("sync\n")
    }
}

struct Nested: ParsableCommand {
    static let configuration = CommandConfiguration(commandName: "nested", subcommands: [Leaf.self])

    struct Leaf: ParsableCommand {
        static let configuration = CommandConfiguration(commandName: "leaf", abstract: "")
        @Flag(name: .shortAndLong) var count: Int
    }
}
```
