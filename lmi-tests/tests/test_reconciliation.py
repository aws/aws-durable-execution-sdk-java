# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""An invoke transport failure does not prove Lambda rejected the execution."""
from concurrent.futures import Future
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud, CollectionError


class ReconciliationTest(unittest.TestCase):
    function = "arn:aws:lambda:us-west-2:123456789012:function:fixture:$LATEST.PUBLISHED"
    execution = "arn:aws:lambda:us-west-2:123456789012:function:fixture/durable-execution/run-silent/uuid"

    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.artifacts = Path(directory.name)
        self.cloud = Cloud({"runId": "run", "functions": {"default1": {"arn": self.function}}}, self.artifacts)
        self.addCleanup(self.cloud.close)
        self.failure = RuntimeError("all invoke responses lost")
        with patch.object(self.cloud, "_invoke_request", side_effect=self.failure), self.assertRaises(RuntimeError):
            self.cloud._invoke("default1", {"runId": "run", "marker": "silent", "scenario": "timeout"})
        future = Future()
        future.set_exception(self.failure)
        self.item = {"marker": "silent", "fixture": "default1", "future": future}

    def match(self, arn=None):
        return {"DurableExecutionName": "run-silent", "DurableExecutionArn": arn or self.execution}

    def report(self):
        return json.loads((self.artifacts / "invocations/silent.json").read_text())

    @patch("cloud_support.aws")
    def test_cleanup_recovers_and_stops_acceptance_without_hiding_invoke_failure(self, api):
        api.side_effect = [{"DurableExecutions": [self.match()]}, {"Status": "RUNNING"}, {}, {"Status": "STOPPED"}]
        with self.assertRaisesRegex(CollectionError, "all invoke responses lost"):
            self.cloud.finish(self.item)
        final = self.cloud.stop(self.item, seconds=5)
        self.assertEqual("STOPPED", final["Status"])
        self.assertEqual(["list-durable-executions-by-function", "get-durable-execution",
                          "stop-durable-execution", "get-durable-execution"], [c.args[1] for c in api.call_args_list])
        self.assertEqual(self.execution, self.item["arn"])
        self.assertEqual("REQUEST_FAILED", self.report()["state"])
        self.assertEqual(self.execution, self.report()["executionArn"])
        self.assertEqual("all invoke responses lost", self.report()["error"])
        with self.assertRaisesRegex(CollectionError, "all invoke responses lost"):
            self.cloud.finish(self.item)

    @patch("cloud_support.aws", side_effect=RuntimeError("lookup unavailable"))
    def test_existing_diagnostics_reconcile_without_an_unnecessary_name_lookup(self, api):
        self.cloud.events = {("jvm", 1): {"marker": "silent", "executionArn": self.execution}}
        self.assertEqual(self.execution, self.cloud.reconcile_invocation(self.item, seconds=0))
        api.assert_not_called()
        self.assertEqual(self.execution, self.report()["executionArn"])

    @patch("cloud_support.time.sleep")
    @patch("cloud_support.aws")
    def test_lookup_waits_for_visibility_and_paginates_the_exact_name_and_qualifier(self, api, sleep):
        api.side_effect = [{"DurableExecutions": []}, {"DurableExecutions": [], "NextMarker": "page-2"},
                           {"DurableExecutions": [self.match()]}]
        self.assertEqual(self.execution, self.cloud.reconcile_invocation(self.item, seconds=5))
        requests = [call.args[2] for call in api.call_args_list]
        self.assertTrue(all(request["DurableExecutionName"] == "run-silent" for request in requests))
        self.assertTrue(all(request["Qualifier"] == "$LATEST.PUBLISHED" for request in requests))
        self.assertTrue(all(request["FunctionName"] == self.function.rsplit(":", 1)[0] for request in requests))
        self.assertEqual([None, None, "page-2"], [request.get("Marker") for request in requests])
        self.assertTrue(all("--no-paginate" in call.kwargs["extra"] for call in api.call_args_list))
        sleep.assert_called_once()

    @patch("cloud_support.aws", return_value={"DurableExecutions": []})
    def test_unresolved_acceptance_is_bounded_and_keeps_the_request_error(self, api):
        with self.assertRaisesRegex(CollectionError, "Could not reconcile"):
            self.cloud.reconcile_invocation(self.item, seconds=0)
        api.assert_called_once()
        self.assertEqual("all invoke responses lost", self.report()["error"])
        self.assertIn("reconciliationError", self.report())
        self.assertNotIn("arn", self.item)

    @patch("cloud_support.aws")
    def test_ambiguous_or_unrelated_matches_cannot_select_an_execution_to_stop(self, api):
        for matches in ([self.match(), self.match("arn:another")],
                        [{**self.match(), "DurableExecutionName": "other-name"}]):
            api.return_value = {"DurableExecutions": matches}
            with self.subTest(matches=matches), self.assertRaises(CollectionError):
                self.cloud.reconcile_invocation(self.item, seconds=0)
            self.assertNotIn("arn", self.item)

    @patch("cloud_support.aws", side_effect=RuntimeError("lookup unavailable"))
    def test_lookup_failure_is_recorded_without_replacing_the_invoke_error(self, api):
        with self.assertRaisesRegex(CollectionError, "lookup unavailable"):
            self.cloud.reconcile_invocation(self.item, seconds=0)
        report = self.report()
        self.assertEqual("all invoke responses lost", report["error"])
        self.assertIn("lookup unavailable", report["reconciliationError"])


if __name__ == "__main__":
    unittest.main()
