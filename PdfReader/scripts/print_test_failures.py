#!/usr/bin/env python3
"""Print failed tests (with stack traces) and app crashes from Gradle test output.

CI logs truncate Gradle's own failure output, so this prints the JUnit XML failures and any
FATAL EXCEPTION found in the per-test logcat files that the Android Gradle plugin saves.
Usage: print_test_failures.py <build-outputs-dir>...
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_TRACE_LINES = 40


def print_xml_failures(root_dir):
    found = 0
    for path in glob.glob(os.path.join(root_dir, "**", "TEST-*.xml"), recursive=True):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for case in tree.iter("testcase"):
            for failure in list(case.iter("failure")) + list(case.iter("error")):
                found += 1
                print(f"\n=== FAILED {case.get('classname')}.{case.get('name')}")
                text = (failure.text or failure.get("message") or "").strip().splitlines()
                print("\n".join(text[:MAX_TRACE_LINES]))
    return found


def print_crashes(root_dir):
    for path in glob.glob(os.path.join(root_dir, "**", "logcat-*.txt"), recursive=True):
        with open(path, errors="replace") as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            if "FATAL EXCEPTION" in line or "AndroidRuntime: " in line and "Caused by" in line:
                print(f"\n=== CRASH in {os.path.basename(path)}")
                print("".join(lines[i:i + MAX_TRACE_LINES]))
                break


if __name__ == "__main__":
    total = 0
    for directory in sys.argv[1:]:
        total += print_xml_failures(directory)
        print_crashes(directory)
    print(f"\n{total} failing test(s) listed.")
