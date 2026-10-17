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
from cloud_support import Cloud
from cloud_suite import execute_case, warm_case
from test_evidence import event


class DelayedWarmEnvironmentTest(unittest.TestCase):
    def run_case(self, late_original=False):
        cloud = Mock()
        cloud.manifest = {"runId": "case", "invocationTimeout": 60}
        cloud.invocations, visible = [], []
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
            visible.extend(trace(marker, marker, scenario, len(cloud.invocations) * 100))
            return item
        cloud.launch.side_effect = launch
        cloud.refresh.side_effect = lambda fixture: list(visible)
        cloud.events_for.side_effect = lambda item: [e for e in visible if e["marker"] == item["marker"]]
        cloud.poll.side_effect = lambda *args, **kwargs: Cloud.poll(cloud, *args, **kwargs)
        cloud.history.return_value = [{"Name": name, "Id": name, "EventType": kind}
                                     for name, kind in [("success", "StepSucceeded"), ("failure", "StepFailed"),
                                                        ("resume", "WaitSucceeded")]]
        def stop(*args, **kwargs):
            if late_original:
                visible.extend(trace(cloud.invocations[0]["marker"], "late-original", "success", 0,
                                     environment="original-jvm", status="THREW"))
        cloud.stop_all.side_effect = stop
        with patch("cloud_suite.uuid.uuid4", side_effect=lambda: SimpleNamespace(hex="case" + str(next(ids)))), \
                patch("cloud_suite.time.monotonic", side_effect=count()), patch("cloud_suite.time.sleep"):
            return execute_case(cloud, "default2", lambda: warm_case(cloud, "default2"))

    def test_delayed_other_jvm_cannot_supply_an_earlier_warm_request(self):
        primary, cleanup = self.run_case(late_original=True)
        self.assertIsInstance(primary, AssertionError)
        self.assertIn("replacement environment", str(primary))
        self.assertIsNone(cleanup)

    def test_one_jvm_warm_replay_sequence_still_passes(self):
        self.assertEqual((None, None), self.run_case())


if __name__ == "__main__":
    unittest.main()
