#!/usr/bin/env python3
"""Rewrites README/Antora version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_VERSION from the environment. The project's own version locations (pyproject.toml,
__version__ literals, a Django settings VERSION, a FastAPI(version=) tracking the package) are bumped by
python_version.py in the step before, and uv.lock by `uv lock`; this script only handles the README's version table
and the Antora component version. CHANGELOG.md is left alone: its release section arrives through the merge that
precedes this script.
"""
import os
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def update_readme(release_version, next_version):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    # Adjust these row labels if this repository's README words them differently (Step 1's survey).
    text = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_version}\g<2>", text)
    text = re.sub(r"(\| Latest release[^|]*\| `)[^`]*(` \|)", rf"\g<1>{release_version}\g<2>", text)
    path.write_text(text, encoding="utf-8")


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(r"(?m)^version:.*$", f"version: {release_version}", text, count=1)
    if count == 1:
        path.write_text(updated, encoding="utf-8")


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_version = os.environ["NEXT_VERSION"]
    update_readme(release_version, next_version)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
