#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_VERSION from the environment. For a library,
rewrites version.txt (if it exists) and reopens CHANGELOG.md's [Unreleased]
section — the release section itself was already written before the tag was
published. For an app, bumps MARKETING_VERSION/CURRENT_PROJECT_VERSION in
project.yml or Project.swift. Mirrors iru-swift-bump-version's own Step 3/4
rewrite rules exactly.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def update_version_txt(release_version):
    path = REPO_ROOT / "version.txt"
    if not path.exists():
        return
    path.write_text(release_version + "\n")


def update_readme_package_snippets(release_version):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    updated, n = re.subn(
        r'((?:from|exact)\s*:\s*)"[^"]*"',
        lambda m: f'{m.group(1)}"{release_version}"',
        text,
    )
    if n:
        path.write_text(updated)


def update_project_yml(next_version):
    path = REPO_ROOT / "project.yml"
    if not path.exists():
        return
    text = path.read_text()
    # Quote-tolerant: matches `MARKETING_VERSION: 1.0.0` and `MARKETING_VERSION: "1.0.0"` alike, and keeps
    # whichever form the file already uses (same regexes as iru-swift-bump-version's project.yml path).
    text, n_mv = re.subn(
        r'(MARKETING_VERSION:\s*)("?)[^"\s]+\2',
        lambda m: f'{m.group(1)}{m.group(2)}{next_version}{m.group(2)}',
        text, count=1,
    )
    if n_mv == 0:
        sys.exit("project.yml: MARKETING_VERSION not found")

    def bump(m):
        return f"{m.group(1)}{m.group(2)}{int(m.group(3)) + 1}{m.group(2)}"

    text, n_cpv = re.subn(r'(CURRENT_PROJECT_VERSION:\s*)("?)(\d+)\2', bump, text, count=1)
    if n_cpv == 0:
        sys.exit("project.yml: CURRENT_PROJECT_VERSION not found")
    path.write_text(text)


def update_project_swift(next_version):
    path = REPO_ROOT / "Project.swift"
    if not path.exists():
        return
    text = path.read_text()
    text, n_mv = re.subn(
        r'("MARKETING_VERSION":\s*)"[^"]*"', lambda m: f'{m.group(1)}"{next_version}"', text, count=1
    )
    if n_mv == 0:
        sys.exit("Project.swift: MARKETING_VERSION not found")

    def bump(m):
        return f'{m.group(1)}"{int(m.group(2)) + 1}"'

    text, n_cpv = re.subn(r'("CURRENT_PROJECT_VERSION":\s*)"(\d+)"', bump, text, count=1)
    if n_cpv == 0:
        sys.exit("Project.swift: CURRENT_PROJECT_VERSION not found")
    path.write_text(text)


def update_changelog(today):
    path = REPO_ROOT / "CHANGELOG.md"
    if not path.exists():
        return
    text = path.read_text()
    updated, n = re.subn(
        r'^## \[Unreleased\]\s*\n(?:[ \t]*\n)*',
        '## [Unreleased]\n\n',
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if n:
        path.write_text(updated)


def update_antora(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = path.read_text()
    updated, n = re.subn(r"(?m)^version:.*$", f"version: {release_version}", text, count=1)
    if n:
        path.write_text(updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_version = os.environ["NEXT_VERSION"]
    import datetime

    today = datetime.date.today().isoformat()

    # Library path — version.txt and the README dependency snippet always reflect the last actual RELEASE (the
    # real, taggable version), never NEXT_VERSION, which has no git tag yet. The merge step that runs before
    # this script already carries both forward correctly from the stable branch, so these two calls are a
    # defensive no-op in the common case, not the primary mechanism — they matter when the merge itself didn't
    # touch the file (e.g. it was already in sync) or a manual conflict resolution got it wrong.
    update_version_txt(release_version)
    update_readme_package_snippets(release_version)
    update_changelog(today)

    # App path — MARKETING_VERSION/CURRENT_PROJECT_VERSION track the *next* development version, the same
    # "-SNAPSHOT"-equivalent bump every sibling's own sync_versions.py performs.
    update_project_yml(next_version)
    update_project_swift(next_version)

    update_antora(release_version)


if __name__ == "__main__":
    main()
