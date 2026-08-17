#!/usr/bin/env python3
"""Expose Android instrumented-test failures as GitHub annotations and summary."""

from __future__ import annotations

import html
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RESULT_ROOT = REPO_ROOT / "app" / "build" / "outputs" / "androidTest-results"
DEVICE_LOG = REPO_ROOT / "build" / "ci-device-tests.log"
MAX_FAILURES = 30


def github_escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def compact(value: str, limit: int = 1600) -> str:
    normalized = re.sub(r"\x1b\[[0-9;]*m", "", value)
    normalized = re.sub(r"[ \t]+", " ", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized).strip()
    return normalized[:limit] + ("…" if len(normalized) > limit else "")


def xml_failures() -> list[tuple[str, str]]:
    failures: list[tuple[str, str]] = []
    if not RESULT_ROOT.exists():
        return failures
    for result_file in sorted(RESULT_ROOT.rglob("*.xml")):
        try:
            root = ET.parse(result_file).getroot()
        except ET.ParseError:
            continue
        for test_case in root.iter("testcase"):
            failure_nodes = list(test_case.findall("failure")) + list(test_case.findall("error"))
            if not failure_nodes:
                continue
            class_name = test_case.attrib.get("classname", "instrumented test")
            method_name = test_case.attrib.get("name", "unknown")
            title = f"{class_name}.{method_name}"
            details = "\n".join(
                filter(
                    None,
                    (
                        node.attrib.get("message", "").strip() or (node.text or "").strip()
                        for node in failure_nodes
                    ),
                )
            )
            failures.append((title, compact(details or "Instrumented test failed without a message.")))
            if len(failures) >= MAX_FAILURES:
                return failures
    return failures


def log_failure_excerpt() -> str:
    if not DEVICE_LOG.exists():
        return "No Android test XML or captured Gradle device log was produced."
    lines = DEVICE_LOG.read_text(encoding="utf-8", errors="replace").splitlines()
    interesting = [
        line
        for line in lines
        if re.search(
            r"( FAILED$|FAILURE:|\* What went wrong:|INSTRUMENTATION_(FAILED|ABORTED)|Process crashed|Exception|Error:)",
            line,
            re.IGNORECASE,
        )
    ]
    selected = interesting[-24:] if interesting else lines[-40:]
    return compact("\n".join(selected), limit=3000)


def append_summary(failures: list[tuple[str, str]]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    with open(summary_path, "a", encoding="utf-8") as summary:
        summary.write("\n## Android device-test failure details\n\n")
        if failures:
            summary.write("| Test | Failure |\n|---|---|\n")
            for title, details in failures:
                one_line = details.splitlines()[0]
                summary.write(f"| `{html.escape(title)}` | {html.escape(one_line)} |\n")
        else:
            summary.write("No parseable failing JUnit XML was produced. Captured Gradle excerpt:\n\n```text\n")
            summary.write(log_failure_excerpt())
            summary.write("\n```\n")


def main() -> int:
    failures = xml_failures()
    if failures:
        for title, details in failures:
            print(f"::error title={github_escape(title)}::{github_escape(details)}")
    else:
        excerpt = log_failure_excerpt()
        print(f"::error title=Android device test failed::{github_escape(excerpt)}")
    append_summary(failures)
    # This step reports the preceding failure and must not replace its outcome.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
