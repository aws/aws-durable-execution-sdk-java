#!/usr/bin/env python3

import os
import xml.etree.ElementTree as ET
from pathlib import Path


def markdown(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def main():
    reports = sorted(Path("target/surefire-reports").glob("TEST-*.xml"))
    summary_path = Path(os.environ["GITHUB_STEP_SUMMARY"])

    with summary_path.open("a", encoding="utf-8") as summary:
        summary.write(f"### Java {os.environ['JAVA_VERSION']} E2E Test Cases\n\n")

        if not reports:
            summary.write("No Surefire XML test reports were found.\n\n")
            return

        total_tests = 0
        total_failures = 0
        total_errors = 0
        total_skipped = 0
        total_time = 0.0
        cases = []

        for report in reports:
            suite = ET.parse(report).getroot()
            total_tests += int(suite.attrib.get("tests", 0))
            total_failures += int(suite.attrib.get("failures", 0))
            total_errors += int(suite.attrib.get("errors", 0))
            total_skipped += int(suite.attrib.get("skipped", 0))
            total_time += float(suite.attrib.get("time", 0))

            for case in suite.findall("testcase"):
                status = "Passed"
                if case.find("failure") is not None:
                    status = "Failed"
                elif case.find("error") is not None:
                    status = "Errored"
                elif case.find("skipped") is not None:
                    status = "Skipped"

                cases.append(
                    (
                        status,
                        case.attrib.get("classname", suite.attrib.get("name", "")),
                        case.attrib.get("name", ""),
                        float(case.attrib.get("time", 0)),
                    )
                )

        passed = total_tests - total_failures - total_errors - total_skipped
        summary.write(
            f"**Totals:** {total_tests} tests, {passed} passed, {total_failures} failed, "
            f"{total_errors} errored, {total_skipped} skipped, {total_time:.2f}s\n\n"
        )
        summary.write("| Status | Class | Test case | Time |\n")
        summary.write("| --- | --- | --- | ---: |\n")

        for status, class_name, test_name, case_time in sorted(cases):
            summary.write(f"| {status} | `{markdown(class_name)}` | `{markdown(test_name)}` | {case_time:.2f}s |\n")

        summary.write("\n")


if __name__ == "__main__":
    main()
