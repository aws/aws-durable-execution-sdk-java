# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Overlap and in-flight probes must retain the originally admitted healthy anchor."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_suite import inflight_case, overlap_case
from test_evidence import event


class HealthyAnchorStatusTest(unittest.TestCase):
    def run_case(self, overlap, original_status, late_exit=False):
        def trace(marker, request, timeline, status):
            return [event(kind, sequence, request, marker=marker, epochMillis=sequence,
                          status=status, name="held-step") for kind, sequence in timeline]
        anchor = trace("case-anchor", "original", [("WRAPPER_ENTER", 0), ("ROOT_ENTER", 1),
                       ("TASK_ENTER", 2), ("HEARTBEAT", 3), ("TASK_EXIT", 10),
                       ("ROOT_EXIT", 11), ("WRAPPER_RETURN", 12)], original_status)
        retry = trace("case-anchor", "retry", [("WRAPPER_ENTER", 13), ("ROOT_ENTER", 14),
                      ("TASK_ENTER", 15), ("HEARTBEAT", 16), ("TASK_EXIT", 17),
                      ("ROOT_EXIT", 18), ("WRAPPER_RETURN", 19)], "SUCCEEDED")
        if late_exit:
            next(e for e in anchor if e["kind"] == "ROOT_EXIT")["sequence"] = 20
            next(e for e in anchor if e["kind"] == "WRAPPER_RETURN")["rootExited"] = False
        # In-flight cases name the anchor differently from overlap cases.
        if not overlap:
            for e in anchor + retry:
                e["marker"] = "case-healthy"
        other_marker = "peer" if overlap else "case-victim"
        other = trace(other_marker, "other", [("WRAPPER_ENTER", 4), ("ROOT_ENTER", 5),
                      ("TASK_ENTER", 6), ("TASK_EXIT", 7), ("ROOT_EXIT", 8),
                      ("WRAPPER_RETURN", 9)], "SUCCEEDED")
        # Retry logs may arrive first; the oldest heartbeat still pins the original request.
        events = retry + anchor + other
        cloud = Mock()
        cloud.events = dict(enumerate(events))
        cloud.events_for.side_effect = lambda item: [e for e in events if e["marker"] == item["marker"]]
        cloud.launch.side_effect = lambda fixture, scenario, marker, **kwargs: {"marker": marker}
        cloud.finish.return_value = {"Status": "SUCCEEDED"}
        def poll(fixture, predicate, *args, **kwargs):
            result = predicate(events)
            self.assertTrue(result)
            return result
        cloud.poll.side_effect = poll
        peer = {"marker": "peer", "requestId": "other", "environment": "jvm"}
        with patch("cloud_suite.uuid.uuid4", return_value=Mock(hex="case")), \
                patch("cloud_suite.healthy_peers", return_value=([peer], "peer-gate")), \
                patch("cloud_suite.wait_for_runtime_quiescence"):
            if overlap:
                overlap_case(cloud, "default2")
            else:
                inflight_case(cloud, "default2", "return-inflight")

    def test_original_anchor_throw_cannot_be_hidden_by_successful_retry(self):
        for overlap in (False, True):
            with self.subTest(overlap=overlap), self.assertRaisesRegex(AssertionError, "Original healthy request original"):
                self.run_case(overlap, "THREW")

    def test_healthy_inflight_anchor_cannot_exit_after_successful_wrapper_return(self):
        with self.assertRaisesRegex(AssertionError, "before root exit"):
            self.run_case(False, "SUCCEEDED", late_exit=True)

    def test_original_anchor_success_preserves_both_controls(self):
        for overlap in (False, True):
            with self.subTest(overlap=overlap):
                self.run_case(overlap, "SUCCEEDED")


if __name__ == "__main__":
    unittest.main()
