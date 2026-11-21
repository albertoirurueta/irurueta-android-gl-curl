#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_SNAPSHOT from the environment and updates
pom.xml, README.md, docs/antora.yml, and any Antora page that carries the
same "Latest release" / "Latest snapshot" dependency snippets as README.md.

CHANGELOG.md is intentionally left untouched: its release section and fresh
"[Unreleased]" heading are written before the tag is published, and arrive on
<integration-branch> via the merge that precedes this script, not by bumping
a version string.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def replace_dependency_snippets(text, release_version, next_snapshot):
    """Replaces the <version> tag following a "Latest release"/"Latest snapshot" marker line."""
    lines = text.splitlines(keepends=True)
    pending = None
    for i, line in enumerate(lines):
        lower = line.strip().lower()
        if lower.startswith("latest release"):
            pending = release_version
        elif lower.startswith("latest snapshot"):
            pending = next_snapshot
        elif pending and "<version>" in line:
            lines[i] = re.sub(r"(<version>)[^<]*(</version>)", rf"\g<1>{pending}\g<2>", line)
            pending = None
    return "".join(lines)


def update_pom(release_version, next_snapshot):
    path = REPO_ROOT / "pom.xml"
    text = path.read_text()
    updated, count = re.subn(
        r"(<artifactId><artifact-id></artifactId>\s*\n\s*<version>)[^<]*(</version>)",
        rf"\g<1>{next_snapshot}\g<2>",
        text,
        count=1,
    )
    if count != 1:
        sys.exit("pom.xml: could not find the <artifact-id> <version> element to bump")
    path.write_text(updated)


def update_readme(release_version, next_snapshot):
    path = REPO_ROOT / "README.md"
    text = path.read_text()
    # Adjust these two row-label regexes to match this repository's actual README table wording
    # (resolved in Step 1) if it differs from setup-readme's default "Current development version" /
    # "Latest release" labels.
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
        if "<version>" not in text:
            continue
        updated = replace_dependency_snippets(text, release_version, next_snapshot)
        if updated != text:
            path.write_text(updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_snapshot = os.environ["NEXT_SNAPSHOT"]

    update_pom(release_version, next_snapshot)
    update_readme(release_version, next_snapshot)
    update_antora_component_version(release_version)
    update_antora_pages(release_version, next_snapshot)


if __name__ == "__main__":
    main()
