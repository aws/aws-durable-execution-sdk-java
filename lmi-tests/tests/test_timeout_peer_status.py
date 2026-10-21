# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""A healthy durable retry cannot replace success of the original admitted request."""
from pathlib import Path
from itertools import count
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_suite import assert_timeout_case
from test_evidence import event


class TimeoutPeerStatusTest(unittest.TestCase):
    def evidence(self, original_status, retry_environment="jvm"):
        def trace(marker, request, timeline, status, environment="jvm"):
            return [event(kind, seconds, request, environment, marker=marker,
                          nanos=seconds * 1_000_000_000, epochMillis=seconds * 1000,
                          remainingMillis=60_000, status=status, name="held-step")
                    for kind, seconds in timeline]
        return (
            trace("victim", "timed-out", [("WRAPPER_ENTER", 0), ("ROOT_ENTER", 1),
                  ("TASK_ENTER", 2), ("INTERRUPTED", 62), ("TASK_EXIT", 63),
                  ("ROOT_EXIT", 64), ("WRAPPER_RETURN", 65)], "THREW")
            + trace("peer", "healthy-original", [("WRAPPER_ENTER", 10), ("ROOT_ENTER", 11),
                    ("TASK_ENTER", 12), ("HEARTBEAT", 13), ("HEARTBEAT", 61),
                    ("TASK_EXIT", 70), ("ROOT_EXIT", 71), ("WRAPPER_RETURN", 72)], original_status)
            + trace("peer", "healthy-retry", [("WRAPPER_ENTER", 73), ("ROOT_ENTER", 74),
                    ("TASK_ENTER", 75), ("TASK_EXIT", 76), ("ROOT_EXIT", 77),
                    ("WRAPPER_RETURN", 78)], "SUCCEEDED", retry_environment)
            + trace("case-probe-0", "probe", [("TASK_ENTER", 66)], "SUCCEEDED"))

    def run_case(self, original_status, retry_environment="jvm", delayed_root=False):
        cloud = Mock()
        events = self.evidence(original_status, retry_environment)
        root_exit = next(e for e in events if e["requestId"] == "healthy-original" and e["kind"] == "ROOT_EXIT")
        if delayed_root:
            events.remove(root_exit)
        cloud.events = dict(enumerate(events))
        refresh_count = 0
        def refresh(fixture):
            nonlocal refresh_count
            refresh_count += 1
            if delayed_root and refresh_count == 2:
                events.append(root_exit)
                cloud.events[len(cloud.events)] = root_exit
            return list(events)
        cloud.refresh.side_effect = refresh
        cloud.raw_logs = {}
        cloud.events_for.side_effect = lambda item: [e for e in events if e["marker"] == item["marker"]]
        cloud.launch.side_effect = lambda fixture, scenario, marker, **kwargs: {"marker": marker}
        cloud.history.return_value = []
        # Durable execution succeeded via retry even when the original healthy request failed.
        cloud.finish.return_value = {"Status": "SUCCEEDED"}
        ready = []
        def poll(fixture, predicate, **kwargs):
            result = predicate(events)
            ready.append(bool(result))
            self.assertTrue(result, "Final request-status assertions must not change evidence readiness")
            return result
        cloud.poll.side_effect = poll
        peer = {"marker": "peer", "requestId": "healthy-original", "environment": "jvm",
                "admittedNanos": 13_000_000_000}
        with patch("cloud_suite.wait_until_wall_time"), \
                patch("cloud_suite.healthy_peers", return_value=([peer], "healthy-gate")), \
                patch("cloud_suite.time.monotonic", side_effect=count(step=5)), \
                patch("cloud_suite.time.sleep"), patch("cloud_suite.save"):
            try:
                return assert_timeout_case(cloud, "default2", False, "case", 60, {"marker": "victim"})
            finally:
                self.assertGreaterEqual(len(ready), 3)
                self.assertTrue(all(ready))
                self.assertTrue(any(call.args == (peer,) for call in cloud.finish.call_args_list))

    def test_original_throw_then_successful_retry_is_an_isolation_failure(self):
        with self.assertRaisesRegex(AssertionError, "Original healthy request healthy-original"):
            self.run_case("THREW")

    def test_retry_in_another_jvm_cannot_supply_original_success(self):
        with self.assertRaisesRegex(AssertionError, "did not return SUCCEEDED"):
            self.run_case("THREW", retry_environment="replacement")

    def test_failed_or_pending_original_request_is_not_healthy_success(self):
        for status in ("FAILED", "PENDING"):
            with self.subTest(status=status), self.assertRaisesRegex(AssertionError, "did not return SUCCEEDED"):
                self.run_case(status)

    def test_delayed_original_peer_root_exit_is_collected_before_acceptance(self):
        self.assertEqual("jvm", self.run_case("SUCCEEDED", delayed_root=True))

    def test_successful_original_request_preserves_recovery_control(self):
        self.assertEqual("jvm", self.run_case("SUCCEEDED"))


if __name__ == "__main__":
    unittest.main()
