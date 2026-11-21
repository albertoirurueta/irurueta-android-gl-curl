---
name: iru-react-native-bump-version
description: "/iru-react-native-bump-version <new-version> - sets a React Native app's version (Expo or bare Metro toolchain) to the exact value given, in package.json, the app config and the native projects. Optional args: sync-files, next-version, dry-run, toolchain. Reports old/new values and files touched."
model: haiku
---

# React Native Bump Version

Set a React Native app's version to an exact value across every file that needs to agree with it. A React Native
app has **two** versions: the npm one in `package.json` (what `iru-release`, git tags, `CHANGELOG.md`, and README
mentions follow) and the **store-facing** one the stores and devices see — `app.json`/`app.config.*` for an Expo
app, the native `android/` + `ios/` projects for a bare React Native CLI app. This skill keeps both in step under
one convention (Step 5) and never computes a version itself — it applies exactly the version string it's given.

Two toolchains, one rule set (the same detection every React Native skill in this catalog uses):

- **`expo`** — the Expo framework (`create-expo-app`, Continuous Native Generation/`npx expo prebuild`, EAS). The
  native projects, if present at all, are generated from `app.json`/`app.config.*`, so the app config is the only
  place to write the version; `android/`/`ios/` are never edited for this toolchain (`npx expo prebuild` would
  overwrite them).
- **`metro`** — a bare React Native Community CLI app (`npx @react-native-community/cli@latest init`) with
  committed `android/` and `ios/` projects that it owns directly. (Expo apps are bundled by Metro too — "metro"
  names the toolchain users pick, not the only one that uses Metro.)

## Step 0 — Resolve inputs

Parse the invocation:

- The positional argument is `<new-version>` — the exact target version string (e.g. `1.4.0`, `1.4.1-dev.0`). If
  it's missing, ask the user for it before doing anything else; don't guess or compute one here.
- `args` (`key: value` lines), if present:
  - `sync-files: yes|no` — whether to also update README/Antora version mentions (Step 8). If omitted, ask once
    with `AskUserQuestion` after Step 2 confirms whether such mentions actually exist; skip asking if none do.
  - `next-version: <upcoming-version>` — the upcoming pre-release version (e.g. `1.5.0-dev.0`) that README/Antora
    "Latest snapshot"/"Current development version"-style markers should show after a release, while "Latest
    release" markers show `<new-version>`. Only meaningful with `sync-files: yes`; `iru-release` passes it so a
    release never regresses a development marker to the release value (Step 8).
  - `dry-run: yes|no` — default `no`. When `yes`, run every step through composing the new file contents and
    showing the diff (Step 7), then stop — write nothing, and say so plainly in the report.
  - `toolchain: expo|metro` — overrides Step 2's detection (e.g. a repository mid-migration).
  - `package: <name-or-path>` — accepted for parity with `iru-typescript-bump-version` (which forwards its own
    arguments here unchanged); points at the app's `package.json` when the app isn't at the repository root (a
    monorepo `apps/mobile/`). Every path below is then relative to that directory.

Reject, before touching anything, a `<new-version>` that is obviously not attempting a version at all (empty
string, or contains whitespace) with a short message asking for a valid value — Step 1 validates the actual shape.

## Step 1 — Validate the version string

`npm version <new-version> --no-git-tag-version` (Step 4) validates semver itself and fails with `npm error
Invalid version: <value>`, so there's no separate semver check. Also check the value against this catalog's
convention (Step 5) — `X.Y.Z` or `X.Y.Z-dev.N`:

```bash
python3 -c 'import re,sys; sys.exit(0 if re.fullmatch(r"\d+\.\d+\.\d+(-dev\.\d+)?", sys.argv[1]) else 1)' "<new-version>"
```

A non-matching but valid semver string (`1.4.0-rc.1`, `1.4.0+5`) is flagged and confirmed with the user rather than
refused — but note that **any** value containing `-` or `+` is treated as a pre-release in Step 6 and never reaches
the store-facing files, since neither the App Store's `CFBundleShortVersionString` (dot-separated integers only) nor
an Expo store version accepts one.

## Step 2 — Detect the toolchain and survey the files

Honor `args: toolchain` if given; otherwise read `package.json`:

- `expo` in `dependencies` → **expo** — even when `android/`/`ios/` exist (prebuild/CNG output).
- `react-native` in `dependencies` without `expo` → **metro**, on that signal alone (the catalog's detection rule,
  as in `iru-explore`). A `metro.config.js`, committed `android/` + `ios/`, or `@react-native-community/cli` in
  `devDependencies` only corroborate it — a monorepo package whose CLI devDependency is hoisted to the root, or
  whose native projects live elsewhere, is still metro. When `android/app/build.gradle(.kts)` or
  `ios/*.xcodeproj` can't be found, Step 6 bumps `package.json` and reports each missing native file instead of
  guessing a path.
- Neither `react-native` nor `expo` in `dependencies` → this isn't a React Native app; tell the user to use
  `iru-typescript-bump-version` and stop. (A React Native *library* — `react-native` only in `peerDependencies` — is
  an ordinary npm package and belongs there too.)

Then survey, so Step 6 neither misses a file nor double-bumps one:

- **expo**: `app.json` (with an `expo` key), `app.config.ts`/`.js`/`.mjs`/`.cjs`, and `eas.json`. Read
  `eas.json`'s `cli.appVersionSource` (default `local`) and whether any `build.<profile>.autoIncrement` is set:
  - `appVersionSource: "remote"` → **EAS owns the build numbers** (stored on EAS's servers;
    `iru-setup-react-native-app` scaffolds exactly this with `autoIncrement: true` on `production`). Never write
    `ios.buildNumber`/`android.versionCode` — `eas build:version:set`/`eas build:version:sync` are the only
    sanctioned ways to change them.
  - local source **with** `autoIncrement` → EAS increments the local value in the app config itself on each build,
    so bumping it here too would double-count — also leave it alone (and say so).
  - local source without `autoIncrement`, or no `eas.json` → this skill increments the build numbers that are
    present (Step 6); absent ones are reported, not created (Expo then defaults them to `1`).
- **metro**: `android/app/build.gradle` (Groovy — the template's `versionCode 1` / `versionName "1.0"`, no `=`) or
  `android/app/build.gradle.kts` (`versionCode = 1` / `versionName = "1.0"`); `ios/*.xcodeproj/project.pbxproj`
  (excluding `ios/Pods/Pods.xcodeproj`), and every `INFOPLIST_FILE` it names. Survey with:

  ```bash
  grep -n 'versionCode\|versionName' android/app/build.gradle* 2>/dev/null
  grep -n 'MARKETING_VERSION\|CURRENT_PROJECT_VERSION\|INFOPLIST_FILE' ios/*.xcodeproj/project.pbxproj
  grep -n -A1 'CFBundleShortVersionString\|CFBundleVersion' ios/*/Info.plist
  ```

  Verified on a fresh React Native CLI 20.2 / React Native 0.87.1 TypeScript app: `versionCode 1` and
  `versionName "1.0"` in `android/app/build.gradle`; `MARKETING_VERSION = 1.0;` and `CURRENT_PROJECT_VERSION = 1;`
  in both the Debug and Release build configurations of `ios/<App>.xcodeproj/project.pbxproj`; and `Info.plist`
  routing both keys through the build settings (`<string>$(MARKETING_VERSION)</string>`,
  `<string>$(CURRENT_PROJECT_VERSION)</string>`), so the plist itself needs no edit. A missing `android/app` or
  `ios/*.xcodeproj` is reported ("Android/iOS project not found — not edited"), not fatal: some apps target one
  platform only.

## Step 3 — Read the current values

Read every current value before changing anything, so the report shows old → new even if a later step fails:

```bash
node -p "require('./package.json').version"
# expo — the resolved values after app.json + app.config.* are merged (verified on Expo SDK 57):
npx expo config --type public --json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const c=JSON.parse(s);console.log(c.version, c.ios?.buildNumber, c.android?.versionCode)})'
# metro — Step 2's grep lines are the current values
```

`expo config` evaluates a dynamic `app.config.ts`, so it is also how Step 7 proves the rewrite took effect.

## Step 4 — Update `package.json` and the lockfile

Exactly as `iru-typescript-bump-version`'s Step 4 (read it for the full per-package-manager table): from the app
directory, `npm version <new-version> --no-git-tag-version` (npm — updates `package.json` **and**
`package-lock.json`; verified on both scaffolds), `pnpm version <new-version> --no-git-tag-version` (pnpm), or
`yarn version --new-version <new-version> --no-git-tag-version` / `yarn version <new-version>` (Yarn Classic/Berry —
unverified locally). **Always** pass `--no-git-tag-version`: this skill never commits or tags. If the command exits
non-zero, stop and show the error — don't hand-edit `package.json` instead. On `dry-run: yes`, don't run it; Step
6's script shows the `package.json` diff instead (the lockfile change is then only described, not shown).

This step runs for every value, release or pre-release.

## Step 5 — The React Native pre-release convention (for `iru-release` and other callers)

The npm convention (`iru-typescript-bump-version`'s Step 5) applies to `package.json`, with one extra rule for the
store-facing files — this section is the authoritative statement of it for both toolchains (it used to live, for
Expo only, in `iru-typescript-bump-version`):

- **Current development version**: `package.json` carries `x.y.z-dev.N` (e.g. `1.4.0-dev.0`). The store-facing
  version keeps the **last released** value — `expo.version` (expo) or `versionName`/`MARKETING_VERSION` (metro) —
  because a `-dev.N` string isn't a valid store version (`CFBundleShortVersionString` must be dot-separated
  integers). Build numbers don't move either.
- **Cutting a release**: strip the suffix — `x.y.z-dev.N` → `x.y.z`. Call this skill with `x.y.z`; it sets
  `package.json` **and** the store-facing version to `x.y.z`, and increments each build number this skill owns by
  exactly 1: metro `versionCode` and `CURRENT_PROJECT_VERSION` (every build configuration), expo
  `ios.buildNumber`/`android.versionCode` only when EAS doesn't own them (Step 2). This is the one call that
  changes what a store sees — mirroring `iru-android-bump-version`'s rule of burning a `versionCode` only for a
  build a distribution channel will actually receive.
- **Opening the next development cycle**: `x.y.z` → `x.y.(z+1)-dev.0` (or a minor bump, at the caller's choice).
  Call this skill again with that value; it touches `package.json` + lockfile only.
- **Starting the convention**: both templates start on a plain release value (`create-expo-app` writes
  `"version": "1.0.0"`, the React Native CLI template `"version": "0.0.1"`), which `iru-release` reads as "nothing to
  release". Call this skill once with the first development value (e.g. `1.0.0-dev.0`) to enter the cycle; the
  store-facing values stay as scaffolded until the first release.
- A store build that must ship more than once for the same `x.y.z` (a rejected review, a hotfix of the same
  marketing version) needs a build-number bump without a version change — that's EAS's `autoIncrement` (expo) or
  a manual `versionCode`/`CURRENT_PROJECT_VERSION` edit (metro); calling this skill with the same `x.y.z` again
  would also do it, and its report says so, but it's not something `iru-release` does on its own.

## Step 6 — Rewrite the app config / native version files

Run this script from the app directory, with the toolchain from Step 2. Arguments: app directory, `<new-version>`,
toolchain, `dry-run` (`yes`/`no`), and whether to include `package.json` (`yes` only on a dry run, since Step 4 has
already written it otherwise). It decides release vs. pre-release itself (`-`/`+` in the value → pre-release →
store-facing files untouched), edits literals in place without reformatting anything, re-parses every JSON file
it touched to prove only the intended key changed, prints a unified diff per file (computed inside Python, so no
trailing-newline artefacts from shell command substitution — the quirk `iru-swift-bump-version`'s Step 7 records),
and ends with a one-line-per-change summary:

```bash
python3 - . "<new-version>" "<expo|metro>" "<yes|no>" "<yes|no>" <<'PY'
import difflib, glob, json, os, re, sys

root, new_version, toolchain, dry_run, with_pkg = sys.argv[1:6]
dry_run = dry_run == "yes"
release = "-" not in new_version and "+" not in new_version
changes, notes = {}, []


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def stage(path, new_text):
    old = changes[path][0] if path in changes else read(path)
    if new_text != old:
        changes[path] = (old, new_text)


def current(path):
    return changes[path][1] if path in changes else read(path)


def eas_owns_build_numbers():
    p = os.path.join(root, "eas.json")
    if not os.path.exists(p):
        return False, "no eas.json"
    eas = json.loads(read(p))
    source = eas.get("cli", {}).get("appVersionSource", "local")
    auto = any(isinstance(b, dict) and b.get("autoIncrement") for b in eas.get("build", {}).values())
    if source == "remote":
        return True, "eas.json cli.appVersionSource is remote"
    if auto:
        return True, "eas.json local appVersionSource with autoIncrement (EAS writes the build number at build time)"
    return False, f"eas.json appVersionSource {source}, no autoIncrement"


def bump_json_literal(path, key_path, pattern, render):
    """Rewrite one literal in a JSON file without reformatting it, then re-parse to prove only that key changed."""
    text = current(path)
    data = json.loads(text)
    node = data
    for k in key_path[:-1]:
        node = node.get(k, {}) if isinstance(node, dict) else {}
    if key_path[-1] not in node:
        return None, None
    old_value = node[key_path[-1]]
    new_value = render(old_value)
    new_text, n = re.subn(pattern(old_value), lambda m: m.group(1) + json.dumps(new_value), text, count=1)
    check = json.loads(new_text)
    node2 = check
    for k in key_path[:-1]:
        node2 = node2[k]
    if n != 1 or node2[key_path[-1]] != new_value:
        sys.exit(f"could not rewrite {'.'.join(key_path)} in {path} safely")
    node2[key_path[-1]] = old_value
    if check != data:
        sys.exit(f"rewriting {'.'.join(key_path)} in {path} touched another key — aborting")
    stage(path, new_text)
    return old_value, new_value


def lit(key):
    return lambda old: r'("%s"\s*:\s*)%s' % (key, re.escape(json.dumps(old)))


if with_pkg == "yes":
    p = os.path.join(root, "package.json")
    o, n = bump_json_literal(p, ["version"], lit("version"), lambda _: new_version)
    notes.append(f"package.json version: {o} -> {n}")

if toolchain == "expo":
    if not release:
        notes.append("expo: pre-release value — app.json/app.config version and build numbers left untouched")
    else:
        eas_owned, why = eas_owns_build_numbers()
        app_json = os.path.join(root, "app.json")
        dyn = [p for p in (os.path.join(root, f"app.config.{e}") for e in ("ts", "js", "mjs", "cjs")) if os.path.exists(p)]
        done_version = False
        if os.path.exists(app_json):
            o, n = bump_json_literal(app_json, ["expo", "version"], lit("version"), lambda _: new_version)
            if o is not None:
                done_version = True
                notes.append(f"app.json expo.version: {o} -> {n}")
            if eas_owned:
                notes.append(f"build numbers left to EAS ({why})")
            else:
                for kp, key, render in ((["expo", "ios", "buildNumber"], "buildNumber", lambda v: str(int(v) + 1)),
                                        (["expo", "android", "versionCode"], "versionCode", lambda v: int(v) + 1)):
                    o, n = bump_json_literal(app_json, kp, lit(key), render)
                    notes.append(f"app.json {'.'.join(kp)}: {o} -> {n}" if o is not None
                                 else f"app.json {'.'.join(kp)} not set ({why}) — not created; Expo/EAS default applies")
        for p in dyn:
            text = current(p)
            hits = re.findall(r"(?<![A-Za-z0-9_$.])version\s*:\s*(['\"`])([^'\"`]*)\1", text)
            if len(hits) == 1 and not done_version:
                stage(p, re.sub(r"((?<![A-Za-z0-9_$.])version\s*:\s*)(['\"`])[^'\"`]*\2",
                                lambda m: f"{m.group(1)}{m.group(2)}{new_version}{m.group(2)}", text, count=1))
                notes.append(f"{os.path.basename(p)} version literal: {hits[0][1]} -> {new_version}")
                done_version = True
            elif len(hits) == 1:
                notes.append(f"{os.path.basename(p)} also has a version literal ({hits[0][1]}) — check which one wins with `npx expo config`")
            elif len(hits) > 1:
                notes.append(f"{os.path.basename(p)}: {len(hits)} version literals — ambiguous, edit by hand")
            elif not done_version:
                if re.search(r"package\.json|\bpkg\.version|npm_package_version", text):
                    notes.append(f"{os.path.basename(p)}: version is read from package.json — covered by the package.json bump (a -dev.N package.json value then flows into the store version too — prefer a literal)")
                    done_version = True
                else:
                    notes.append(f"{os.path.basename(p)}: version is computed (no literal) — not edited, update its source by hand")
            if not eas_owned:
                text = current(p)
                for key, pat, render in (("buildNumber", r"(buildNumber\s*:\s*)(['\"`])(\d+)\2", lambda m: f"{m.group(1)}{m.group(2)}{int(m.group(3)) + 1}{m.group(2)}"),
                                         ("versionCode", r"(versionCode\s*:\s*)(\d+)(?![\d.])", lambda m: f"{m.group(1)}{int(m.group(2)) + 1}")):
                    found = re.findall(pat, text)
                    if len(found) == 1:
                        text = re.sub(pat, render, text, count=1)
                        notes.append(f"{os.path.basename(p)} {key} literal incremented by 1")
                    elif re.search(key, text):
                        notes.append(f"{os.path.basename(p)}: {key} is not a single literal — review by hand")
                stage(p, text)
            elif re.search(r"buildNumber|versionCode", text):
                notes.append(f"{os.path.basename(p)}: build-number literals present but EAS owns them ({why}) — left untouched")
        if not done_version:
            notes.append("expo: no expo.version literal found — nothing edited for the app version")

if toolchain == "metro":
    if not release:
        notes.append("metro: pre-release value — versionName/versionCode/MARKETING_VERSION/CURRENT_PROJECT_VERSION left untouched")
    else:
        for g in ("android/app/build.gradle", "android/app/build.gradle.kts"):
            p = os.path.join(root, g)
            if not os.path.exists(p):
                continue
            text = current(p)
            m_name = re.search(r'(versionName\s*=?\s*)"([^"]*)"', text)
            m_code = re.search(r"(versionCode\s*=?\s*)(\d+)", text)
            if not m_name or not m_code:
                sys.exit(f"versionName/versionCode literal not found in {g} (computed?) — edit by hand")
            text = text[:m_name.start()] + f'{m_name.group(1)}"{new_version}"' + text[m_name.end():]
            m_code = re.search(r"(versionCode\s*=?\s*)(\d+)", text)
            new_code = int(m_code.group(2)) + 1
            text = text[:m_code.start()] + f"{m_code.group(1)}{new_code}" + text[m_code.end():]
            stage(p, text)
            notes.append(f"{g}: versionName {m_name.group(2)} -> {new_version}, versionCode {m_code.group(2)} -> {new_code}")
        projs = [p for p in glob.glob(os.path.join(root, "ios", "*.xcodeproj", "project.pbxproj")) if "/Pods.xcodeproj/" not in p]
        for p in projs:
            text = current(p)
            mvs = re.findall(r"MARKETING_VERSION = ([^;]+);", text)
            cpvs = [int(x) for x in re.findall(r"CURRENT_PROJECT_VERSION = (\d+);", text)]
            if mvs:
                text = re.sub(r"(MARKETING_VERSION = )[^;]+;", lambda m: f"{m.group(1)}{new_version};", text)
                notes.append(f"{os.path.relpath(p, root)}: MARKETING_VERSION {sorted(set(mvs))} -> {new_version} ({len(mvs)} build configurations)")
            if cpvs:
                new_cpv = max(cpvs) + 1
                text = re.sub(r"(CURRENT_PROJECT_VERSION = )\d+;", lambda m: f"{m.group(1)}{new_cpv};", text)
                notes.append(f"{os.path.relpath(p, root)}: CURRENT_PROJECT_VERSION {sorted(set(cpvs))} -> {new_cpv} ({len(cpvs)} build configurations)"
                             + (" — configurations disagreed, all set to max+1" if len(set(cpvs)) > 1 else ""))
            stage(p, text)
            plists = set(re.findall(r'INFOPLIST_FILE = "?([^";]+)"?;', text))
            for rel in plists:
                ip = os.path.join(root, "ios", rel)
                if not os.path.exists(ip):
                    continue
                ptext = current(ip)
                for key, render in (("CFBundleShortVersionString", lambda v: new_version),
                                    ("CFBundleVersion", lambda v: str(int(v) + 1) if v.isdigit() else None)):
                    m = re.search(r"(<key>%s</key>\s*<string>)([^<]*)(</string>)" % key, ptext)
                    if not m or m.group(2).startswith("$("):
                        continue  # driven by the build setting rewritten above
                    nv = render(m.group(2))
                    if nv is None:
                        notes.append(f"{rel}: {key} literal {m.group(2)!r} is not an integer — edit by hand")
                        continue
                    ptext = ptext[:m.start()] + m.group(1) + nv + m.group(3) + ptext[m.end():]
                    notes.append(f"{rel}: {key} literal {m.group(2)} -> {nv}")
                stage(ip, ptext)
        if not projs:
            notes.append("metro: no ios/*.xcodeproj found — iOS version not edited")

for path, (old, new) in changes.items():
    rel = os.path.relpath(path, root)
    sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), new.splitlines(True), f"a/{rel}", f"b/{rel}"))
    if not dry_run:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
print("\n".join(["---- summary " + ("(dry run, nothing written)" if dry_run else "(written)")] + notes))
PY
```

What it does, per toolchain (only for a release value):

- **expo** — `app.json` `expo.version` → `<new-version>`; `expo.ios.buildNumber` (a string) and
  `expo.android.versionCode` (an integer) +1 each when present and not EAS-owned. A dynamic `app.config.*`: a
  single `version: '<literal>'` (never `runtimeVersion`/`sdkVersion` — the regex requires `version` not to be part
  of a longer identifier) is rewritten when `app.json` didn't already carry it; `buildNumber`/`versionCode` single
  literals +1 under the same EAS rule; a version **read from `package.json`** (`pkg.version`,
  `process.env.npm_package_version`) is already covered by Step 4 — reported, with the warning that a `-dev.N`
  `package.json` value then flows into the store version too; any other computed value, or more than one literal,
  is reported for a hand edit and never guessed at. When both `app.json` and `app.config.*` carry a literal, only
  `app.json`'s is edited and the report says to check which one wins (Step 7's `expo config` read-back answers it).
- **metro** — `android/app/build.gradle(.kts)`: the first `versionName` → `"<new-version>"`, the first
  `versionCode` +1 (both forms, with or without `=`; a computed value — e.g. read from `gradle.properties` — stops
  the script with a "edit by hand" message rather than half-applying). `ios/<App>.xcodeproj/project.pbxproj`:
  **every** `MARKETING_VERSION = …;` → `<new-version>` and **every** `CURRENT_PROJECT_VERSION = N;` → `max(N) + 1`
  (one value per build configuration — Debug and Release, plus any extra target; if they disagreed, all become
  `max + 1` and the report says so). Each `INFOPLIST_FILE` the project names: a `CFBundleShortVersionString`/
  `CFBundleVersion` that is a literal rather than `$(MARKETING_VERSION)`/`$(CURRENT_PROJECT_VERSION)` gets the new
  version / +1 too. `ios/Pods/` is never touched.

## Step 7 — Preview (`dry-run: yes`) or write, then sanity-check

- **`dry-run: yes`**: run Step 6's script with `dry-run` `yes` and `package.json` `yes` — it prints every diff and
  writes nothing. Skip Step 4's `npm version` and the checks below; say plainly in the report that nothing was
  written.
- **Otherwise**: Step 4 has written `package.json` + lockfile; run Step 6's script with `dry-run` `no` and
  `package.json` `no`, then check each file still parses and the build tools resolve the new values:

  ```bash
  # expo — resolved values after the merge of app.json + app.config.* (should show the new version/build numbers)
  npx expo config --type public --json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const c=JSON.parse(s);console.log(c.version, c.ios?.buildNumber, c.android?.versionCode)})'

  # metro, iOS (macOS only)
  plutil -lint ios/<App>.xcodeproj/project.pbxproj ios/<App>/Info.plist
  xcodebuild -showBuildSettings -project ios/<App>.xcodeproj -scheme <App> 2>/dev/null | grep -E ' (MARKETING_VERSION|CURRENT_PROJECT_VERSION) ='

  # metro, Android — needs a JDK Gradle/AGP supports (17 or 21) and ANDROID_HOME; slow on a cold Gradle cache
  printf 'allprojects { afterEvaluate { p -> if (p.name == "app") println("VN=" + p.android.defaultConfig.versionName + " VC=" + p.android.defaultConfig.versionCode) } }\n' > "$TMPDIR/rn-version.gradle"
  (cd android && JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew -q -I "$TMPDIR/rn-version.gradle" :app:help)
  ```

  `xcodebuild -showBuildSettings` works against the `.xcodeproj` without `pod install`. Off macOS, report the iOS
  check as "not run (needs macOS)"; without a usable JDK/Android SDK, report the Gradle check as "not run" — never as
  passed. A failure means the rewrite broke the file: stop, show the error, and don't report success.

## Step 8 — Optionally sync other files that mirror the version

Ask (or honor `args: sync-files`) whether to also update README/Antora mentions, exactly as
`iru-typescript-bump-version`'s Step 6 does (same files: `README.md` install/"Current version" mentions,
`docs/antora.yml`'s `version:` — release values only — and Antora pages with a literal version), using the same
**marker mapping when `next-version` was given**: "Latest release"-style markers and a Project Status `Latest
release` row → `<new-version>` when it's a release, untouched when it's a pre-release; "Latest snapshot"/"Current
development version"-style markers → `next-version` when given, otherwise `<new-version>` only if it's a
pre-release, and untouched (with a warning that the caller should pass `next-version`) when `<new-version>` is a
release — never regress a development marker to a release value. A snippet with no such marker → `<new-version>`.
An app README usually shows a store badge that pulls live rather than a literal — leave such badges alone.

This is offered, not automatic — don't rewrite anything the user didn't confirm.

## Step 9 — Report

Summarize:

- The toolchain (and how it was decided — detected or `args: toolchain`), the package manager, and whether the
  value was treated as a release or a pre-release.
- `package.json` (+ lockfile) old → new.
- **expo**: which file carried the app version (`app.json`, an `app.config.*` literal, read from `package.json`, or
  "computed — edit by hand") and its old → new value; build numbers incremented (old → new) or left alone and why
  (EAS remote source / local `autoIncrement` / not present / pre-release); the `npx expo config` read-back.
- **metro**: `versionName`/`versionCode` old → new, `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` old → new and how
  many build configurations, any `Info.plist` literal rewritten, or "untouched (pre-release)"; the `plutil`/
  `xcodebuild`/Gradle read-back results or why each wasn't run.
- Any Step 8 files accepted, and whether this was a `dry-run`.
- **Warn explicitly**: review the diff before committing (this skill never commits or tags); a build number, once
  uploaded to App Store Connect/Google Play, can never be reused, so re-running a release bump is not undone by
  calling again with a lower value; for expo with EAS-owned build numbers, nothing in the repository changes them —
  `eas build:version:set` does; an `app.config.*` that computes its version needs the hand edit the report names.

## Verification notes

Verified in `$TMPDIR/iru-verify/react-native/`, against a `create-expo-app@latest --template blank-typescript`
app (Expo SDK 57.0.29, React Native 0.86.3) and a `@react-native-community/cli@latest init` app (CLI 20.2.0, React
Native 0.87.1), each committed to a throwaway git repository so every run could be checked with `git diff`:

- **metro, `0.0.1` → `1.0.0-dev.0`** (`npm version` + script): only `package.json` and `package-lock.json` changed;
  the script reported the native files untouched (pre-release).
- **metro, `1.0.0-dev.0` → `1.0.0`**: `android/app/build.gradle` `versionCode 1` → `2`, `versionName "1.0"` →
  `"1.0.0"`; `project.pbxproj` `MARKETING_VERSION = 1.0;` → `1.0.0;` and `CURRENT_PROJECT_VERSION = 1;` → `2;` in
  both build configurations (4 files changed, 9 insertions/9 deletions). `plutil -lint` passed on the pbxproj and
  `Info.plist`; `xcodebuild -showBuildSettings` (Xcode 27, no `pod install`) printed `MARKETING_VERSION = 1.0.0`
  and `CURRENT_PROJECT_VERSION = 2`; the Gradle init-script read-back (JDK 21, Gradle 9.4.1 wrapper) printed
  `VN=1.0.0 VC=2`.
- **metro, `Info.plist` literals** (a copy with `CFBundleShortVersionString` `1.0` and `CFBundleVersion` `5`
  hard-coded): both rewritten (`2.0.0`, `6`) alongside the build settings, `plutil -lint` OK.
- **metro, dry run**: printed the `package.json`, `build.gradle`, and `project.pbxproj` diffs; `git status` stayed
  clean.
- **expo, `1.0.0` → `1.1.0-dev.0` → `1.1.0`** with no `eas.json` and no build numbers: the pre-release changed only
  `package.json` + lockfile; the release changed `app.json` `"version": "1.0.0"` → `"1.1.0"` (one-line diff, the
  rest of the file byte-identical) and reported both build numbers as "not set — not created"; `npx expo config`
  resolved `1.1.0`.
- **expo, `ios.buildNumber: "3"` / `android.versionCode: 3`, no `eas.json`**: both became `4`. With an `eas.json`
  of `cli.appVersionSource: "remote"` + `production.autoIncrement: true` (the `iru-setup-react-native-app` shape):
  only `expo.version` changed, build numbers reported as EAS-owned.
- **expo, dynamic `app.config.ts`** (no `app.json`): a `version: '1.0.0'` literal next to `runtimeVersion: {
  policy: 'appVersion' }`, `ios: { buildNumber: '3' }`, `android: { versionCode: 7 }` → `version: '1.1.0'`,
  `buildNumber: '4'`, `versionCode: 8`, `runtimeVersion` untouched; `npx expo config` resolved `1.1.0 4 8`. With
  `version: pkg.version` instead, the script reported "read from package.json — covered by the package.json bump"
  and edited nothing.
- **Unverified locally**: pnpm/yarn version commands (only npm was exercised — same caveat as
  `iru-typescript-bump-version`); a `build.gradle.kts` app module (the template ships Groovy — the regex accepts
  the `=` form but wasn't run against a real `.kts` file); `eas build:version:*` (needs an Expo account); an
  Xcode project with more than one app target.
