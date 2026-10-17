# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""A drained probe or discarded attempt still has to satisfy normal SDK return ordering."""
from concurrent.futures import Future
from itertools import count
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud
from cloud_suite import execute_case, runtime_requests_drained
from test_evidence import event


class CaseRequestLifecycleTest(unittest.TestCase):
    def trace(self, request="original", offset=0, late_task=False, late_root=False, status="SUCCEEDED"):
        result = [event(kind, offset + sequence, request, marker="case-item", name="held-step", status=status)
                  for kind, sequence in [("WRAPPER_ENTER", 1), ("ROOT_ENTER", 2), ("TASK_ENTER", 3),
                                         ("TASK_EXIT", 6 if late_task else 4),
                                         ("ROOT_EXIT", 7 if late_root else 5), ("WRAPPER_RETURN", 6)]]
        returned = next(e for e in result if e["kind"] == "WRAPPER_RETURN")
        returned.update(tasks=1 if late_task else 0, rootExited=not late_root)
        if late_task:
            next(e for e in result if e["kind"] == "TASK_EXIT")["sequence"] = offset + 7
        return result

    def run_case(self, events, scenario="probe", primary=None, cleanup_failure=None, delayed_logs=False):
        cloud = Mock()
        cloud.manifest = {"runId": "case", "invocationTimeout": 60}
        cloud.invocations = []
        future = Future()
        future.set_result({})
        cloud.pool.submit.return_value = future
        visible = list(events) if not delayed_logs else [e for e in events if e["kind"] != "ROOT_EXIT"]
        refresh_count = [0]
        def refresh(fixture):
            refresh_count[0] += 1
            if delayed_logs and refresh_count[0] == 2:
                visible[:] = events
            return list(visible)
        cloud.refresh.side_effect = refresh
        cloud.events_for.side_effect = lambda item: [e for e in visible if e["marker"] == item["marker"]]
        cloud.stop_all.side_effect = cleanup_failure
        def case():
            Cloud.launch(cloud, "default2", scenario, "case-item")
            if primary is not None:
                raise primary
        self.assertTrue(runtime_requests_drained(events, {"case-item"}), "Cleanup can physically settle this trace")
        with patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"):
            result = execute_case(cloud, "default2", case)
        cloud.stop_all.assert_called_once()
        if delayed_logs:
            self.assertGreaterEqual(refresh_count[0], 2)
        return result

    def test_qualifying_probe_cannot_return_with_a_live_held_task(self):
        primary, cleanup = self.run_case(self.trace(late_task=True))
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("live invocation tasks", str(primary))
        self.assertIsNone(cleanup)

    def test_every_probe_retry_is_checked_after_drainage(self):
        primary, cleanup = self.run_case(self.trace() + self.trace("retry", 10, late_task=True))
        self.assertIsInstance(primary, AssertionError)
        self.assertIsNone(cleanup)

    def test_discarded_placement_attempt_cannot_hide_late_root_exit(self):
        events = self.trace(late_root=True)
        events.append(event("PLACEMENT_MISS", 2.5, "original", marker="case-item"))
        primary, cleanup = self.run_case(events, scenario="hold")
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("before root exit", str(primary))
        self.assertIsNone(cleanup)

    def test_stubborn_victim_keeps_its_explicit_residual_task_exception(self):
        self.assertEqual((None, None), self.run_case(self.trace(late_task=True, status="THREW"), scenario="stubborn"))

    def test_cooperative_victim_does_not_borrow_the_stubborn_exception(self):
        primary, cleanup = self.run_case(self.trace(late_task=True, status="THREW"), scenario="timeout")
        self.assertIsInstance(primary, AssertionError)
        self.assertIsNone(cleanup)

    def test_successful_probe_keeps_its_existing_acceptance(self):
        self.assertEqual((None, None), self.run_case(self.trace()))

    def test_delayed_valid_logs_are_collected_before_final_case_acceptance(self):
        self.assertEqual((None, None), self.run_case(self.trace(), delayed_logs=True))

    def test_original_cleanup_failure_is_not_replaced(self):
        original = RuntimeError("stop request failed")
        primary, cleanup = self.run_case(self.trace(late_task=True), cleanup_failure=original)
        self.assertIsNone(primary)
        self.assertIs(cleanup, original)

    def test_original_case_failure_is_not_replaced(self):
        original = AssertionError("original deadline violation")
        primary, cleanup = self.run_case(self.trace(late_task=True), primary=original)
        self.assertIs(primary, original)
        self.assertIsNone(cleanup)


if __name__ == "__main__":
    unittest.main()
