# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Final refreshes must update the case verdict, including previously completed cases."""
import json
from itertools import count
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud, save
from cloud_suite import FIXTURES, collect, run_tests, save_case_assertions
from test_evidence import event


class FinalCollectionAuditTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.artifacts = Path(directory.name)
        self.manifest = {"runId": "run", "commit": "sha", "invocationTimeout": 60,
                         "functions": {fixture: {} for fixture in FIXTURES}}
        save(self.artifacts / "manifest.json", self.manifest)
        self.events = []
        self.refresh_count = 0
        self.deliver_at = None
        self.late_events = []

    def trace(self, marker, base):
        return [event(kind, base + offset, marker, status="SUCCEEDED", fixture="default1",
                      runId="run", executionArn="arn:" + marker)
                for kind, offset in [("WRAPPER_ENTER", 1), ("ROOT_ENTER", 2),
                                     ("ROOT_EXIT", 3), ("WRAPPER_RETURN", 4)]]

    def late_calls(self, marker="first", base=0):
        return [event(kind, base + offset, marker, callId=1, fixture="default1",
                      runId="run", executionArn="arn:" + marker)
                for kind, offset in [("CHECKPOINT_CALL", 5), ("CHECKPOINT_EXIT", 6)]]

    def cloud(self, manifest, artifacts):
        cloud = Cloud(manifest, artifacts)
        def refresh(fixture):
            self.refresh_count += 1
            if self.refresh_count == self.deliver_at:
                self.events.extend(self.late_events)
            cloud.events.update({(entry["environment"], entry["sequence"]): entry for entry in self.events})
            save(self.artifacts / "diagnostics.json", list(cloud.events.values()))
            return list(cloud.events.values())
        cloud.refresh = refresh
        cloud.history = lambda item: []
        return cloud

    def cases(self, cloud, fixture):
        if fixture not in {"default1", "default2"}:
            return []
        marker, base = ("first", 0) if fixture == "default1" else ("second", 100)
        def case():
            cloud.invocations.append({"marker": marker, "scenario": "success"})
            self.events.extend(self.trace(marker, base))
            cloud.events.update({(entry["environment"], entry["sequence"]): entry for entry in self.events})
        return [(marker, case)]

    def run_suite(self):
        with patch("cloud_suite.ARTIFACTS", self.artifacts), \
             patch("cloud_suite.MANIFEST", self.artifacts / "manifest.json"), \
             patch("cloud_suite.Cloud", side_effect=self.cloud), \
             patch("cloud_suite.cases_for_fixture", side_effect=self.cases), \
             patch("cloud_suite.cleanup_case"):
            run_tests()

    def collect_suite(self):
        with patch("cloud_suite.ARTIFACTS", self.artifacts), \
             patch("cloud_suite.MANIFEST", self.artifacts / "manifest.json"), \
             patch("cloud_suite.Cloud", side_effect=self.cloud), \
             patch("cloud_suite.aws", return_value={"Status": "SUCCEEDED"}):
            collect()

    def failures(self):
        return {node.get("name"): [child.get("message") for child in node]
                for node in ET.parse(self.artifacts / "junit.xml").getroot().findall("testcase")}

    def test_refresh_after_case_cleanup_rejects_late_backend_activity(self):
        self.deliver_at, self.late_events = 1, self.late_calls()
        with self.assertRaisesRegex(AssertionError, "cases failed"):
            self.run_suite()
        self.assertIn("after wrapper return", self.failures()["first"][0])
        self.assertEqual([], self.failures()["second"])

    def test_later_case_refresh_rechecks_the_original_case_only(self):
        self.deliver_at, self.late_events = 6, self.late_calls()
        with self.assertRaisesRegex(AssertionError, "cases failed"):
            self.run_suite()
        self.assertIn("after wrapper return", self.failures()["first"][0])
        self.assertEqual([], self.failures()["second"])

    def test_standalone_collect_rechecks_saved_cases_and_updates_junit(self):
        self.run_suite()
        self.events.extend(self.late_calls())
        with self.assertRaisesRegex(AssertionError, "final collection"):
            self.collect_suite()
        self.assertIn("after wrapper return", self.failures()["first"][0])
        self.assertEqual([], self.failures()["second"])

    def test_delayed_valid_activity_and_repeated_collection_preserve_success(self):
        self.deliver_at = 6
        self.late_events = [{**entry, "sequence": entry["sequence"] - 2.5}
                            for entry in self.late_calls()]
        self.run_suite()
        self.collect_suite()
        self.collect_suite()
        self.assertEqual({"first": [], "second": []}, self.failures())

    def test_existing_failures_remain_single_and_do_not_disappear_on_collection(self):
        self.deliver_at, self.late_events = 1, self.late_calls()
        with self.assertRaises(AssertionError):
            self.run_suite()
        with self.assertRaises(AssertionError):
            self.collect_suite()
        self.assertEqual(1, len(self.failures()["first"]))

    def test_mismatched_case_metadata_cannot_validate_a_different_run(self):
        self.run_suite()
        path = self.artifacts / "case-assertions.json"
        saved = json.loads(path.read_text())
        saved["runId"] = "previous"
        save(path, saved)
        with self.assertRaisesRegex(AssertionError, "different deployed run"):
            self.collect_suite()

    def prepare_warm_collection(self):
        for request, base, status in [("warm-initial", 0, "PENDING"), ("warm-final", 10, "SUCCEEDED")]:
            for kind, offset in [("WRAPPER_ENTER", 1), ("ROOT_ENTER", 2), ("ROOT_EXIT", 3),
                                 ("SNAPSHOT", 4), ("WRAPPER_RETURN", 5)]:
                self.events.append(event(kind, base + offset, request, marker="warm", status=status,
                                         fixture="default2", runId="run", executionArn="arn:warm",
                                         liveTasks=0, liveRoots=0, queued=0, threads=20))
        final_snapshot = next(entry for entry in self.events
                              if entry["kind"] == "SNAPSHOT" and entry["requestId"] == "warm-final")
        self.events.remove(final_snapshot)
        suite = ET.Element("testsuite", name="LMI cloud lifecycle")
        node = ET.SubElement(suite, "testcase", classname="LmiCloudLifecycle", name="warm")
        ET.ElementTree(suite).write(self.artifacts / "junit.xml")
        cloud = SimpleNamespace(manifest=self.manifest, invocations=[{
            "marker": "warm", "fixture": "default2", "scenario": "success", "warmEnvironment": "jvm",
            "warmRequests": [("warm", "jvm", "warm-initial"), ("warm", "jvm", "warm-final")]}])
        with patch("cloud_suite.ARTIFACTS", self.artifacts):
            save_case_assertions(cloud, [(node, 0, 1)])
        return final_snapshot

    def test_standalone_collect_rejects_a_final_warm_snapshot_that_never_arrives(self):
        self.prepare_warm_collection()
        with patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"):
            with self.assertRaisesRegex(AssertionError, "final collection"):
                self.collect_suite()
        node = ET.parse(self.artifacts / "junit.xml").getroot().find("testcase/error")
        self.assertEqual("CollectionError", node.get("type"))
        self.assertIn("warm-final", node.get("message"))
        self.assertLess(self.refresh_count, 40, "Missing evidence must exhaust one bounded collection window")

    def test_standalone_collect_waits_for_the_matching_final_warm_snapshot(self):
        self.late_events = [self.prepare_warm_collection()]
        self.deliver_at = len(FIXTURES) + 2
        with patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"):
            self.collect_suite()
        self.assertEqual({"warm": []}, self.failures())
        self.assertEqual(self.deliver_at, self.refresh_count)

    def test_saved_warm_request_identity_cannot_disappear_from_collected_evidence(self):
        self.prepare_warm_collection()
        self.events = [entry for entry in self.events if entry["requestId"] != "warm-final"]
        with patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"):
            with self.assertRaisesRegex(AssertionError, "final collection"):
                self.collect_suite()
        self.assertIn("warm-final", self.failures()["warm"][0])


if __name__ == "__main__":
    unittest.main()
