# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Delayed fixed-pool diagnostics must never turn a failed cohort into a retry."""
from collections import deque
from concurrent.futures import Future
from itertools import count
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud, CollectionError, PreconditionError
from cloud_suite import fixed_case
from test_evidence import event


class FixedPlacementTest(unittest.TestCase):
    def setUp(self):
        self.cloud = Mock(spec=Cloud)
        self.visible, self.cohorts, self.actions = [], [], []
        self.batches = deque()
        self.plans = []
        self.settlement = None
        self.cloud.launch.side_effect = self.launch
        self.cloud.refresh.side_effect = self.refresh
        self.cloud.events_for.side_effect = lambda item: [
            e for e in self.visible if e["marker"] == item["marker"]]
        # Use the real bounded polling loop, with deterministic time and log batches.
        self.cloud.poll.side_effect = lambda *a, **kw: Cloud.poll(self.cloud, *a, **kw)
        self.cloud.finish_all.side_effect = lambda *a, **kw: self.actions.append("terminal")

    def launch(self, fixture, scenario, marker, **kwargs):
        future = Future()
        future.set_result({})
        item = {"marker": marker, "future": future}
        self.actions.append("launch")
        if marker.endswith("-0"):
            self.cohorts.append([item])
        else:
            self.cohorts[-1].append(item)
            index = len(self.cohorts) - 1
            plan = self.plans[index] if index < len(self.plans) else self.complete_plan
            self.batches.extend(plan(self.cohorts[-1]))
        return item

    def refresh(self, fixture):
        if self.batches:
            self.visible.extend(self.batches.popleft())
        return list(self.visible)

    def settle(self, cloud, fixture, markers):
        self.actions.append("settled")
        if self.settlement:
            self.settlement(markers)
        return list(self.visible)

    def evidence(self, items, misplaced=False):
        result = []
        for index, item in enumerate(items):
            marker = item["marker"]
            environment = f"jvm-{index}" if misplaced else "jvm"
            timeline = [("WRAPPER_ENTER", index), ("ROOT_ENTER", 2 + index),
                        ("BARRIER_ENTER", 4 + index)]
            if not misplaced:
                timeline += [("BARRIER_PASSED", 6 + index), ("PROGRESS", 8 + index)]
            timeline += [("ROOT_EXIT", 10 + index), ("WRAPPER_RETURN", 12 + index)]
            result += [event(kind, sequence, marker, environment,
                             status="FAILED" if misplaced else "SUCCEEDED")
                       for kind, sequence in timeline]
        return result

    def complete_plan(self, items):
        return [self.evidence(items)]

    def delayed_plan(self, items, escape=False):
        events = self.evidence(items)
        wrappers = [e for e in events if e["kind"].startswith("WRAPPER_")]
        diagnostics = [e for e in events if not e["kind"].startswith("WRAPPER_")]
        if escape:
            diagnostics.append(event("ESCAPE", 9, items[0]["marker"]))
        return [wrappers, diagnostics]

    def run_case(self):
        with patch("cloud_support.time.monotonic", side_effect=count()), \
                patch("cloud_support.time.sleep"), \
                patch("cloud_suite.wait_for_runtime_quiescence", side_effect=self.settle) as settle:
            fixed_case(self.cloud, "fixed2", "fixed")
        return settle

    def test_wrapper_logs_before_barrier_and_escape_cannot_retry_into_expanded_pool(self):
        self.plans = [lambda items: self.delayed_plan(items, escape=True)]
        with self.assertRaisesRegex(AssertionError, "escape"):
            self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)
        self.cloud.finish.assert_not_called()

    def test_delayed_admission_logs_can_still_prove_clean_progress(self):
        self.plans = [self.delayed_plan]
        self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)
        self.assertEqual(2, self.cloud.refresh.call_count)
        self.assertEqual(2, self.cloud.finish.call_count)
        self.cloud.finish_all.assert_not_called()

    def test_missing_setup_evidence_cannot_be_retried(self):
        self.plans = [lambda items: [[e for e in self.evidence(items)
                                    if e["kind"].startswith("WRAPPER_")]]]
        with self.assertRaisesRegex(CollectionError, "Evidence deadline"):
            self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)

    def test_same_jvm_without_barrier_outcome_is_not_a_placement_miss(self):
        self.plans = [lambda items: [[e for e in self.evidence(items)
                                    if e["kind"] not in {"BARRIER_PASSED", "PROGRESS"}]]]
        with self.assertRaises(CollectionError):
            self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)

    def test_distinct_jvms_must_settle_before_one_clean_retry(self):
        self.plans = [lambda items: [self.evidence(items, misplaced=True)]]
        settle = self.run_case()
        self.assertEqual(["launch", "launch", "terminal", "settled", "launch", "launch", "settled"],
                         self.actions)
        self.cloud.finish_all.assert_called_once_with(self.cohorts[0], expected="FAILED")
        self.assertEqual({item["marker"] for cohort in self.cohorts for item in cohort},
                         settle.call_args.args[2])

    def test_escape_from_an_earlier_cohort_discovered_during_final_settlement_fails(self):
        self.plans = [lambda items: [self.evidence(items, misplaced=True)]]

        def late_escape(markers):
            if len(self.cohorts) == 2:
                self.visible.append(event("ESCAPE", 9, self.cohorts[0][0]["marker"], "jvm-0"))

        self.settlement = late_escape
        with self.assertRaisesRegex(AssertionError, "escape"):
            self.run_case()
        self.assertEqual(4, self.cloud.launch.call_count)

    def test_unsettled_misplaced_cohort_cannot_retry(self):
        self.plans = [lambda items: [self.evidence(items, misplaced=True)]]
        self.settlement = Mock(side_effect=AssertionError("runtime work not settled"))
        with self.assertRaisesRegex(AssertionError, "not settled"):
            self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)

    def test_unexpected_durable_success_after_placement_miss_cannot_retry(self):
        self.plans = [lambda items: [self.evidence(items, misplaced=True)]]
        self.cloud.finish_all.side_effect = AssertionError("durable status SUCCEEDED, expected FAILED")
        with self.assertRaisesRegex(AssertionError, "expected FAILED"):
            self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)

    def test_partial_admission_cannot_be_classified_as_placement_failure(self):
        def partial(items):
            events = self.evidence(items, misplaced=True)
            events.append(event("BARRIER_PASSED", 6, items[0]["marker"], "jvm-0"))
            return [events]

        self.plans = [partial]
        with self.assertRaises(CollectionError):
            self.run_case()
        self.assertEqual(2, self.cloud.launch.call_count)

    def test_three_confirmed_placement_misses_remain_a_setup_error(self):
        self.plans = [lambda items: [self.evidence(items, misplaced=True)]] * 3
        with self.assertRaisesRegex(PreconditionError, "could not meet"):
            self.run_case()
        self.assertEqual(6, self.cloud.launch.call_count)
        self.assertEqual(3, self.cloud.finish_all.call_count)


if __name__ == "__main__":
    unittest.main()
