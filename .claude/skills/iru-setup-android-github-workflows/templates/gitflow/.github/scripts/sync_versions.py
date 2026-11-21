#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_SNAPSHOT from the environment and updates
<lib-module>/build.gradle.kts's `val libraryVersion`, <app-module>/build.gradle.kts's
`versionName` (and, only when the release itself is not a -SNAPSHOT, its `versionCode`
— see iru-android-bump-version's Step 4 for the same rule), README.md, docs/antora.yml,
and any Antora page that carries the same "Latest release" / "Latest snapshot"
dependency snippets as README.md.

CHANGELOG.md is intentionally left untouched: its release section and fresh
"[Unreleased]" heading are written before the tag is published, and arrive on
<integration-branch> via the merge that precedes this script, not by bumping a
version string.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def update_lib_module(next_snapshot):
    path = REPO_ROOT / "<lib-module>" / "build.gradle.kts"
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
    path = REPO_ROOT / "<app-module>" / "build.gradle.kts"
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


def update_readme(release_version, next_snapshot):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    # Adjust these two row-label regexes to match this repository's actual README table wording (resolved in
    # Step 1) if it differs from setup-readme's default "Current development version" / "Latest release" labels.
    text = re.sub(
        r"(\| Current development version \| `)[^`]*(` \|)",
        rf"\g<1>{next_snapshot}\g<2>",
        text,
    )
    text = re.sub(
        r"(\| Latest release[^|]*\| `)[^`]*(` \|)",
        rf"\g<1>{release_version}\g<2>",
        text,
    )
    text = replace_dependency_snippets(text, release_version, next_snapshot)
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
    for path in sorted(pages_dir.glob("*.adoc")):
        text = path.read_text()
        if "implementation" not in text:
            continue
        updated = replace_dependency_snippets(text, release_version, next_snapshot)
        if updated != text:
            path.write_text(updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_snapshot = os.environ["NEXT_SNAPSHOT"]

    update_lib_module(next_snapshot)
    update_app_module(next_snapshot)
    update_readme(release_version, next_snapshot)
    update_antora_component_version(release_version)
    update_antora_pages(release_version, next_snapshot)


if __name__ == "__main__":
    main()
