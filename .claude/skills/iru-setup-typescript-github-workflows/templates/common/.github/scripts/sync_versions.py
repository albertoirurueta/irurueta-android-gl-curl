#!/usr/bin/env python3
"""Rewrites README/Antora version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_VERSION from the environment and updates README.md,
docs/antora.yml, and any Antora page that carries the same "Latest release" /
"Latest snapshot" dependency snippets as README.md.

package.json (and its lockfile) are bumped by the previous "Bump package.json and
lockfile" workflow step via the package manager's own `version` command, not by this
script.

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


def replace_dependency_snippets(text, release_version, next_version):
    """Replaces the version string following a "Latest release"/"Latest snapshot" marker line."""
    lines = text.splitlines(keepends=True)
    pending = None
    for i, line in enumerate(lines):
        lower = line.strip().lower()
        if lower.startswith("latest release"):
            pending = release_version
        elif lower.startswith("latest snapshot") or lower.startswith("latest dev"):
            pending = next_version
        elif pending and re.search(r'"version":\s*"[^"]*"', line):
            lines[i] = re.sub(r'("version":\s*")[^"]*(")', rf"\g<1>{pending}\g<2>", line)
            pending = None
    return "".join(lines)


def update_readme(release_version, next_version):
    path = REPO_ROOT / "README.md"
    text = path.read_text()
    # Adjust these two row-label regexes to match this repository's actual README table wording
    # (resolved in Step 1) if it differs from setup-readme's default "Current development version" /
    # "Latest release" labels.
    text = re.sub(
        r"(\| Current development version \| `)[^`]*(` \|)",
        rf"\g<1>{next_version}\g<2>",
        text,
    )
    text = re.sub(
        r"(\| Latest release[^|]*\| `)[^`]*(` \|)",
        rf"\g<1>{release_version}\g<2>",
        text,
    )
    text = replace_dependency_snippets(text, release_version, next_version)
    path.write_text(text)


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(r"(?m)^version:.*$", f"version: {release_version}", text, count=1)
    if count == 1:
        path.write_text(updated)


def update_antora_pages(release_version, next_version):
    pages_dir = REPO_ROOT / "docs" / "modules" / "ROOT" / "pages"
    if not pages_dir.exists():
        return
    for path in sorted(pages_dir.glob("*.adoc")):
        text = path.read_text()
        if '"version"' not in text and "Latest release" not in text:
            continue
        updated = replace_dependency_snippets(text, release_version, next_version)
        if updated != text:
            path.write_text(updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_version = os.environ["NEXT_VERSION"]

    update_readme(release_version, next_version)
    update_antora_component_version(release_version)
    update_antora_pages(release_version, next_version)


if __name__ == "__main__":
    main()
