# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Final collection cannot replace an admitted fixed-pool participant with a retry."""
from itertools import count
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_suite import execute_case, fixed_case, runtime_requests_drained
from test_evidence import event
import test_fixed_placement as fixed_controls


class DelayedFixedMembershipTest(unittest.TestCase):
    def run_case(self, retry=False):
        driver = fixed_controls.FixedPlacementTest()
        driver.setUp()
        cloud = driver.cloud
        cloud.manifest = {"invocationTimeout": 60}
        cloud.invocations = []
        hidden, added = [], []
        def plan(items):
            events = driver.evidence(items)
            if retry:
                events = [e for e in events if not (e["marker"] == items[0]["marker"] and e["kind"] == "PROGRESS")]
                for entry in events:
                    if entry["marker"] == items[0]["marker"] and entry["kind"] == "WRAPPER_RETURN":
                        entry["status"] = "THREW"
            # Setup observes the admitted original cohort before any retry diagnostics.
            return [events, []]
        driver.plans = [plan]
        def launch(*args, **kwargs):
            item = driver.launch(*args, **kwargs)
            cloud.invocations.append(item)
            return item
        cloud.launch.side_effect = launch
        def finish(item):
            if retry and not added:
                added.append(True)
                timeline = [("WRAPPER_ENTER", 13.1), ("ROOT_ENTER", 13.2),
                            ("BARRIER_ENTER", 13.3), ("BARRIER_PASSED", 13.4),
                            ("TASK_ENTER", 13.41), ("TASK_EXIT", 13.43), ("PROGRESS", 13.5),
                            ("ROOT_EXIT", 13.6), ("WRAPPER_RETURN", 13.7)]
                records = [event(kind, sequence, "retry", marker=item["marker"],
                                 name="success", status="SUCCEEDED") for kind, sequence in timeline]
                hidden.extend(e for e in records if e["kind"].startswith("BARRIER_"))
                driver.visible.extend(e for e in records if e not in hidden)
        cloud.finish.side_effect = finish
        cloud.stop_all.side_effect = lambda *args, **kwargs: driver.visible.extend(hidden)
        def settle(cloud, fixture, markers, **kwargs):
            self.assertTrue(runtime_requests_drained(driver.visible, markers))
            return driver.visible
        with patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"), \
                patch("cloud_suite.wait_for_runtime_quiescence", side_effect=settle):
            return execute_case(cloud, "fixed2", lambda: fixed_case(cloud, "fixed2", "fixed"))

    def test_retry_barrier_arriving_after_case_assertions_cannot_replace_original_progress(self):
        primary, cleanup = self.run_case(retry=True)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("cohort", str(primary))
        self.assertIsNone(cleanup)

    def test_original_fixed_cohort_still_passes(self):
        self.assertEqual((None, None), self.run_case())


if __name__ == "__main__":
    unittest.main()
