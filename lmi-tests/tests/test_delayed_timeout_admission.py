# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""A later timeout retry cannot receive credit for an original request's late cleanup."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_suite import assert_case_request_lifecycles
from test_evidence import event
import test_timeout_peer_status as timeout_controls


class DelayedTimeoutAdmissionTest(unittest.TestCase):
    def collect_case(self, original_environment=None):
        class RetryEvidence(timeout_controls.TimeoutPeerStatusTest):
            def evidence(self, *args):
                events = super().evidence(*args)
                for entry in events:
                    entry["sequence"] += 80
                    entry["nanos"] += 80_000_000_000
                    entry["epochMillis"] += 80_000
                    if entry["marker"] == "victim":
                        entry["requestId"] = "retry"
                self.collected = events
                return events
        driver = RetryEvidence()
        captured = []
        def collect(cloud, fixture, markers, **kwargs):
            captured.append(cloud)
            if original_environment is not None:
                # This original request exceeded its 60s deadline + 5s despite otherwise correct lifecycle ordering.
                original = [event(kind, at, "original", original_environment, marker="victim", name="held-step",
                                  status="THREW", remainingMillis=60000,
                                  nanos=at * 1_000_000_000, epochMillis=at * 1000)
                            for kind, at in [("WRAPPER_ENTER", 0), ("ROOT_ENTER", 1), ("TASK_ENTER", 2),
                                             ("INTERRUPTED", 74), ("TASK_EXIT", 76), ("ROOT_EXIT", 77),
                                             ("WRAPPER_RETURN", 78)]]
                driver.collected.extend(original)
                for entry in original:
                    cloud.events[len(cloud.events)] = entry
            return driver.collected
        with patch("cloud_suite.wait_for_runtime_quiescence", side_effect=collect):
            self.assertEqual("jvm", driver.run_case("SUCCEEDED"))
        cloud = captured[0]
        # Reuse the actual victim item observed by the first driver poll, including its original pin.
        cloud.invocations = [cloud.poll.call_args_list[0].kwargs["items"][0]]
        return cloud

    def test_original_late_cleanup_cannot_be_hidden_by_later_timeout_retry(self):
        for environment in ("jvm", "replacement"):
            with self.subTest(environment=environment):
                cloud = self.collect_case(environment)
                with self.assertRaisesRegex(AssertionError, "Original timeout admission"):
                    assert_case_request_lifecycles(cloud, 0)

    def test_unchanged_original_timeout_request_keeps_existing_acceptance(self):
        assert_case_request_lifecycles(self.collect_case(), 0)


if __name__ == "__main__":
    unittest.main()
