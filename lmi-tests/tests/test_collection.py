# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Collect accepted invocations independently of whether fixture logging started."""
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_suite import collect
from cloud_support import Cloud, save
from test_evidence import event


class CollectionTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.artifacts = Path(directory.name)
        self.manifest = {"runId": "current", "commit": "sha", "functions": {
            "default1": {"arn": "arn:function", "logGroup": "logs"}}}
        save(self.artifacts / "manifest.json", self.manifest)
        self.logs = []
        self.api = Mock(side_effect=self.response)

    def response(self, service, operation, *args, **kwargs):
        if operation == "filter-log-events":
            return {"events": self.logs}
        if operation == "get-durable-execution-history":
            return {"Events": [{"EventType": "ExecutionFailed"}]}
        if operation == "get-durable-execution":
            return {"Status": "FAILED"}
        raise AssertionError(f"Unexpected API: {service} {operation}")

    def accepted(self, marker="silent", run_id="current"):
        cloud = Cloud(self.manifest, self.artifacts)
        try:
            with patch.object(cloud, "_invoke_request", return_value={
                    "headers": {"DurableExecutionArn": "arn:" + marker}, "body": ""}):
                cloud._invoke("default1", {"runId": run_id, "marker": marker, "scenario": "timeout"})
        finally:
            cloud.close()

    def run_collection(self):
        with patch("cloud_suite.ARTIFACTS", self.artifacts), \
             patch("cloud_suite.MANIFEST", self.artifacts / "manifest.json"), \
             patch("cloud_suite.aws", self.api), patch("cloud_support.aws", self.api):
            collect()

    def assert_collected(self, marker="silent"):
        self.assertEqual({"Status": "FAILED"}, json.loads(
            (self.artifacts / "executions" / (marker + ".json")).read_text()))
        self.assertEqual([{"EventType": "ExecutionFailed"}], json.loads(
            (self.artifacts / "histories" / (marker + ".json")).read_text()))

    def test_accepted_invocation_without_diagnostics_has_status_and_history(self):
        self.accepted()
        self.run_collection()
        self.assert_collected()

    def test_merge_deduplicates_arns_and_ignores_other_runs_and_incomplete_records(self):
        self.accepted()
        self.accepted("old", "previous")
        save(self.artifacts / "invocations/unfinished.json", {
            "runId": "current", "marker": "unfinished", "state": "STARTED"})
        diagnostic = event("WRAPPER_ENTER", 1, marker="silent", executionArn="arn:silent",
                           runId="current", deploymentRunId="current", commit="sha")
        self.logs = [{"eventId": "one", "message": "LMI_TEST " + json.dumps(diagnostic)}]
        self.run_collection()
        histories = [call.args[2]["DurableExecutionArn"] for call in self.api.call_args_list
                     if call.args[1] == "get-durable-execution-history"]
        self.assertEqual(["arn:silent"], histories)
        self.assert_collected()
        self.assertFalse((self.artifacts / "executions/old.json").exists())

    def test_log_collection_failure_does_not_hide_accepted_invocation(self):
        self.accepted()
        def fail_logs(service, operation, *args, **kwargs):
            if operation == "filter-log-events":
                raise RuntimeError("logs unavailable")
            return self.response(service, operation, *args, **kwargs)
        self.api.side_effect = fail_logs
        with self.assertRaisesRegex(AssertionError, "logs unavailable"):
            self.run_collection()
        self.assert_collected()

    def test_partial_artifact_is_reported_while_other_invocations_are_collected(self):
        self.accepted()
        (self.artifacts / "invocations/partial.json").write_text('{"state":')
        with self.assertRaisesRegex(AssertionError, "partial.json"):
            self.run_collection()
        self.assert_collected()


if __name__ == "__main__":
    unittest.main()
