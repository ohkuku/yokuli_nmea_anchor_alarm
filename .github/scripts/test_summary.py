#!/usr/bin/env python3
"""Render Gradle JUnit XML as a compact GitHub Actions job summary."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


report_dir = Path(sys.argv[1])
files = sorted(report_dir.glob("TEST-*.xml"))
if not files:
    print("\n### Unit test report\n\nNo XML report was produced; compilation may have failed before tests started.")
    raise SystemExit(0)

tests = failures = errors = skipped = 0
failed_names: list[str] = []
for file in files:
    suite = ET.parse(file).getroot()
    tests += int(suite.attrib.get("tests", 0))
    failures += int(suite.attrib.get("failures", 0))
    errors += int(suite.attrib.get("errors", 0))
    skipped += int(suite.attrib.get("skipped", 0))
    for case in suite.iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            failed_names.append(f"{case.attrib.get('classname', '?')}#{case.attrib.get('name', '?')}")

print("\n### Unit test report")
print(f"\n{tests} total · {failures} failed · {errors} errors · {skipped} skipped")
if failed_names:
    print("\nFailed tests:")
    for name in sorted(set(failed_names)):
        print(f"- `{name}`")
