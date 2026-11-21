#!/usr/bin/env python3
"""Converts an lcov coverage report (xcrun llvm-cov export -format=lcov) into
SonarQube's Generic Test Coverage XML format (sonar.coverageReportPaths).

Usage: python3 lcov_to_sonar_generic.py coverage.lcov > sonarqube-generic-coverage.xml

File paths are rewritten relative to the current working directory, since
llvm-cov's SF: lines are always absolute and Sonar expects project-relative
paths.
"""
import os
import sys
import xml.sax.saxutils as sx


def main():
    lcov_path = sys.argv[1] if len(sys.argv) > 1 else "coverage.lcov"
    root = os.getcwd()
    files = []
    current = None
    with open(lcov_path) as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line.startswith("SF:"):
                current = {"path": line[3:], "lines": []}
            elif line.startswith("DA:") and current is not None:
                lineno, hits = line[3:].split(",", 1)
                current["lines"].append((int(lineno), int(hits) > 0))
            elif line == "end_of_record" and current is not None:
                files.append(current)
                current = None

    out = ['<coverage version="1">']
    for f in files:
        rel = os.path.relpath(f["path"], root)
        out.append(f'  <file path="{sx.escape(rel)}">')
        for lineno, covered in f["lines"]:
            out.append(f'    <lineToCover lineNumber="{lineno}" covered="{"true" if covered else "false"}"/>')
        out.append("  </file>")
    out.append("</coverage>")
    print("\n".join(out))


if __name__ == "__main__":
    main()
