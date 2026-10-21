# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Post-stop settlement must include an already-running retry's bounded residual work."""
from concurrent.futures import Future
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud
from cloud_suite import cleanup_case
from test_evidence import event


class CleanupResidualBudgetTest(unittest.TestCase):
    def run_cleanup(self, hold_millis=80000, retry_start=61, exits=True, scenario="stubborn", delivery_delay=0):
        clock = [80.0]
        future = Future()
        future.set_result({})
        cloud = Mock()
        cloud.manifest = {"runId": "case"}
        cloud.invocations = []
        cloud.pool.submit.return_value = future
        item = Cloud.launch(cloud, "default2", scenario, "victim", hold_ms=hold_millis)
        events = []
        for request, start in (("original", 0), ("retry", retry_start)):
            boundaries = [("WRAPPER_ENTER", start), ("ROOT_ENTER", start + .1),
                          ("TASK_ENTER", start + .2), ("ROOT_EXIT", start + 5),
                          ("WRAPPER_RETURN", start + 5.1)]
            if exits:
                boundaries.append(("TASK_EXIT", start + min(hold_millis, 120000) / 1000))
            events.extend(event(kind, at, request, marker="victim", name="stubborn-child", status="THREW")
                          for kind, at in boundaries)
        cloud.refresh.side_effect = lambda fixture: [e for e in events
            if e["sequence"] + (delivery_delay if e["kind"] == "TASK_EXIT" else 0) <= clock[0]]
        def advance(seconds):
            clock[0] += seconds
        try:
            with patch("cloud_suite.time.monotonic", side_effect=lambda: clock[0]), \
                    patch("cloud_suite.time.sleep", side_effect=advance):
                cleanup_case(cloud, "default2", 0, 90)
        finally:
            self.finished_at = clock[0]
        cloud.stop_all.assert_called_once_with([item], seconds=90)
        return clock[0]

    def test_running_stubborn_retry_settles_before_required_quiet_period(self):
        # Retry admitted at61 exits at141, after the original has already exited at80.
        self.assertEqual(161, self.run_cleanup())

    def test_retry_exit_logs_can_arrive_after_the_physical_hold_finishes(self):
        self.assertEqual(181, self.run_cleanup(delivery_delay=20))

    def test_large_requested_hold_uses_the_fixture_120_second_cap(self):
        # A retry admitted just before stop can consume almost the full capped hold.
        self.assertEqual(219, self.run_cleanup(hold_millis=900000, retry_start=79))

    def test_never_exiting_stubborn_retry_still_fails_cleanup(self):
        with self.assertRaisesRegex(AssertionError, "did not reach stable quiescence"):
            self.run_cleanup(exits=False)
        self.assertEqual(210, self.finished_at)

    def test_cleanup_budget_is_capped_even_for_an_oversized_hold(self):
        with self.assertRaisesRegex(AssertionError, "did not reach stable quiescence"):
            self.run_cleanup(hold_millis=900000, exits=False)
        self.assertEqual(250, self.finished_at)

    def test_ordinary_case_does_not_receive_stubborn_residual_allowance(self):
        with self.assertRaisesRegex(AssertionError, "did not reach stable quiescence"):
            self.run_cleanup(scenario="timeout")
        self.assertEqual(130, self.finished_at)


if __name__ == "__main__":
    unittest.main()
