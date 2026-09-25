# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_suite import FIXTURES, OWNER, deploy_fixtures, ensure_bucket, template, wait_for_idle_functions
from cloud_support import Cloud, PreconditionError


class PersistenceTest(unittest.TestCase):
    def manifest(self, run_id="run-1", code_key="code/digest.jar"):
        return {"stack": "persistent", "runId": run_id, "codeKey": code_key, "functions": {},
                "account": "123456789012", "region": "us-west-2", "role": "role", "bucket": "bucket",
                "provider": "provider", "commit": "sha", "invocationTimeout": 60, "codeSha256": "digest"}

    def stack(self, status="CREATE_COMPLETE"):
        return {"StackStatus": status, "Tags": [{"Key": "Suite", "Value": OWNER}],
                "Outputs": [{"OutputKey": key, "OutputValue": "function:" + key + ":$LATEST.PUBLISHED"}
                            for key in FIXTURES]}

    @patch("cloud_suite.save")
    @patch("cloud_suite.wait_stack")
    @patch("cloud_suite.wait_for_idle_functions")
    @patch("cloud_suite.record_fixture")
    @patch("cloud_suite.verify_function_scaling")
    @patch("cloud_suite.existing_stack")
    @patch("cloud_suite.aws")
    def test_later_run_updates_same_stack_and_resource_names(self, api, existing, verify, record, idle, wait, save):
        existing.side_effect = [None, self.stack(), self.stack(), self.stack("UPDATE_COMPLETE")]
        def record_one(manifest, fixture, arn):
            manifest["functions"][fixture] = {"arn": arn}
        record.side_effect = record_one
        deploy_fixtures(self.manifest(), [])
        deploy_fixtures(self.manifest("run-2", "code/new-digest.jar"), [])
        self.assertEqual(["create-stack", "update-stack"], [call.args[1] for call in api.call_args_list])
        self.assertEqual(["persistent", "persistent"], [call.args[2]["StackName"] for call in api.call_args_list])
        first, second = [json.loads(call.args[2]["TemplateBody"]) for call in api.call_args_list]
        self.assertEqual("DO_NOTHING", api.call_args_list[0].args[2]["OnFailure"])
        self.assertEqual(first["Outputs"], second["Outputs"])
        self.assertEqual(first["Resources"].keys(), second["Resources"].keys())
        for fixture in FIXTURES:
            before = first["Resources"][fixture + "Function"]["Properties"]
            after = second["Resources"][fixture + "Function"]["Properties"]
            self.assertEqual(before["FunctionName"], after["FunctionName"])
            self.assertNotEqual(before["Code"]["S3Key"], after["Code"]["S3Key"])
            self.assertEqual("run-2", after["Environment"]["Variables"]["LMI_TEST_RUN_ID"])
        self.assertNotIn("TimeoutInMinutes", api.call_args_list[1].args[2])
        self.assertEqual(["CREATE_COMPLETE", "UPDATE_COMPLETE"], [call.args[1] for call in wait.call_args_list])
        self.assertEqual(10, verify.call_count)
        idle.assert_called_once()

    @patch("cloud_suite.save")
    @patch("cloud_suite.wait_stack")
    @patch("cloud_suite.wait_for_idle_functions")
    @patch("cloud_suite.record_fixture")
    @patch("cloud_suite.verify_function_scaling")
    @patch("cloud_suite.existing_stack")
    @patch("cloud_suite.aws", side_effect=RuntimeError("No updates are to be performed."))
    def test_unchanged_stack_still_gets_readback_without_recreation(self, api, existing, verify, record, idle, wait, save):
        existing.return_value = self.stack()
        deploy_fixtures(self.manifest(), [])
        self.assertEqual("update-stack", api.call_args.args[1])
        wait.assert_not_called()
        self.assertEqual(5, verify.call_count)

    @patch("cloud_suite.save")
    @patch("cloud_suite.existing_stack")
    @patch("cloud_suite.aws")
    def test_failed_persistent_stack_is_retained_for_diagnosis(self, api, existing, save):
        existing.return_value = self.stack("ROLLBACK_COMPLETE")
        with self.assertRaisesRegex(PreconditionError, "retained"):
            deploy_fixtures(self.manifest(), [])
        api.assert_not_called()

    @patch("cloud_suite.save")
    @patch("cloud_suite.existing_stack")
    @patch("cloud_suite.aws")
    def test_other_stacks_are_not_adopted(self, api, existing, save):
        existing.return_value = {"StackStatus": "CREATE_COMPLETE", "Tags": []}
        with self.assertRaisesRegex(PreconditionError, "not owned"):
            deploy_fixtures(self.manifest(), [])
        api.assert_not_called()

    @patch("cloud_suite.save")
    @patch("cloud_suite.existing_stack", return_value=None)
    @patch("cloud_suite.aws", side_effect=RuntimeError("create failed"))
    def test_failed_creation_does_not_trigger_deletion(self, api, existing, save):
        with self.assertRaisesRegex(RuntimeError, "create failed"):
            deploy_fixtures(self.manifest(), [])
        self.assertEqual(["create-stack"], [call.args[1] for call in api.call_args_list])
        self.assertEqual("DO_NOTHING", api.call_args.args[2]["OnFailure"])

    @patch("cloud_suite.aws")
    def test_expired_budget_prevents_changes(self, api):
        with self.assertRaisesRegex(PreconditionError, "budget exhausted"):
            deploy_fixtures(self.manifest(), [], seconds=0)
        api.assert_not_called()

    @patch("cloud_suite.save")
    @patch("cloud_suite.time.sleep")
    @patch("cloud_suite.aws")
    def test_previous_executions_are_allowed_to_finish_before_update(self, api, sleep, save):
        stack = {"Outputs": [self.stack()["Outputs"][0]]}
        api.side_effect = [{"DurableExecutions": [{"DurableExecutionArn": "old", "Status": "RUNNING"}]},
                           {"DurableExecutions": []}]
        wait_for_idle_functions(stack)
        self.assertEqual(1, sleep.call_count)
        self.assertTrue(all(call.args[1] == "list-durable-executions-by-function" for call in api.call_args_list))
        self.assertEqual(["RUNNING"], api.call_args.args[2]["Statuses"])
        self.assertEqual("function:default1", api.call_args.args[2]["FunctionName"])
        self.assertEqual("$LATEST.PUBLISHED", api.call_args.args[2]["Qualifier"])

    @patch("cloud_suite.save")
    @patch("cloud_suite.aws")
    def test_still_running_execution_prevents_update_without_stopping_it(self, api, save):
        api.return_value = {"DurableExecutions": [{"DurableExecutionArn": "old", "Status": "RUNNING"}]}
        with self.assertRaisesRegex(PreconditionError, "code was not updated"):
            wait_for_idle_functions({"Outputs": [self.stack()["Outputs"][0]]}, seconds=0)
        self.assertTrue(all(call.args[1].startswith("list-") for call in api.call_args_list))

    @patch("cloud_suite.save")
    @patch("cloud_suite.aws")
    def test_incomplete_execution_listing_cannot_be_treated_as_idle(self, api, save):
        api.return_value = {"DurableExecutions": [], "NextMarker": "another-page"}
        with self.assertRaisesRegex(PreconditionError, "finish checking"):
            wait_for_idle_functions({"Outputs": [self.stack()["Outputs"][0]]}, seconds=0)

    @patch("cloud_suite.aws")
    def test_owned_bucket_is_reused_and_only_control_objects_expire(self, api):
        api.side_effect = [{}, {"TagSet": [{"Key": "Suite", "Value": OWNER}, {"Key": "Stack", "Value": "persistent"}]}, {}, {}]
        ensure_bucket(self.manifest(), [])
        operations = [call.args[1] for call in api.call_args_list]
        self.assertNotIn("create-bucket", operations)
        self.assertNotIn("delete-bucket", operations)
        lifecycle = api.call_args.args[2]["LifecycleConfiguration"]["Rules"]
        self.assertEqual([{"Prefix": "control/"}], [rule["Filter"] for rule in lifecycle])

    @patch("cloud_suite.aws")
    def test_absent_bucket_is_created_once(self, api):
        api.side_effect = [RuntimeError("404 Not Found"), {}, {}, {}, {}]
        ensure_bucket(self.manifest(), [])
        self.assertEqual(1, sum(call.args[1] == "create-bucket" for call in api.call_args_list))

    @patch("cloud_suite.aws", side_effect=RuntimeError("Access denied"))
    def test_bucket_access_error_does_not_trigger_creation(self, api):
        with self.assertRaisesRegex(RuntimeError, "Access denied"):
            ensure_bucket(self.manifest(), [])
        self.assertEqual(1, api.call_count)

    @patch("cloud_support.aws", return_value="https://example.test/control")
    def test_control_objects_are_scoped_to_the_run(self, api):
        with TemporaryDirectory() as directory:
            cloud = Cloud({"runId": "run-2", "bucket": "bucket"}, directory)
            try:
                cloud.gate("barrier")
                self.assertEqual("control/run-2/barrier", api.call_args_list[0].args[2]["Key"])
                self.assertIn("s3://bucket/control/run-2/barrier", api.call_args_list[1].kwargs["extra"])
            finally:
                cloud.close()
