#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_SNAPSHOT from the environment. Maven poms are bumped by `mvn versions:set` in the
workflow itself; this script handles gradle.properties (Gradle builds keep `version=` there), the README's version
table, and the Antora component version. Every file is optional, so the script fits either build tool.
CHANGELOG.md is left alone: its release section arrives through the merge that precedes this script.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def update_gradle_properties(next_snapshot):
    path = REPO_ROOT / "gradle.properties"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(r"(?m)^version=.*$", f"version={next_snapshot}", text, count=1)
    if count != 1:
        sys.exit("gradle.properties: no 'version=' line to bump")
    path.write_text(updated)


def update_readme(release_version, next_snapshot):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    # Adjust these row labels if this repository's README words them differently (Step 1's survey).
    text = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_snapshot}\g<2>", text)
    text = re.sub(r"(\| Latest release[^|]*\| `)[^`]*(` \|)", rf"\g<1>{release_version}\g<2>", text)
    path.write_text(text)


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(r"(?m)^version:.*$", f"version: {release_version}", text, count=1)
    if count == 1:
        path.write_text(updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_snapshot = os.environ["NEXT_SNAPSHOT"]
    update_gradle_properties(next_snapshot)
    update_readme(release_version, next_snapshot)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
