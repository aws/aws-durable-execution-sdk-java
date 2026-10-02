# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Request identity, suspension cleanup and distinct-execution counterexamples."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import PreconditionError, assert_lifecycle, assert_overlap
from cloud_suite import (assert_request_lifecycles, cleanup_progress_observed, overlap_case, replay_case, runtime_requests_drained,
                         wait_for_runtime_quiescence)
from test_evidence import event


class RequestActivityTest(unittest.TestCase):
    def trace(self, request="original", environment="jvm", offset=0):
        return [event(kind, offset + index, request, environment, marker="victim",
                      name="child", status="THREW")
                for index, kind in enumerate(["WRAPPER_ENTER", "ROOT_ENTER", "TASK_ENTER",
                                              "TASK_EXIT", "ROOT_EXIT", "WRAPPER_RETURN"], 1)]

    def test_activity_without_wrapper_logs_is_not_a_drained_request(self):
        for environment in ("jvm", "replacement"):
            for kind in ("ROOT_ENTER", "TASK_ENTER", "BODY", "HEARTBEAT"):
                activity = [event(kind, 7, "retry", environment, marker="victim", name="child")]
                for prior in ([], self.trace()):
                    with self.subTest(environment=environment, kind=kind, prior=bool(prior)):
                        for allow_no_entry in (False, True):
                            self.assertFalse(runtime_requests_drained(
                                prior + activity, "victim", allow_no_entry=allow_no_entry))

    def test_all_lifecycle_checks_include_requests_with_only_task_evidence(self):
        activity = [event("ROOT_ENTER", 7, "retry", marker="victim"),
                    event("TASK_ENTER", 8, "retry", marker="victim", name="child")]
        for allow_residual in (False, True):
            with self.subTest(residual=allow_residual):
                with self.assertRaisesRegex(AssertionError, "without wrapper entry"):
                    assert_lifecycle(self.trace() + activity, allow_residual=allow_residual)
                with self.assertRaisesRegex(AssertionError, "without wrapper entry"):
                    assert_request_lifecycles(
                        self.trace() + activity, "victim", allow_residual_tasks=allow_residual)

    def test_completed_api_calls_do_not_hide_missing_request_boundaries(self):
        events = self.trace() + [event(kind, index, "retry", marker="victim", callId=1)
                                for index, kind in [(7, "POLL_CALL"), (8, "POLL_EXIT")]]
        self.assertFalse(runtime_requests_drained(events, "victim", allow_no_entry=True))
        with self.assertRaisesRegex(AssertionError, "without wrapper entry"):
            assert_request_lifecycles(events, "victim")

    def test_completed_retries_are_valid_regardless_of_log_arrival_order(self):
        events = self.trace() + self.trace("retry", "replacement", 6)
        assert_request_lifecycles(events[::-1], "victim")
        self.assertTrue(runtime_requests_drained(events[::-1], "victim"))
        # Activity from a different case must not block this case's cleanup.
        events.append(event("TASK_ENTER", 13, "unrelated", name="live"))
        self.assertTrue(runtime_requests_drained(events, "victim"))
        assert_request_lifecycles(events, "victim")

    @patch("cloud_suite.time.sleep")
    @patch("cloud_suite.time.monotonic", side_effect=[0, 0, 1, 2, 3, 4, 5])
    def test_retry_activity_resets_quiet_period_before_wrapper_logs_arrive(self, monotonic, sleep):
        original, retry = self.trace(), self.trace("retry", offset=6)
        live = [e for e in retry if e["kind"] in {"ROOT_ENTER", "TASK_ENTER"}]
        cloud = Mock()
        cloud.refresh.side_effect = [original, original + live, original + live,
                                     original + retry, original + retry, original + retry]
        wait_for_runtime_quiescence(
            cloud, "default2", "victim", seconds=10, quiet_seconds=2, allow_no_entry=True)
        self.assertEqual(6, cloud.refresh.call_count)


class ExecutionOverlapTest(unittest.TestCase):
    def test_concurrent_retries_cannot_replace_another_execution(self):
        events = [event(kind, sequence, request, marker=marker)
                  for kind, sequence, request, marker in [
                      ("TASK_ENTER", 1, "original", "a"), ("TASK_ENTER", 2, "retry", "a"),
                      ("TASK_EXIT", 3, "original", "a"), ("TASK_EXIT", 4, "retry", "a"),
                      ("TASK_ENTER", 5, "other", "b"), ("TASK_EXIT", 6, "other", "b")]]
        with self.assertRaises(PreconditionError):
            assert_overlap(events, {"a", "b"}, 2)

    def test_exiting_one_retry_keeps_the_other_request_active(self):
        events = [event("TASK_ENTER", 1, "original", marker="a"),
                  event("TASK_ENTER", 2, "retry", marker="a"),
                  event("TASK_EXIT", 3, "original", marker="a"),
                  event("TASK_ENTER", 4, "other", marker="b"),
                  event("TASK_EXIT", 5, "retry", marker="a")]
        self.assertEqual("jvm", assert_overlap(events[::-1], {"a", "b"}, 2))


class OriginalEnvironmentTest(unittest.TestCase):
    @patch("cloud_suite.healthy_peers")
    def test_replayed_anchor_in_replacement_cannot_prove_original_recovery(self, peers):
        cloud = Mock()
        cloud.launch.return_value = {"marker": "anchor"}
        # The handler's first-invocation placement gate does not run on replay.
        cloud.poll.return_value = [event("HEARTBEAT", 5, "resume", "replacement", marker="anchor")]
        with self.assertRaisesRegex(AssertionError, "original environment"):
            overlap_case(cloud, "default2", target="original")
        peers.assert_not_called()

    @patch("cloud_suite.assert_lifecycle")
    @patch("cloud_suite.assert_overlap")
    @patch("cloud_suite.wait_returns")
    @patch("cloud_suite.wait_for_runtime_quiescence")
    @patch("cloud_suite.healthy_peers", return_value=([], "gate"))
    def test_matching_or_unspecified_target_uses_the_admitted_environment(self, peers, quiet, returns, overlap, lifecycle):
        for target in (None, "original"):
            cloud = Mock()
            cloud.launch.return_value = {"marker": "anchor"}
            cloud.poll.return_value = [event("HEARTBEAT", 5, environment="original", marker="anchor")]
            cloud.events = {}
            overlap_case(cloud, "default1", target=target)
            self.assertEqual("original", peers.call_args.args[2])
            self.assertEqual("original", overlap.call_args.args[3])


class SuspensionCleanupTest(unittest.TestCase):
    marker = "case-victim-0"

    def evidence(self):
        def record(kind, sequence, request="first", marker=self.marker, **extra):
            return event(kind, sequence, request, marker=marker, epochMillis=sequence, **extra)
        original = [record("WRAPPER_ENTER", 1), record("ROOT_ENTER", 2),
                    record("TASK_ENTER", 3, name="success"),
                    record("BODY", 4, name="success", value=self.marker),
                    record("TASK_EXIT", 5, name="success"), record("TASK_ENTER", 6, name="failure"),
                    record("BODY", 7, name="failure", value=self.marker), record("TASK_EXIT", 8, name="failure"),
                    record("STORED_FAILURE", 9, message="expected:" + self.marker),
                    record("CLEANUP_ENTER", 10), record("ROOT_EXIT", 13),
                    record("WRAPPER_RETURN", 14, status="PENDING")]
        resume = [record("WRAPPER_ENTER", 18, "resume"), record("ROOT_ENTER", 19, "resume"),
                  record("STORED_FAILURE", 20, "resume", message="expected:" + self.marker),
                  record("CLEANUP_ENTER", 21, "resume"), record("CLEANUP_EXIT", 22, "resume"),
                  record("ROOT_EXIT", 23, "resume"), record("WRAPPER_RETURN", 24, "resume", status="SUCCEEDED")]
        anchor = [record(kind, sequence, "healthy", "case-healthy", name="held-step", status="SUCCEEDED")
                  for kind, sequence in [("WRAPPER_ENTER", -3), ("ROOT_ENTER", -2), ("TASK_ENTER", -1),
                                         ("HEARTBEAT", 0), ("HEARTBEAT", 15),
                                         ("TASK_EXIT", 25), ("ROOT_EXIT", 26), ("WRAPPER_RETURN", 27)]]
        # Original cleanup is [10,12]; its exit log arrives after the resumed cleanup [21,22].
        return original + resume + [record("CLEANUP_EXIT", 12)] + anchor

    def heartbeat(self, request="healthy", environment="jvm"):
        return event("HEARTBEAT", 11, request, environment, marker="case-healthy", epochMillis=11)

    def run_case(self, events, delayed=()):
        cloud = Mock()
        cloud.events = dict(enumerate(events))
        cloud.launch.side_effect = lambda fixture, scenario, marker, **kwargs: {"marker": marker}
        cloud.events_for.side_effect = lambda item: [e for e in cloud.events.values() if e["marker"] == item["marker"]]
        cloud.refresh.side_effect = lambda fixture: list(cloud.events.values())
        cloud.history.return_value = [{"Name": name, "Id": name, "EventType": kind} for name, kind in
                                      [("success", "StepSucceeded"), ("failure", "StepFailed"), ("resume", "WaitSucceeded")]]
        def poll(fixture, predicate, *args, **kwargs):
            result = predicate(list(cloud.events.values()))
            if not result:
                for e in delayed:
                    cloud.events[len(cloud.events)] = e
                result = predicate(list(cloud.events.values()))
            if not result:
                raise AssertionError("Evidence deadline exceeded")
            return result
        cloud.poll.side_effect = poll
        with patch("cloud_suite.uuid.uuid4", return_value=Mock(hex="case")), \
             patch("cloud_suite.time.monotonic", side_effect=[0, 0, 10]), patch("cloud_suite.time.sleep"):
            replay_case(cloud, "default2", "suspend")
        return cloud

    def test_heartbeat_between_cleanups_does_not_prove_progress_during_cleanup(self):
        with self.assertRaisesRegex(AssertionError, "Evidence deadline exceeded"):
            self.run_case(self.evidence())

    def test_original_cleanup_is_used_even_when_resume_logs_arrive_first(self):
        self.run_case(self.evidence() + [self.heartbeat()])

    def test_waits_for_the_matching_exit_and_original_peer_heartbeat(self):
        events = self.evidence()
        original_exit = next(e for e in events if e["kind"] == "CLEANUP_EXIT" and e["requestId"] == "first")
        events.remove(original_exit)
        cloud = self.run_case(events, delayed=[original_exit, self.heartbeat()])
        self.assertTrue(any(e["sequence"] == 11 for e in cloud.events.values()))
        self.assertEqual(2, len(cloud.poll.call_args.kwargs["items"]))

    def test_retry_or_replacement_heartbeat_cannot_prove_original_peer_progress(self):
        for request, environment in [("retry", "jvm"), ("healthy", "replacement")]:
            with self.subTest(request=request, environment=environment):
                with self.assertRaisesRegex(AssertionError, "Evidence deadline exceeded"):
                    self.run_case(self.evidence() + [self.heartbeat(request, environment)])

    def test_other_request_or_jvm_cannot_supply_the_original_cleanup_exit(self):
        for field, value in [("requestId", "resume"), ("environment", "replacement")]:
            events = self.evidence() + [self.heartbeat()]
            original_exit = next(e for e in events if e["kind"] == "CLEANUP_EXIT" and e["requestId"] == "first")
            original_exit[field] = value
            with self.subTest(field=field):
                self.assertFalse(cleanup_progress_observed(
                    events, self.marker, {"marker": "case-healthy", "requestId": "healthy", "environment": "jvm"}))


if __name__ == "__main__":
    unittest.main()
