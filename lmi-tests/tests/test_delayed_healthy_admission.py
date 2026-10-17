# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Late original heartbeats must not let a retry become the healthy admission proof."""
from concurrent.futures import Future
from itertools import count
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud
from cloud_suite import assert_healthy_request_success, execute_case, healthy_peers
from test_evidence import event


class DelayedHealthyAdmissionTest(unittest.TestCase):
    def trace(self, request, start, status, environment="jvm"):
        return [event(kind, start + index, request, environment, marker="case-0-0",
                      epochMillis=start + index, status=status, name="held-step")
                for index, kind in enumerate(["WRAPPER_ENTER", "ROOT_ENTER", "TASK_ENTER", "HEARTBEAT",
                                              "TASK_EXIT", "ROOT_EXIT", "WRAPPER_RETURN"])]

    def run_case(self, delayed_original=None):
        retry = self.trace("retry", 20, "SUCCEEDED")
        visible = list(retry)
        cloud = Mock()
        cloud.manifest = {"runId": "case", "invocationTimeout": 60}
        cloud.invocations = []
        future = Future()
        future.set_result({})
        cloud.pool.submit.return_value = future
        cloud.launch.side_effect = lambda *args, **kwargs: Cloud.launch(cloud, *args, **kwargs)
        cloud.refresh.side_effect = lambda fixture: list(visible)
        cloud.events_for.side_effect = lambda item: [e for e in visible if e["marker"] == item["marker"]]
        cloud.poll.side_effect = lambda *args, **kwargs: Cloud.poll(cloud, *args, **kwargs)
        def settle(*args, **kwargs):
            visible.extend(delayed_original or [])
        cloud.stop_all.side_effect = settle
        def case():
            peers, gate = healthy_peers(cloud, "default2", "jvm", 1, "case")
            self.assertEqual("retry", peers[0]["requestId"], "Only retry logs were available at admission")
            cloud.gate(gate, release=True)
            cloud.finish(peers[0])
            assert_healthy_request_success(cloud, "default2", peers[0])
        with patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"):
            result = execute_case(cloud, "default2", case)
        cloud.stop_all.assert_called_once()
        return result

    def test_failed_original_heartbeat_arriving_during_cleanup_invalidates_retry_admission(self):
        for environment in ("jvm", "replacement"):
            with self.subTest(environment=environment):
                primary, cleanup = self.run_case(self.trace("original", 0, "THREW", environment))
                self.assertIsInstance(primary, AssertionError)
                self.assertIn("Original healthy admission", str(primary))
                self.assertIsNone(cleanup)

    def test_changed_original_identity_cannot_reuse_retry_occupancy_even_if_both_succeeded(self):
        primary, cleanup = self.run_case(self.trace("original", 0, "SUCCEEDED"))
        self.assertIsInstance(primary, AssertionError)
        self.assertIsNone(cleanup)

    def test_valid_single_request_admission_is_preserved(self):
        self.assertEqual((None, None), self.run_case())

    def test_delayed_earlier_heartbeat_from_same_request_does_not_change_identity(self):
        earlier = event("HEARTBEAT", 22.5, "retry", marker="case-0-0", epochMillis=22.5)
        self.assertEqual((None, None), self.run_case([earlier]))


if __name__ == "__main__":
    unittest.main()
