#!/usr/bin/env python3
"""Rewrites README/Antora version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_VERSION from the environment. The flavor's own version locations (config/app.php,
plugin/theme headers and constants, the PrestaShop module class and config.xml) are bumped by php_version.py in the
step before, and package.json by npm; this script only handles the README's version table and the Antora component
version. CHANGELOG.md is left alone: its release section arrives through the merge that precedes this script.
"""
import os
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def update_readme(release_version, next_version):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    # Adjust these row labels if this repository's README words them differently (Step 1's survey).
    text = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_version}\g<2>", text)
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
    next_version = os.environ["NEXT_VERSION"]
    update_readme(release_version, next_version)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
