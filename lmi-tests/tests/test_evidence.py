# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
import json
from concurrent.futures import Future
from pathlib import Path
import sys
import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import (Cloud, CollectionError, PreconditionError, assert_fixed, assert_lifecycle,
                           assert_overlap, assert_replay, diagnostic, platform_timeout, scrub)
from cloud_suite import (FIXTURES, cases_for_fixture, cleanup_timeout_case, request_trace,
                         residual_outcome_observed, runtime_requests_drained, timeout_case,
                         verify_function_scaling, run_tests, template, wait_until_wall_time)
from unittest.mock import Mock, patch


def event(kind, sequence, request="a", environment="jvm", **extra):
    return {"kind": kind, "sequence": sequence, "nanos": sequence * 1_000_000,
            "requestId": request, "marker": request, "environment": environment,
            "rootExited": True, "tasks": 0, **extra}


class EvidenceTest(unittest.TestCase):
    def test_simultaneous_requests_in_different_jvms_are_not_concurrency_evidence(self):
        events = [event("TASK_ENTER", 1), event("TASK_ENTER", 1, "b", "other")]
        with self.assertRaises(PreconditionError):
            assert_overlap(events, {"a", "b"}, 2)

    def test_sequential_requests_in_same_jvm_are_not_overlap(self):
        events = [event("TASK_ENTER", 1), event("TASK_EXIT", 2), event("TASK_ENTER", 3, "b")]
        with self.assertRaises(PreconditionError):
            assert_overlap(events, {"a", "b"}, 2)

    def test_multiple_tasks_from_one_request_do_not_count_as_multiple_invocations(self):
        with self.assertRaises(PreconditionError):
            assert_overlap([event("TASK_ENTER", 1), event("TASK_ENTER", 2)], {"a"}, 2)

    def test_requires_original_environment_for_recovery(self):
        events = [event("TASK_ENTER", 1, "a", "replacement"), event("TASK_ENTER", 2, "b", "replacement")]
        with self.assertRaises(PreconditionError):
            assert_overlap(events, {"a", "b"}, 2, "original")

    def test_positive_same_jvm_overlap(self):
        events = [event("TASK_ENTER", 1), event("TASK_ENTER", 2, "b"), event("TASK_EXIT", 3)]
        self.assertEqual("jvm", assert_overlap(events, {"a", "b"}, 2))

    def test_rejects_pending_before_actual_root_exit(self):
        events = [event("WRAPPER_RETURN", 1, status="PENDING", rootExited=False), event("ROOT_EXIT", 2)]
        with self.assertRaisesRegex(AssertionError, "before root exit"):
            assert_lifecycle(events)

    def test_missing_markers_are_not_a_pass(self):
        with self.assertRaises(AssertionError):
            assert_lifecycle([])
        with self.assertRaises(AssertionError):
            assert_lifecycle([event("WRAPPER_RETURN", 1, status="PENDING")])

    def test_log_arrival_order_does_not_change_causal_order(self):
        assert_lifecycle([event("WRAPPER_RETURN", 2, status="PENDING"), event("ROOT_EXIT", 1)])

    def test_rejects_late_checkpoints_even_after_final_success(self):
        events = [event("ROOT_EXIT", 1), event("WRAPPER_RETURN", 2, status="SUCCEEDED"), event("CHECKPOINT_CALL", 3)]
        with self.assertRaisesRegex(AssertionError, "continued after"):
            assert_lifecycle(events)

    def test_rejects_live_tasks_at_normal_return(self):
        events = [event("ROOT_EXIT", 1), event("WRAPPER_RETURN", 2, status="SUCCEEDED", tasks=1)]
        with self.assertRaisesRegex(AssertionError, "live invocation tasks"):
            assert_lifecycle(events)

    def test_exceptional_return_must_also_be_quiescent(self):
        events = [event("WRAPPER_RETURN", 1, status="THREW", rootExited=False, tasks=1),
                  event("ROOT_EXIT", 2), event("TASK_EXIT", 3)]
        with self.assertRaisesRegex(AssertionError, "before root exit"):
            assert_lifecycle(events)
        assert_lifecycle([event("ROOT_EXIT", 1), event("WRAPPER_RETURN", 2, status="THREW")])

    def test_escape_cannot_make_deadlock_look_successful(self):
        events = [event("BARRIER_ENTER", 1), event("BARRIER_ENTER", 2, "b"),
                  event("BARRIER_PASSED", 3), event("BARRIER_PASSED", 4, "b"),
                  event("ESCAPE", 5), event("PROGRESS", 6), event("PROGRESS", 7, "b")]
        with self.assertRaisesRegex(AssertionError, "starved"):
            assert_fixed(events, {"a", "b"})

    def test_fixed_pool_positive_control(self):
        events = [event("BARRIER_ENTER", 1), event("BARRIER_ENTER", 2, "b"),
                  event("BARRIER_PASSED", 3), event("BARRIER_PASSED", 4, "b"),
                  event("PROGRESS", 5), event("PROGRESS", 6, "b")]
        assert_fixed(events, {"a", "b"})

    def test_replay_rejects_second_execution_of_checkpointed_body(self):
        events, history = self.replay_evidence()
        events.append(event("BODY", 9, name="success", value="a"))
        with self.assertRaisesRegex(AssertionError, "body ran 2"):
            assert_replay(events, history, "a")

    def test_replay_checks_real_history_identity_and_stored_failure(self):
        events, history = self.replay_evidence()
        assert_replay(events, history, "a")
        history.append({"Name": "success", "Id": "changed", "EventType": "StepStarted"})
        with self.assertRaisesRegex(AssertionError, "identity changed"):
            assert_replay(events, history, "a")

    def test_replay_cannot_pass_without_resume(self):
        events, history = self.replay_evidence()
        events = [e for e in events if e["requestId"] != "resume"]
        with self.assertRaisesRegex(AssertionError, "No real invocation resume"):
            assert_replay(events, history, "a")

    def replay_evidence(self):
        events = [event("WRAPPER_ENTER", 1), event("BODY", 2, name="success", value="a"),
                  event("BODY", 3, name="failure", value="a"),
                  event("STORED_FAILURE", 4, message="expected:a"),
                  event("WRAPPER_RETURN", 5, status="PENDING"),
                  event("WRAPPER_ENTER", 6, "resume", marker="a"),
                  event("STORED_FAILURE", 7, "resume", marker="a", message="expected:a")]
        history = [{"Name": "success", "Id": "1", "EventType": "StepSucceeded"},
                   {"Name": "failure", "Id": "2", "EventType": "StepFailed"},
                   {"Name": "resume", "Id": "3", "EventType": "WaitSucceeded"}]
        return events, history

    def test_structured_lmi_log_envelope(self):
        record = event("ROOT_EXIT", 1)
        self.assertEqual(record, diagnostic(json.dumps({"message": "LMI_TEST " + json.dumps(record)})))
        self.assertIsNone(diagnostic('{"type":"platform.report"}'))
        self.assertIsNone(diagnostic('LMI_TEST malformed'))

    def test_only_structured_platform_records_count_as_server_timeouts(self):
        diagnostic_message = json.dumps({"message": "LMI_TEST " + json.dumps(
            {"scenario": "timeout", "requestId": "request"})})
        platform_message = json.dumps({"type": "platform.runtimeDone",
                                       "record": {"requestId": "request", "status": "timeout"}})
        self.assertFalse(platform_timeout(diagnostic_message, "request"))
        self.assertTrue(platform_timeout(platform_message, "request"))
        self.assertFalse(platform_timeout(platform_message, "another-request"))

    def test_artifacts_redact_control_credentials_in_nested_payloads(self):
        value = {"controlUrl": "secret", "InputPayload": '{"controlUrl":"https://bucket/key?X-Amz-Signature=secret"}'}
        self.assertNotIn("secret", json.dumps(scrub(value)))

    def test_one_stack_declares_all_five_functions_and_their_scaling_limits(self):
        manifest = {"stack": "test", "role": "role", "bucket": "bucket", "provider": "provider",
                    "commit": "sha", "invocationTimeout": 60, "codeSha256": "digest", "codeKey": "code/digest.jar", "runId": "run-1"}
        spec = template(manifest)
        functions = [r["Properties"] for r in spec["Resources"].values() if r["Type"] == "AWS::Lambda::Function"]
        self.assertEqual(5, len(functions))
        self.assertEqual(set(FIXTURES), set(spec["Outputs"]))
        self.assertEqual(5, sum(r["Type"] == "AWS::Logs::LogGroup" for r in spec["Resources"].values()))
        concurrencies = set()
        for function in functions:
            capacity = function["CapacityProviderConfig"]["LambdaManagedInstancesCapacityProviderConfig"]
            concurrencies.add(capacity["PerExecutionEnvironmentMaxConcurrency"])
            self.assertEqual("java25", function["Runtime"])
            self.assertEqual(["arm64"], function["Architectures"])
            self.assertEqual(2048, function["MemorySize"])
            self.assertEqual(2, capacity["ExecutionEnvironmentMemoryGiBPerVCpu"])
            self.assertEqual({"MinExecutionEnvironments": 1, "MaxExecutionEnvironments": 1},
                             function["FunctionScalingConfig"], "Set limits at creation, before version stabilization")
            self.assertEqual(240, function["DurableConfig"]["ExecutionTimeout"])
            self.assertGreater(function["DurableConfig"]["ExecutionTimeout"], function["Timeout"])
        types = {r["Type"] for r in spec["Resources"].values()}
        self.assertNotIn("AWS::Lambda::Version", types, "A numbered version duplicates the LMI environment floor")
        self.assertNotIn("AWS::Lambda::CapacityProvider", types)
        self.assertIn(":$LATEST.PUBLISHED", json.dumps(spec["Outputs"]))
        self.assertEqual({1, 2, 8}, concurrencies)

    def test_full_suite_preserves_all_cases_on_their_own_fixture(self):
        cloud = Mock()
        names = []
        patches = [patch("cloud_suite." + name) for name in
                   ["replay_case", "overlap_case", "fixed_case", "timeout_case", "inflight_case", "warm_case"]]
        mocks = [p.start() for p in patches]
        try:
            for fixture in FIXTURES:
                cases = cases_for_fixture(cloud, fixture)
                self.assertTrue(cases)
                names.extend(name for name, _ in cases)
                for _, run in cases:
                    run()
                calls = [call for mock in mocks for call in mock.call_args_list]
                self.assertTrue(all(call.args[0] is cloud and call.args[1] == fixture for call in calls))
                for mock in mocks:
                    mock.reset_mock()
        finally:
            for p in patches:
                p.stop()
        self.assertEqual(13, len(names))
        self.assertEqual(13, len(set(names)))

    @patch("cloud_suite.Cloud")
    def test_cloud_suite_requires_all_five_functions(self, cloud):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps({"functions": {"default1": {}}}))
            with patch("cloud_suite.MANIFEST", path), self.assertRaisesRegex(PreconditionError, "All five"):
                run_tests()
        cloud.assert_not_called()

    @patch("cloud_support.aws", side_effect=RuntimeError("Invoke denied https://example.test/?signature=private-value"))
    def test_failed_invocation_is_preserved_in_redacted_artifacts(self, api):
        with TemporaryDirectory() as directory:
            cloud = Cloud({"functions": {"default1": {"arn": "function"}}}, directory)
            try:
                with self.assertRaisesRegex(RuntimeError, "Invoke denied"):
                    cloud._invoke("default1", {"marker": "test", "scenario": "baseline"})
                report = (Path(directory) / "invocations/test.json").read_text()
                self.assertIn("Invoke denied", report)
                self.assertNotIn("private-value", report)
            finally:
                cloud.close()

    def test_failed_invocation_is_not_misreported_as_an_sdk_lifecycle_assertion(self):
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            cloud.refresh = Mock(return_value=[])
            future = Future()
            future.set_exception(RuntimeError("Invoke denied"))
            try:
                with self.assertRaisesRegex(CollectionError, "Invoke denied"):
                    cloud.poll("default1", lambda events: False, items=[{"future": future, "marker": "test"}])
                cloud.refresh.assert_not_called()
            finally:
                cloud.close()

    @patch("cloud_support.time.sleep")
    @patch("cloud_support.aws")
    def test_finish_polls_until_durable_execution_is_terminal(self, api, sleep):
        api.side_effect = [{"Status": "RUNNING"}, {"Status": "SUCCEEDED", "Result": json.dumps("test")}]
        future = Future()
        future.set_result({"headers": {"DurableExecutionArn": "arn:test"}})
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            try:
                final = cloud.finish({"future": future, "marker": "test"}, seconds=5)
            finally:
                cloud.close()
        self.assertEqual("SUCCEEDED", final["Status"])
        self.assertEqual(2, api.call_count)
        sleep.assert_called_once()

    @patch("cloud_support.aws", return_value={"Status": "RUNNING"})
    def test_finish_can_collect_a_nonterminal_snapshot_without_waiting(self, api):
        future = Future()
        future.set_result({"headers": {"DurableExecutionArn": "arn:test"}})
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            try:
                final = cloud.finish({"future": future, "marker": "test"}, expected=None, seconds=0)
            finally:
                cloud.close()
        self.assertEqual("RUNNING", final["Status"])
        api.assert_called_once()

    @patch("cloud_support.aws", return_value={"Status": "RUNNING"})
    def test_finish_bounds_wait_for_a_terminal_status(self, api):
        future = Future()
        future.set_result({"headers": {"DurableExecutionArn": "arn:test"}})
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            try:
                with self.assertRaisesRegex(AssertionError, "did not become terminal"):
                    cloud.finish({"future": future, "marker": "test"}, seconds=0)
            finally:
                cloud.close()

    def test_finish_all_observes_every_invocation_future_before_raising(self):
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            cloud.finish = Mock(side_effect=[CollectionError("first"), {}, {}])
            try:
                with self.assertRaisesRegex(CollectionError, "first"):
                    cloud.finish_all([{"marker": str(i)} for i in range(3)])
                self.assertEqual(3, cloud.finish.call_count)
            finally:
                cloud.close()

    def test_finish_classifies_a_late_invocation_failure_as_collection_error(self):
        future = Future()
        future.set_exception(RuntimeError("Invoke denied"))
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            try:
                with self.assertRaisesRegex(CollectionError, "Invoke denied"):
                    cloud.finish({"future": future, "marker": "test"})
            finally:
                cloud.close()

    @patch("cloud_support.time.sleep")
    @patch("cloud_support.aws")
    def test_stop_waits_until_durable_retries_are_terminal(self, api, sleep):
        api.side_effect = [{"Status": "RUNNING"}, {}, {"Status": "STOPPED"}]
        future = Future()
        future.set_result({"headers": {"DurableExecutionArn": "arn:test"}})
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            try:
                final = cloud.stop({"future": future, "marker": "test"}, seconds=5)
            finally:
                cloud.close()
        self.assertEqual("STOPPED", final["Status"])
        self.assertEqual(["get-durable-execution", "stop-durable-execution", "get-durable-execution"],
                         [call.args[1] for call in api.call_args_list])

    def test_timeout_trace_cannot_use_a_later_retry_or_another_jvm(self):
        events = [event("WRAPPER_ENTER", 1, "original", marker="victim"),
                  event("WRAPPER_RETURN", 2, "retry", marker="victim", status="THREW"),
                  event("WRAPPER_RETURN", 3, "original", "other", marker="victim", status="THREW")]
        trace = request_trace(events, "victim", "original", "jvm")
        self.assertEqual(["WRAPPER_ENTER"], [item["kind"] for item in trace])

    def test_stubborn_outcome_must_come_from_the_original_request(self):
        events = [event("RESIDUAL_EXIT", 1, "original", marker="victim"),
                  event("LATE_REJECTED", 2, "retry", marker="victim")]
        self.assertFalse(residual_outcome_observed(events, "victim", "original", "jvm"))
        events.append(event("LATE_REJECTED", 3, "original", marker="victim"))
        self.assertTrue(residual_outcome_observed(events, "victim", "original", "jvm"))

    def test_runtime_drain_requires_every_retry_to_return(self):
        events = [event("WRAPPER_ENTER", 1, "original", marker="victim"),
                  event("WRAPPER_RETURN", 2, "original", marker="victim", status="THREW"),
                  event("WRAPPER_ENTER", 3, "retry", marker="victim")]
        self.assertFalse(runtime_requests_drained(events, "victim"))
        events.append(event("WRAPPER_RETURN", 4, "retry", marker="victim", status="THREW"))
        self.assertTrue(runtime_requests_drained(events, "victim"))

    @patch("cloud_suite.overlap_case")
    @patch("cloud_suite.assert_timeout_case", side_effect=AssertionError("regression"))
    def test_timeout_case_stops_victim_after_an_assertion_failure(self, assertion, overlap):
        cloud = Mock()
        cloud.manifest = {"invocationTimeout": 60}
        cloud.invocations = []
        victim = {"marker": "victim"}
        cloud.launch.return_value = victim
        cloud.events_for.return_value = []
        with self.assertRaisesRegex(AssertionError, "regression"):
            timeout_case(cloud, "default2")
        cloud.release_all.assert_called_once()
        cloud.stop.assert_called_once_with(victim, seconds=90)
        overlap.assert_not_called()

    def test_timeout_cleanup_drains_every_driver_request_from_the_case(self):
        cloud = Mock()
        victim, peer, probe = ({"marker": name} for name in ("victim", "peer", "probe"))
        cloud.invocations = [{"marker": "older"}, victim, peer, probe]
        cloud.events_for.return_value = []
        cleanup_timeout_case(cloud, "default2", victim, 60, 1)
        cloud.stop_all.assert_called_once_with([peer, probe], seconds=90)

    @patch("cloud_suite.time.time", return_value=10)
    def test_wall_clock_wait_observes_probe_failures(self, now):
        cloud = Mock()
        probes = [{"marker": "probe"}]
        wait_until_wall_time(cloud, "default2", 9, 20, probes)
        self.assertIs(probes, cloud.poll.call_args.kwargs["items"])
        self.assertTrue(cloud.poll.call_args.args[1]([]))

    def test_workflow_separates_local_checks_from_cloud_credentials(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/lmi-e2e-tests.yml").read_text()
        self.assertIn("  local:\n", workflow)
        self.assertIn("  cloud:\n", workflow)
        self.assertIn("if: github.event_name != 'pull_request'", workflow)
        self.assertIn("- 'sdk/**'", workflow)
        self.assertEqual(1, workflow.count("id-token: write"))
        self.assertNotIn("run-lmi-e2e", workflow)
        self.assertNotIn("labeled", workflow)

    def test_missing_runtime_entry_is_not_an_sdk_regression(self):
        with TemporaryDirectory() as directory:
            cloud = Cloud({}, directory)
            cloud.refresh = Mock(return_value=[])
            future = Future()
            future.set_result({"headers": {"StatusCode": 202}})
            try:
                with self.assertRaisesRegex(CollectionError, "No runtime-entry evidence"):
                    cloud.poll("default1", lambda events: False, seconds=0,
                               items=[{"future": future, "marker": "test"}])
                cloud.refresh.return_value = [{"kind": "WRAPPER_ENTER", "marker": "test"}]
                with self.assertRaisesRegex(AssertionError, "Evidence deadline exceeded"):
                    cloud.poll("default1", lambda events: False, seconds=0,
                               items=[{"future": future, "marker": "test"}])
            finally:
                cloud.close()

    @patch("cloud_support.aws")
    def test_runtime_evidence_must_match_the_current_deployment(self, api):
        entry = event("WRAPPER_ENTER", 1, runId="run-2", deploymentRunId="run-1", commit="commit-1")
        api.return_value = {"events": [{"eventId": "event", "message": "LMI_TEST " + json.dumps(entry)}]}
        with TemporaryDirectory() as directory:
            cloud = Cloud({"runId": "run-2", "commit": "commit-2",
                           "functions": {"default1": {"logGroup": "logs"}}}, directory)
            try:
                with self.assertRaisesRegex(CollectionError, "outdated deployment"):
                    cloud.refresh("default1")
                self.assertTrue((Path(directory) / "diagnostics.json").exists())
            finally:
                cloud.close()

    @patch("cloud_suite.save")
    @patch("cloud_suite.time.sleep")
    @patch("cloud_suite.aws")
    def test_scaling_waits_for_applied_limit_and_an_active_version(self, api, sleep, save):
        desired = {"MinExecutionEnvironments": 1, "MaxExecutionEnvironments": 1}
        api.side_effect = [{"RequestedFunctionScalingConfig": desired,
                            "AppliedFunctionScalingConfig": {"MinExecutionEnvironments": 3}},
                           {"State": "Active"},
                           {"AppliedFunctionScalingConfig": desired}, {"State": "Pending"},
                           {"AppliedFunctionScalingConfig": desired}, {"State": "Active"}]
        verify_function_scaling("arn:aws:lambda:us-west-2:123456789012:function:test:$LATEST.PUBLISHED", "default2")
        self.assertEqual(2, sleep.call_count)
        request = api.call_args_list[0].args[2]
        self.assertEqual("$LATEST.PUBLISHED", request["Qualifier"])
        self.assertEqual(["get-function-scaling-config", "get-function-configuration"] * 3,
                         [call.args[1] for call in api.call_args_list])

    @patch("cloud_suite.save")
    @patch("cloud_suite.aws")
    def test_failed_version_is_a_setup_failure(self, api, save):
        api.side_effect = [{}, {"State": "Failed", "StateReason": "capacity exhausted"}]
        with self.assertRaisesRegex(PreconditionError, "capacity exhausted"):
            verify_function_scaling("arn:aws:lambda:us-west-2:123456789012:function:test:$LATEST.PUBLISHED", "default2")


if __name__ == "__main__":
    unittest.main()
