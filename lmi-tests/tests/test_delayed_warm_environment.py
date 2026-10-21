# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Late request logs cannot move a warm-run identity to a replacement JVM."""
from concurrent.futures import Future
from itertools import count
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud, CollectionError
from cloud_suite import cleanup_case, execute_case, warm_case
from test_evidence import event


class DelayedWarmEnvironmentTest(unittest.TestCase):
    def run_case(self, late_original=False, duplicate_body=False, reverse_snapshots=False,
                 final_threads=20, delayed_snapshot=False, missing_snapshot=False,
                 delayed_after_cleanup=False, wrong_snapshot_request=False, cleanup_failure=False,
                 pending_threads=20, pending_queue=0):
        cloud = Mock()
        cloud.manifest = {"runId": "case", "invocationTimeout": 60}
        cloud.invocations, visible = [], []
        cloud.cleanup_finished = False
        self.collection_refreshes = 0
        future = Future()
        future.set_result({})
        cloud.pool.submit.return_value = future
        ids = count()
        def trace(marker, request, scenario, base, environment="jvm", status=None):
            records = []
            def add(kind, **extra):
                records.append(event(kind, base + len(records), request, environment, marker=marker,
                                     epochMillis=base + len(records), **extra))
            add("WRAPPER_ENTER"); add("ROOT_ENTER")
            names = ["success", "failure"] if scenario == "replay" else [scenario]
            for name in names:
                add("TASK_ENTER", name=name)
                add("BODY", name=name, value=marker)
                add("TASK_EXIT", name=name)
            if scenario == "replay":
                add("STORED_FAILURE", message="expected:" + marker)
            add("ROOT_EXIT")
            add("SNAPSHOT", liveTasks=0, liveRoots=0, queued=0, threads=20)
            add("WRAPPER_RETURN", status=status or ("PENDING" if scenario == "replay" else
                                                   "FAILED" if scenario == "failure" else "SUCCEEDED"))
            if scenario == "replay":
                request += "-resume"
                add("WRAPPER_ENTER"); add("ROOT_ENTER")
                add("STORED_FAILURE", message="expected:" + marker)
                add("ROOT_EXIT")
                add("SNAPSHOT", liveTasks=0, liveRoots=0, queued=0, threads=20)
                add("WRAPPER_RETURN", status="SUCCEEDED")
            return records
        def launch(fixture, scenario, marker, **kwargs):
            item = Cloud.launch(cloud, fixture, scenario, marker, **kwargs)
            records = trace(marker, marker, scenario, len(cloud.invocations) * 100)
            if len(cloud.invocations) == 9:
                snapshots = [entry for entry in records if entry["kind"] == "SNAPSHOT"]
                snapshots[0].update(threads=pending_threads, queued=pending_queue)
                snapshots[-1]["threads"] = final_threads
                if reverse_snapshots:
                    records.remove(snapshots[0])
                    records.append(snapshots[0])
                if delayed_snapshot or missing_snapshot or delayed_after_cleanup or wrong_snapshot_request:
                    records.remove(snapshots[-1])
                    cloud.delayed_snapshot = snapshots[-1]
                    if wrong_snapshot_request:
                        records.append({**snapshots[-1], "requestId": snapshots[0]["requestId"]})
            visible.extend(records)
            return item
        cloud.launch.side_effect = launch
        def refresh(fixture):
            if delayed_after_cleanup and cloud.cleanup_finished:
                self.collection_refreshes += 1
                if self.collection_refreshes == 2:
                    visible.append(cloud.delayed_snapshot)
            return list(visible)
        cloud.refresh.side_effect = refresh
        cloud.events_for.side_effect = lambda item: [e for e in visible if e["marker"] == item["marker"]]
        cloud.poll.side_effect = lambda *args, **kwargs: Cloud.poll(cloud, *args, **kwargs)
        cloud.history.return_value = [{"Name": name, "Id": name, "EventType": kind}
                                     for name, kind in [("success", "StepSucceeded"), ("failure", "StepFailed"),
                                                        ("resume", "WaitSucceeded")]]
        def stop(*args, **kwargs):
            if duplicate_body:
                replay = next(item for item in cloud.invocations if item["scenario"] == "replay")
                resumed = next(entry for entry in visible if entry["marker"] == replay["marker"]
                               and entry["kind"] == "WRAPPER_RETURN" and entry["status"] == "SUCCEEDED")
                visible.append(event("BODY", resumed["sequence"] - 1.5, resumed["requestId"],
                                     marker=replay["marker"], name="success", value=replay["marker"]))
            if delayed_snapshot:
                visible.append(cloud.delayed_snapshot)
            if late_original:
                visible.extend(trace(cloud.invocations[0]["marker"], "late-original", "success", 0,
                                     environment="original-jvm", status="THREW"))
        cloud.stop_all.side_effect = stop
        def cleanup(*args, **kwargs):
            cleanup_case(*args, **kwargs)
            cloud.cleanup_finished = True
            if cleanup_failure:
                raise RuntimeError("cleanup failed")
        with patch("cloud_suite.uuid.uuid4", side_effect=lambda: SimpleNamespace(hex="case" + str(next(ids)))), \
                patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"), \
                patch("cloud_suite.cleanup_case", side_effect=cleanup):
            return execute_case(cloud, "default2", lambda: warm_case(cloud, "default2"))

    def test_delayed_other_jvm_cannot_supply_an_earlier_warm_request(self):
        primary, cleanup = self.run_case(late_original=True)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("replacement environment", str(primary))
        self.assertIsNone(cleanup)

    def test_one_jvm_warm_replay_sequence_still_passes(self):
        self.assertEqual((None, None), self.run_case())

    def test_replay_body_delivered_during_cleanup_cannot_run_twice(self):
        primary, cleanup = self.run_case(duplicate_body=True)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("body ran 2 times", str(primary))
        self.assertIsNone(cleanup)

    def test_last_warm_snapshot_uses_jvm_sequence_not_delivery_order(self):
        primary, cleanup = self.run_case(reverse_snapshots=True, final_threads=50)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("Thread growth", str(primary))
        self.assertIsNone(cleanup)

    def test_reordered_snapshots_within_the_existing_tolerance_pass(self):
        self.assertEqual((None, None), self.run_case(reverse_snapshots=True, final_threads=36))

    def test_final_warm_snapshot_delivered_during_cleanup_is_rechecked(self):
        primary, cleanup = self.run_case(delayed_snapshot=True, final_threads=50)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("Thread growth", str(primary))
        self.assertIsNone(cleanup)

    def test_missing_final_warm_snapshot_is_collection_failure_after_cleanup(self):
        primary, cleanup = self.run_case(missing_snapshot=True)
        self.assertIsInstance(primary, CollectionError)
        self.assertIn("Incomplete warm wrapper-return snapshots", str(primary))
        self.assertIsNone(cleanup)

    def test_delayed_final_snapshot_is_collected_before_resource_comparison(self):
        self.assertEqual((None, None), self.run_case(delayed_after_cleanup=True))
        self.assertEqual(2, self.collection_refreshes)

    def test_delayed_final_snapshot_cannot_hide_thread_growth(self):
        primary, cleanup = self.run_case(delayed_after_cleanup=True, final_threads=50)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("Thread growth", str(primary))
        self.assertIsNone(cleanup)

    def test_pending_request_snapshot_cannot_stand_in_for_final_request(self):
        primary, cleanup = self.run_case(wrong_snapshot_request=True)
        self.assertIsInstance(primary, CollectionError)
        self.assertIsNone(cleanup)

    def test_proven_warm_resource_failure_is_preserved_when_cleanup_also_fails(self):
        primary, cleanup = self.run_case(final_threads=50, cleanup_failure=True)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("Thread growth", str(primary))
        self.assertIsInstance(cleanup, RuntimeError)
        self.assertIn("cleanup failed", str(cleanup))

    def test_pending_thread_count_does_not_replace_delayed_final_endpoint(self):
        self.assertEqual((None, None), self.run_case(delayed_after_cleanup=True, pending_threads=50))

    def test_partial_zero_work_violation_is_preserved_with_cleanup_failure(self):
        primary, cleanup = self.run_case(missing_snapshot=True, pending_queue=1, cleanup_failure=True)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("queued work accumulated", str(primary))
        self.assertIsInstance(cleanup, RuntimeError)


if __name__ == "__main__":
    unittest.main()
