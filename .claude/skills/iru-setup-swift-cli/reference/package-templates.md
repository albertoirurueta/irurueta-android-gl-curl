# Package templates — manifest, quality config, docs generator, Sonar

Templates for the files at the package root, plus the helper that renders every `*-templates.md` file of this
directory. Conventions (shared by every reference file of this skill):

- A heading ``### `<path>` `` is followed by exactly one fenced block: the file's content. A heading suffixed
  ` (condition)` is written only when the condition holds.
- `// >>> <condition>` / `# >>> <condition>` … `// <<<` / `# <<<` lines open and close a block written only when the
  condition holds (comma-separated conditions must all hold); the marker lines are never written.
- Conditions: `license` (a license other than "No license" was chosen), `sonar` (`sonar` ≠ `none`),
  `sonar-organization` (a `sonar.organization` key is written — `sonar: cloud`, or self-hosted with organizations).
- Placeholders are `__Token__` (Swift uses `<…>` for generics): `__Bin__` (PascalCase of `bin-name`, the module
  prefix — `Greeter`), `__bin__` (`bin-name` — `greeter`), `__BIN_ENV__` (environment prefix, `bin-name`
  upper-cased with `-` → `_`, plus a trailing `_` — `GREETER_`), `__PackageName__`, `__Description__` (escaped for a
  Swift string literal in `.swift` files), `__MacOSVersion__` (a `major.minor` string such as `15.0` or `26.0` — the string form works under every tools-version, while a named case like `.v26` needs a newer `swift-tools-version`), `__ToolsVersion__` (`6.0` unless `swift-version` says otherwise), `__ArgumentParserVersion__`,
  `__DoccPluginVersion__`, `__SonarProjectKey__`, `__SonarOrganization__`, `__SonarHostUrl__`. Every `.swift` file
  gets the license header (`LicenseHeader` value) prepended when `license` holds.

### `render-templates.py` (helper)

Never written to the repository: extract it into a scratch directory **outside** the repository and run
`python3 -I <scratch>/render-templates.py <this skill's reference dir> <scratch>/staging <scratch>/values.json`.
`values.json` holds every placeholder above (without the underscores) plus `"conditions": [...]` and
`"LicenseHeader": "<the header text, or empty>"`. It prints the relative path of every file it wrote.

```python
#!/usr/bin/env python3
"""Render iru-setup-swift-cli's reference templates into a staging directory.

Usage: python3 -I render-templates.py <reference-dir> <staging-dir> <values.json>

Every `### `<path>`` heading (optionally suffixed `(condition)`) in reference/*-templates.md is followed by one fenced
block: that block becomes <staging-dir>/<path>. `// >>> cond` / `# >>> cond` ... `// <<<` / `# <<<` lines open and
close blocks written only when `cond` is in values["conditions"]; the marker lines are never written. `__Token__`
placeholders come from values; an unresolved placeholder is an error. Swift files get values["LicenseHeader"]
prepended (when non-empty), and `__Description__` is escaped for a Swift string literal in them.
"""
import json
import os
import re
import sys

HEADING = re.compile(r"^### `([^`]+)`(?: \(([^)]+)\))?\s*$")
MARKER_OPEN = re.compile(r"^\s*(?://|#) >>> (.+?)\s*$")
MARKER_CLOSE = re.compile(r"^\s*(?://|#) <<<\s*$")
PLACEHOLDER = re.compile(r"__[A-Za-z][A-Za-z_]*?__")


def holds(condition, conditions):
    return all(part.strip() in conditions for part in condition.split(","))


def sections(path):
    lines = open(path, encoding="utf-8").read().split("\n")
    index = 0
    while index < len(lines):
        match = HEADING.match(lines[index])
        index += 1
        if not match:
            continue
        while index < len(lines) and not lines[index].startswith("```"):
            index += 1
        index += 1
        body = []
        while index < len(lines) and lines[index] != "```":
            body.append(lines[index])
            index += 1
        index += 1
        yield match.group(1), match.group(2), body


def apply_markers(body, conditions, where):
    out, stack = [], []
    for line in body:
        opened = MARKER_OPEN.match(line)
        if opened:
            stack.append(holds(opened.group(1), conditions))
            continue
        if MARKER_CLOSE.match(line):
            if not stack:
                sys.exit(f"unbalanced <<< in {where}")
            stack.pop()
            continue
        if all(stack):
            out.append(line)
    if stack:
        sys.exit(f"unclosed >>> in {where}")
    return out


def substitute(text, values, swift):
    def replace(match):
        key = match.group(0)[2:-2]
        if key not in values:
            sys.exit(f"unresolved placeholder {match.group(0)}")
        value = str(values[key])
        if swift and key == "Description":
            value = value.replace("\\", "\\\\").replace('"', '\\"')
        return value

    return PLACEHOLDER.sub(replace, text)


def main():
    reference, staging, values_path = sys.argv[1:4]
    values = json.load(open(values_path, encoding="utf-8"))
    conditions = set(values.get("conditions", []))
    written = []
    for name in sorted(os.listdir(reference)):
        if not name.endswith("-templates.md"):
            continue
        for raw_path, condition, body in sections(os.path.join(reference, name)):
            if condition and not holds(condition, conditions):
                continue
            path = substitute(raw_path, values, False)
            swift = path.endswith(".swift")
            text = "\n".join(apply_markers(body, conditions, f"{name}:{raw_path}")) + "\n"
            text = substitute(text, values, swift)
            if swift and values.get("LicenseHeader"):
                text = values["LicenseHeader"].rstrip("\n") + "\n\n" + text
            target = os.path.join(staging, path)
            os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(text)
            if path.endswith(".sh"):
                os.chmod(target, 0o755)
            written.append(path)
    print("\n".join(written))


if __name__ == "__main__":
    main()
```

### `Package.swift`

```swift
// swift-tools-version: __ToolsVersion__
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "__PackageName__",
    platforms: [
        .macOS("__MacOSVersion__")
    ],
    products: [
        .executable(
            name: "__bin__",
            targets: ["__Bin__"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-argument-parser", from: "__ArgumentParserVersion__"),
        .package(url: "https://github.com/swiftlang/swift-docc-plugin", from: "__DoccPluginVersion__"),
    ],
    targets: [
        .executableTarget(
            name: "__Bin__",
            dependencies: [
                "__Bin__Kit",
                .product(name: "ArgumentParser", package: "swift-argument-parser"),
            ]
        ),
        .target(
            name: "__Bin__Kit",
            dependencies: [
                .product(name: "ArgumentParser", package: "swift-argument-parser")
            ]
        ),
        .testTarget(
            name: "__Bin__KitTests",
            dependencies: ["__Bin__Kit"]
        ),
        .testTarget(
            name: "__Bin__CLITests",
            dependencies: ["__Bin__"]
        ),
    ],
    swiftLanguageModes: [.v6]
)
```

`Package(...)`'s argument order (`name`, `platforms`, `products`, `dependencies`, `targets`, `swiftLanguageModes`)
is compiler-enforced. The `.macOS` floor is 15.0 because `Synchronization.Mutex` (used by the Kit's thread-safe
captures and signal counter) is macOS 15+; Linux needs no `platforms` entry. `swift-docc-plugin` is only a
build-time plugin (DocC for the Kit); `generate-manual` comes from swift-argument-parser itself.

### `.swift-format`

```json
{
  "version": 1,
  "lineLength": 120,
  "indentation": {
    "spaces": 4
  },
  "tabWidth": 4,
  "rules": {
    "AllPublicDeclarationsHaveDocumentation": true,
    "AlwaysUseLiteralForEmptyCollectionInit": true,
    "AlwaysUseLowerCamelCase": true,
    "AmbiguousTrailingClosureOverload": true,
    "AvoidRetroactiveConformances": true,
    "BeginDocumentationCommentWithOneLineSummary": true,
    "DoNotUseSemicolons": true,
    "DontRepeatTypeInStaticProperties": true,
    "FileScopedDeclarationPrivacy": true,
    "FullyIndirectEnum": true,
    "GroupNumericLiterals": true,
    "IdentifiersMustBeASCII": true,
    "NeverForceUnwrap": true,
    "NeverUseForceTry": true,
    "NeverUseImplicitlyUnwrappedOptionals": true,
    "NoAccessLevelOnExtensionDeclaration": true,
    "NoAssignmentInExpressions": true,
    "NoBlockComments": true,
    "NoCasesWithOnlyFallthrough": true,
    "NoEmptyTrailingClosureParentheses": true,
    "NoLabelsInCasePatterns": true,
    "NoLeadingUnderscores": true,
    "NoParensAroundConditions": true,
    "NoPlaygroundLiterals": true,
    "NoVoidReturnOnFunctionSignature": true,
    "OmitExplicitReturns": false,
    "OneCasePerLine": true,
    "OneVariableDeclarationPerLine": true,
    "OnlyOneTrailingClosureArgument": true,
    "OrderedImports": true,
    "ReplaceForEachWithForLoop": true,
    "ReturnVoidInsteadOfEmptyTuple": true,
    "TypeNamesShouldBeCapitalized": true,
    "UseEarlyExits": true,
    "UseExplicitNilCheckInConditions": true,
    "UseLetInEveryBoundCaseVariable": true,
    "UseShorthandTypeNames": true,
    "UseSingleLinePropertyGetter": true,
    "UseSynthesizedInitializer": true,
    "UseTripleSlashForDocumentationComments": true,
    "UseWhereClausesInForLoops": true,
    "ValidateDocumentationComments": true
  }
}
```

The toolchain's bundled formatter (`swift format lint --strict --recursive Sources Tests Package.swift`) picks it
up from the package root.

### `.swiftlint.yml`

```yaml
opt_in_rules:
  - array_init
  - closure_end_indentation
  - closure_spacing
  - collection_alignment
  - contains_over_filter_count
  - contains_over_filter_is_empty
  - contains_over_first_not_nil
  - discouraged_optional_boolean
  - empty_count
  - empty_string
  - explicit_init
  - fatal_error_message
# >>> license
  - file_header
# <<<
  - first_where
  - force_unwrapping
  - implicitly_unwrapped_optional
  - last_where
  - legacy_random
  - literal_expression_end_indentation
  - multiline_arguments
  - multiline_parameters
  - operator_usage_whitespace
  - overridden_super_call
  - pattern_matching_keywords
  - prohibited_super_call
  - redundant_nil_coalescing
  - redundant_type_annotation
  - sorted_first_last
  - strict_fileprivate
  - toggle_bool
  - unneeded_parentheses_in_closure_argument
  - vertical_whitespace_closing_braces
  - vertical_whitespace_opening_braces
  - yoda_condition

# >>> license
file_header:
  required_pattern: |
    \/\/
    \/\/ Copyright \(C\) \d{4} .*
    \/\/
# <<<

line_length:
  warning: 120
  error: 150

excluded:
  - .build

analyzer_rules:
  - unused_import

identifier_name:
  excluded:
    - ok
    - id

nesting:
  type_level: 2

# swift-format owns layout (it adds trailing commas); SwiftLint checks the rest.
disabled_rules:
  - trailing_comma
```

`file_header` is opt-in in SwiftLint, so it is both listed in `opt_in_rules` and configured (license only) —
configuring it without enabling it only prints a warning. `trailing_comma` is disabled because `swift format` *adds* trailing commas to multi-line collections — with both
enabled the two tools fight. `unused_import` is an analyzer rule (`swiftlint analyze`), so it sits under
`analyzer_rules`. `identifier_name` excludes `ok` (the `CLIExitCode.ok` case named after the contract table) and
`nesting` allows two levels for `CommandSchema.Flag.CodingKeys`.

### `scripts/generate-docs.sh`

```bash
#!/usr/bin/env bash
# Regenerates the man page, shell completion scripts, schema and AsciiDoc CLI reference from the command tree.
# Never edit the outputs by hand. Usage: scripts/generate-docs.sh [output-dir]   (default: dist)
set -euo pipefail
cd "$(dirname "$0")/.."

BIN=__bin__
TARGET=__Bin__
OUT="${1:-dist}"
# Reproducible: the man page date is the last commit's date, rendered in the C locale.
export LC_ALL=C
DATE="$(git log -1 --format=%cs 2>/dev/null || date -u +%F)"

swift build -c release --product "$BIN"
EXE="$(swift build -c release --show-bin-path)/$BIN"

rm -rf "$OUT/man" "$OUT/completions"
mkdir -p "$OUT/man" "$OUT/completions"

# swift-argument-parser's GenerateManual command plugin (it builds in release mode and reads --experimental-dump-help).
swift package plugin generate-manual --date "$DATE"
cp ".build/plugins/GenerateManual/outputs/$TARGET/$BIN.1" "$OUT/man/"

"$EXE" completion bash > "$OUT/completions/$BIN.bash"
"$EXE" completion zsh > "$OUT/completions/_$BIN"
"$EXE" completion fish > "$OUT/completions/$BIN.fish"
"$EXE" schema > "$OUT/schema.json"

# The Antora partial exists only once iru-setup-antora has created the docs site.
if [ -f docs/antora.yml ]; then
  mkdir -p docs/modules/ROOT/partials
  "$EXE" schema --asciidoc > docs/modules/ROOT/partials/cli-reference.adoc
fi

echo "Generated $OUT/man/$BIN.1, $OUT/completions/{$BIN.bash,_$BIN,$BIN.fish}, $OUT/schema.json"
```

### `sonar-project.properties` (sonar)

```properties
sonar.projectKey=__SonarProjectKey__
# >>> sonar-organization
sonar.organization=__SonarOrganization__
# <<<
sonar.host.url=__SonarHostUrl__

sonar.sources=Sources
sonar.tests=Tests
sonar.swift.file.suffixes=.swift

sonar.coverageReportPaths=sonarqube-generic-coverage.xml
sonar.swift.swiftLint.reportPaths=swiftlint.json
```

`sonarqube-generic-coverage.xml` and `swiftlint.json` are produced by the CI workflow
(`iru-setup-cli-github-workflows`), not by this scaffold.
