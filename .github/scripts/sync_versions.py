#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

With `--print-next-snapshot`, only prints the development version to open
after RELEASE_VERSION (see planned_next_snapshot) and exits.

Otherwise reads RELEASE_VERSION and NEXT_SNAPSHOT from the environment and updates
lib/build.gradle.kts's `val libraryVersion`, app/build.gradle.kts's
`versionName` (its `versionCode` is only bumped by a real release, never here
— see iru-android-bump-version's Step 4 for the same rule), README.md,
docs/antora.yml, and the Antora pages that mention the published coordinates
(docs/modules/ROOT/pages/installation.adoc, whats-new.adoc, ...).

Dependency snippets are matched in two ways:

* the generic "Latest release" / "Latest snapshot" marker form shared with
  iru-android-bump-version (a bare marker line followed by an
  `implementation ...` line), and
* this library's own Maven coordinates (ARTIFACT_COORDINATES), in both the
  Groovy `implementation 'group:artifact:X.Y.Z'` and the Kotlin-DSL
  `implementation("group:artifact:X.Y.Z")` forms, which are always set to the
  release version unless a "Latest snapshot" marker already pointed them at
  the development snapshot.

CHANGELOG.md is intentionally left untouched: its release section and fresh
"[Unreleased]" heading are written before the tag is published, and arrive on
develop via the merge that precedes this script, not by bumping a
version string. Release-note headings in whats-new.adoc are history and are
never rewritten either; only "Latest release" rows/attributes and dependency
snippets are.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Maven coordinates (group:artifact) published by lib/ — every snippet pinning these is kept at the
# latest release.
ARTIFACT_COORDINATES = "com.irurueta:irurueta-android-gl-curl"

# Version-catalog keys used in docs snippets such as `glCurl = "1.1.6"` under `[versions]`.
TOML_VERSION_KEYS = ("glCurl",)

VERSION = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.]+)?"


def update_lib_module(next_snapshot):
    path = REPO_ROOT / "lib" / "build.gradle.kts"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(
        r'(val libraryVersion = )"[^"]*"',
        lambda m: f'{m.group(1)}"{next_snapshot}"',
        text,
        count=1,
    )
    if count == 0:
        sys.exit(f"{path}: libraryVersion pattern not found")
    path.write_text(updated)


def update_app_module(next_snapshot):
    path = REPO_ROOT / "app" / "build.gradle.kts"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(
        r'(versionName = )"[^"]*"',
        lambda m: f'{m.group(1)}"{next_snapshot}"',
        text,
        count=1,
    )
    if count == 0:
        sys.exit(f"{path}: versionName pattern not found")
    path.write_text(updated)
    # versionCode is bumped by a *release* (a non -SNAPSHOT version), never by this script, which only ever
    # writes a -SNAPSHOT value — matching iru-android-bump-version's Step 4 rule exactly. Nothing to do here.


def replace_dependency_snippets(text, release_version, next_snapshot):
    """Rewrites the trailing version segment of an `implementation 'group:artifact:version'` (or Kotlin-DSL
    `implementation("group:artifact:version")`) line, following a "Latest release"/"Latest snapshot" marker."""
    lines = text.splitlines(keepends=True)
    pending = None
    for i, line in enumerate(lines):
        lower = line.strip().lower()
        if lower.startswith("latest release"):
            pending = release_version
        elif lower.startswith("latest snapshot"):
            pending = next_snapshot
        elif pending and re.search(r"implementation\s*\(?['\"][^:'\"]+:[^:'\"]+:", line):
            lines[i] = re.sub(
                r"(implementation\s*\(?['\"][^:'\"]+:[^:'\"]+:)[^'\"]+(['\"])",
                lambda m: f"{m.group(1)}{pending}{m.group(2)}",
                line,
            )
            pending = None
    return "".join(lines)


def replace_coordinate_versions(text, release_version):
    """Sets every `<ARTIFACT_COORDINATES>:<version>` reference (Groovy or Kotlin-DSL, quoted with ' or ") to
    the release version, leaving a -SNAPSHOT reference (put there by a "Latest snapshot" marker) alone."""
    pattern = re.compile(r"(['\"]" + re.escape(ARTIFACT_COORDINATES) + r":)(" + VERSION + r")(['\"])")

    def repl(m):
        if m.group(2).endswith("-SNAPSHOT"):
            return m.group(0)
        return f"{m.group(1)}{release_version}{m.group(3)}"

    return pattern.sub(repl, text)


def replace_latest_release_markers(text, release_version):
    """Rewrites "Latest release" values in Markdown/AsciiDoc tables and attributes:
    `| Latest release | X |` or `| Latest release | `X` |` (Markdown), `|Latest release |`X`` (AsciiDoc),
    `:latest-release: X`."""
    text = re.sub(
        r"(\|\s*Latest release[^|\n]*\|\s*`?)" + VERSION + r"(`?)",
        rf"\g<1>{release_version}\g<2>",
        text,
    )
    text = re.sub(
        r"(?m)^(:latest-release:[ \t]*)" + VERSION + r"[ \t]*$",
        rf"\g<1>{release_version}",
        text,
    )
    return text


def replace_toml_versions(text, release_version):
    for key in TOML_VERSION_KEYS:
        text = re.sub(
            r'(?m)^(\s*' + re.escape(key) + r'\s*=\s*")' + VERSION + r'(")',
            rf"\g<1>{release_version}\g<2>",
            text,
        )
    return text


def replace_maven_badge_version(text, release_version):
    """Maven Central badge/search link: `.../com.irurueta/irurueta-android-gl-curl/X.Y.Z/aar`."""
    group, artifact = ARTIFACT_COORDINATES.split(":")
    return re.sub(
        r"(" + re.escape(f"{group}/{artifact}/") + r")" + VERSION + r"(/aar)",
        rf"\g<1>{release_version}\g<2>",
        text,
    )


def update_readme(release_version, next_snapshot):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    text = re.sub(
        r"(\|\s*Current development version\s*\|\s*`?)" + VERSION + r"(`?\s*\|)",
        rf"\g<1>{next_snapshot}\g<2>",
        text,
    )
    text = replace_latest_release_markers(text, release_version)
    text = replace_dependency_snippets(text, release_version, next_snapshot)
    text = replace_coordinate_versions(text, release_version)
    text = replace_maven_badge_version(text, release_version)
    path.write_text(text)


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(r"(?m)^version:.*$", f"version: {release_version}", text, count=1)
    if count == 1:
        path.write_text(updated)


def update_antora_pages(release_version, next_snapshot):
    pages_dir = REPO_ROOT / "docs" / "modules" / "ROOT" / "pages"
    if not pages_dir.exists():
        return
    for path in sorted(pages_dir.rglob("*.adoc")):
        text = path.read_text()
        updated = replace_latest_release_markers(text, release_version)
        updated = replace_dependency_snippets(updated, release_version, next_snapshot)
        updated = replace_coordinate_versions(updated, release_version)
        updated = replace_toml_versions(updated, release_version)
        if updated != text:
            path.write_text(updated)


def parse_version(version):
    """`X.Y.Z` or `X.Y.Z-SNAPSHOT` -> (X, Y, Z), or None if it isn't in that form."""
    m = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)(?:-SNAPSHOT)?", version)
    return tuple(int(part) for part in m.groups()) if m else None


def planned_next_snapshot(release_version):
    """The development version to open after `release_version`.

    The release commit records the planned next version in README.md's "Current development version" row
    (iru-release writes it there when cutting the release). Use it when it is an `X.Y.Z-SNAPSHOT` newer than
    the release, so a planned minor/major bump survives the sync; otherwise fall back to a patch bump.
    """
    release = parse_version(release_version)
    if release is None or release_version.endswith("-SNAPSHOT"):
        sys.exit(f"Release version '{release_version}' is not in X.Y.Z form; cannot compute the next snapshot.")
    patch_bump = f"{release[0]}.{release[1]}.{release[2] + 1}-SNAPSHOT"

    readme = REPO_ROOT / "README.md"
    if not readme.exists():
        return patch_bump
    m = re.search(r"\|\s*Current development version\s*\|\s*`?(" + VERSION + r")`?\s*\|", readme.read_text())
    if not m or not m.group(1).endswith("-SNAPSHOT"):
        return patch_bump
    planned = parse_version(m.group(1))
    if planned is None or planned <= release:
        return patch_bump
    return m.group(1)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    if len(sys.argv) > 1 and sys.argv[1] == "--print-next-snapshot":
        print(planned_next_snapshot(release_version))
        return
    next_snapshot = os.environ["NEXT_SNAPSHOT"]

    update_lib_module(next_snapshot)
    update_app_module(next_snapshot)
    update_readme(release_version, next_snapshot)
    update_antora_component_version(release_version)
    update_antora_pages(release_version, next_snapshot)


if __name__ == "__main__":
    main()
