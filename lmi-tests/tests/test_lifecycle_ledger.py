# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Counterexamples for backend-call drainage, slot attribution and nested setup."""
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import api_calls_complete, assert_fixed, assert_lifecycle, assert_nested_reached, PreconditionError
from cloud_suite import fixed_case, recovery_admissions, runtime_requests_drained, timeout_evidence_ready, wait_for_runtime_quiescence
from test_evidence import event


class BackendCallLedgerTest(unittest.TestCase):
    def trace(self, api):
        return [event("WRAPPER_ENTER", 1), event("ROOT_ENTER", 2),
                event(api + "_CALL", 3, callId=1), event(api + "_EXIT", 4, callId=1),
                event("ROOT_EXIT", 5), event("WRAPPER_RETURN", 6, status="SUCCEEDED")]

    def check_incomplete(self, events):
        self.assertFalse(runtime_requests_drained(events, "a"))
        with self.assertRaisesRegex(AssertionError, "checkpoint/poll"):
            assert_lifecycle(events)

    def test_every_backend_call_needs_its_own_finally_exit(self):
        for api in ("CHECKPOINT", "POLL"):
            with self.subTest(api=api):
                complete = self.trace(api)
                self.check_incomplete([e for e in complete if e["kind"] != api + "_EXIT"])
                self.assertTrue(runtime_requests_drained(complete, "a"))
                assert_lifecycle(complete[::-1])

    def test_exit_after_return_is_not_lifecycle_success(self):
        for api in ("CHECKPOINT", "POLL"):
            trace = self.trace(api)
            trace[3]["sequence"] = 7
            self.check_incomplete(trace)

    def test_overlapping_calls_cannot_share_one_exit(self):
        trace = self.trace("CHECKPOINT")
        trace[3]["sequence"], trace[4]["sequence"], trace[5]["sequence"] = 5, 7, 8
        trace += [event("CHECKPOINT_CALL", 4, callId=2)]
        self.check_incomplete(trace)
        trace += [event("CHECKPOINT_EXIT", 6, callId=2)]
        self.assertTrue(runtime_requests_drained(trace, "a"))

    def test_invalid_or_mismatched_ids_never_balance_calls(self):
        for update in ({"callId": None}, {"callId": "1"}, {"callId": True}, {"callId": 0},
                       {"callId": 2}, {"kind": "POLL_EXIT"}, {"requestId": "retry"},
                       {"marker": "other"}, {"environment": "replacement"}, {"sequence": 3}):
            with self.subTest(update=update):
                trace = self.trace("CHECKPOINT")
                trace[3].update(update)
                self.check_incomplete(trace)

    def test_duplicate_calls_exits_and_reused_ids_are_invalid(self):
        for extra in (event("CHECKPOINT_CALL", 3, callId=1), event("CHECKPOINT_EXIT", 4, callId=1),
                      event("POLL_CALL", 5, callId=1)):
            self.check_incomplete(self.trace("CHECKPOINT") + [extra])

    def test_call_ids_are_scoped_to_request_and_environment(self):
        trace = self.trace("CHECKPOINT")
        self.assertTrue(api_calls_complete(trace + [{**e, "requestId": "retry"} for e in trace]
                                               + [{**e, "environment": "other"} for e in trace]))

    @patch("cloud_suite.time.sleep")
    @patch("cloud_suite.time.monotonic", side_effect=[0, 0, 1, 2, 3, 4])
    def test_missing_exit_resets_cleanup_quiet_period(self, monotonic, sleep):
        complete = self.trace("POLL")
        no_exit = [e for e in complete if e["kind"] != "POLL_EXIT"]
        cloud = Mock()
        cloud.refresh.side_effect = [complete, no_exit, complete, complete, complete]
        wait_for_runtime_quiescence(cloud, "default2", "a", seconds=10, quiet_seconds=2)
        self.assertEqual(5, cloud.refresh.call_count)


class RecoverySlotTest(unittest.TestCase):
    deadline = 60_000_000_000

    def evidence(self, early_peer_exit=False):
        # One coherent JVM timeline: the original peer crosses the deadline, but
        # may then free its slot before the probe while the victim is still stuck.
        timeline = [("WRAPPER_ENTER", 1, "victim"), ("ROOT_ENTER", 2, "victim"),
                    ("TASK_ENTER", 3, "victim"), ("WRAPPER_ENTER", 30, "peer"),
                    ("ROOT_ENTER", 31, "peer"), ("TASK_ENTER", 32, "peer"),
                    ("HEARTBEAT", 59, "peer"), ("HEARTBEAT", 60.2, "peer"),
                    ("TASK_ENTER", 62, "probe"), ("INTERRUPTED", 64, "victim"),
                    ("TASK_EXIT", 64.1, "victim"), ("ROOT_EXIT", 64.2, "victim"),
                    ("WRAPPER_RETURN", 64.3, "victim")]
        end = 61 if early_peer_exit else 69
        timeline += [("TASK_EXIT", end, "peer"), ("ROOT_EXIT", end + .1, "peer"),
                     ("WRAPPER_RETURN", end + .2, "peer")]
        return [event(kind, i + 1, request, nanos=int(seconds * 1e9), name="held-step",
                      status="THREW" if request == "victim" or early_peer_exit else "SUCCEEDED")
                for i, (kind, seconds, request) in enumerate(sorted(timeline, key=lambda t: t[1]))]

    def admissions(self, events, peers=None):
        return recovery_admissions(events, "jvm", peers or [
            {"marker": "peer", "requestId": "peer", "environment": "jvm"}],
            [{"marker": "probe"}], self.deadline)

    def test_probe_cannot_recover_a_healthy_peers_prematurely_freed_slot(self):
        events = self.evidence(early_peer_exit=True)
        assert_lifecycle([e for e in events if e["marker"] in {"peer", "victim"}])
        self.assertEqual([], self.admissions(events))
        cloud = Mock(raw_logs={})
        self.assertFalse(timeout_evidence_ready(cloud, events, "victim", "victim", "jvm",
                         [{"marker": "peer", "requestId": "peer", "environment": "jvm"}],
                         [{"marker": "probe"}], self.deadline))

    def test_peer_task_and_wrapper_cover_the_probe_admission(self):
        self.assertEqual(1, len(self.admissions(self.evidence()[::-1])))

    def test_missing_or_delayed_peer_boundaries_are_not_proof_of_occupancy(self):
        for kind in ("WRAPPER_ENTER", "TASK_ENTER", "TASK_EXIT", "WRAPPER_RETURN"):
            with self.subTest(kind=kind):
                events = self.evidence()
                self.assertEqual([], self.admissions([e for e in events
                                 if e["marker"] != "peer" or e["kind"] != kind]))
                self.assertEqual(1, len(self.admissions(events)))

    def test_retry_or_replacement_cannot_supply_the_missing_peer_exit(self):
        for change in ({"requestId": "retry"}, {"environment": "other"}):
            events = self.evidence()
            for e in events:
                if e["marker"] == "peer" and e["kind"] in {"TASK_EXIT", "WRAPPER_RETURN"}:
                    e.update(change)
            self.assertEqual([], self.admissions(events))

    def test_all_peers_must_hold_their_slots_at_the_same_probe(self):
        events = self.evidence()
        another = [{**e, "marker": "peer2", "requestId": "peer2"} for e in events if e["marker"] == "peer"]
        peers = [{"marker": name, "requestId": name, "environment": "jvm"} for name in ("peer", "peer2")]
        combine = lambda traces: [{**e, "sequence": i} for i, e in enumerate(sorted(traces, key=lambda e: e["nanos"]))]
        self.assertEqual(1, len(self.admissions(combine(events + another), peers)))
        another = [{**e, "marker": "peer2", "requestId": "peer2"}
                   for e in self.evidence(True) if e["marker"] == "peer"]
        self.assertEqual([], self.admissions(combine(events + another), peers))

    def test_probe_budget_and_target_are_enforced(self):
        for change in ({"environment": "other"}, {"nanos": self.deadline + 8_000_000_001}):
            events = self.evidence()
            next(e for e in events if e["marker"] == "probe").update(change)
            self.assertEqual([], self.admissions(events))

    def test_zero_peer_fixture_still_requires_target_probe(self):
        probe = event("TASK_ENTER", 1, "probe", name="held-step", nanos=self.deadline + 1)
        self.assertEqual([probe], recovery_admissions([probe], "jvm", [], [{"marker": "probe"}], self.deadline))


class NestedBarrierEvidenceTest(unittest.TestCase):
    @patch("cloud_suite.wait_returns")
    def test_driver_waits_for_delayed_child_setup_failure_before_classifying_it(self, wait_returns):
        cloud = Mock()
        events = []

        def launch(fixture, scenario, marker, **kwargs):
            events.append(event("BARRIER_PASSED", len(events) + 1, marker))
            return {"marker": marker}

        def poll(fixture, predicate, **kwargs):
            self.assertFalse(predicate(events))
            events.append(event("CHILD_BARRIER_FAILED", 3, events[0]["marker"]))
            self.assertTrue(predicate(events))

        cloud.launch.side_effect = launch
        cloud.events_for.side_effect = lambda item: [e for e in events if e["marker"] == item["marker"]]
        cloud.poll.side_effect = poll
        with self.assertRaisesRegex(PreconditionError, "child barrier"):
            fixed_case(cloud, "nested2", "nested")
        cloud.finish.assert_not_called()

    def evidence(self):
        # Slow setup is not time spent trying to run the nested coordinators.
        timeline = [("BARRIER_ENTER", 1, "a"), ("BARRIER_ENTER", 2, "b"),
                    ("BARRIER_PASSED", 3, "a"), ("BARRIER_PASSED", 4, "b"),
                    ("CHILD_BARRIER_ENTER", 5, "a"), ("CHILD_BARRIER_ENTER", 10, "b"),
                    ("CHILD_BARRIER_PASSED", 11, "a"), ("NESTED_WORK_START", 11.1, "a"),
                    ("PROGRESS", 11.2, "a"), ("CHILD_BARRIER_PASSED", 11.3, "b"),
                    ("NESTED_WORK_START", 12, "b"), ("PROGRESS", 13, "b")]
        return [event(kind, i, request, nanos=int(seconds * 1e9))
                for i, (kind, seconds, request) in enumerate(timeline)]

    def test_children_establish_contention_before_either_can_progress(self):
        events = self.evidence()
        assert_nested_reached(events, {"a", "b"})
        assert_fixed(events, {"a", "b"}, progress_start="CHILD_BARRIER_PASSED")
        with self.assertRaisesRegex(AssertionError, "within budget"):
            assert_fixed(events, {"a", "b"})

    def test_root_barrier_alone_or_broken_child_barrier_is_not_nested_setup(self):
        for events in ([e for e in self.evidence() if not e["kind"].startswith("CHILD_BARRIER")],
                       self.evidence() + [event("CHILD_BARRIER_FAILED", 15)]):
            with self.assertRaises(PreconditionError):
                assert_nested_reached(events, {"a", "b"})

    def test_retry_cannot_complete_the_original_child_barrier(self):
        events = self.evidence()
        events[9]["requestId"] = "retry"
        with self.assertRaises(PreconditionError):
            assert_nested_reached(events, {"a", "b"})

    def test_escape_after_child_barrier_still_fails_progress(self):
        events = self.evidence() + [event("ESCAPE", 20)]
        assert_nested_reached(events, {"a", "b"})
        with self.assertRaisesRegex(AssertionError, "starved"):
            assert_fixed(events, {"a", "b"}, progress_start="CHILD_BARRIER_PASSED")

    def test_later_participant_does_not_get_a_new_progress_budget(self):
        events = self.evidence()
        events[9]["nanos"] = 18_000_000_000
        events[-1]["nanos"] = 20_000_000_000
        with self.assertRaisesRegex(AssertionError, "within budget"):
            assert_fixed(events, {"a", "b"}, progress_start="CHILD_BARRIER_PASSED")


class FixtureBuildTest(unittest.TestCase):
    def test_fixture_parent_and_sdk_dependency_use_the_current_reactor(self):
        root = Path(__file__).resolve().parents[2]
        ns = {"p": "http://maven.apache.org/POM/4.0.0"}
        parent = ET.parse(root / "pom.xml").getroot()
        fixture = ET.parse(root / "lmi-tests/pom.xml").getroot()
        self.assertEqual(parent.findtext("p:version", namespaces=ns),
                         fixture.findtext("p:parent/p:version", namespaces=ns))
        sdk = [d for d in fixture.findall("p:dependencies/p:dependency", ns)
               if d.findtext("p:artifactId", namespaces=ns) == "aws-durable-execution-sdk-java"]
        self.assertEqual(1, len(sdk))
        self.assertEqual("${project.version}", sdk[0].findtext("p:version", namespaces=ns))


if __name__ == "__main__":
    unittest.main()
