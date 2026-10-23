"""Negative controls for observability evidence and the instrumented Invoke boundary."""

import copy
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_suite
from cloud_support import Cloud, CollectionError
from otel_cases import additional_cases
from otel_evidence import validate_invocations


HEADER = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1"
SETTINGS = {"view": "execution", "mode": "success", "traceHeader": HEADER}


def events():
    base = {"requestId": "request", "executionArn": "execution", "environment": "jvm",
            "marker": "a", "sequence": 1, "epochMillis": 1}
    span = {"name": "Invocation", "traceId": "6955b900123456789012345678901234",
            "parentId": "1234567890123456", "sampled": True, "startNanos": 1,
            "endNanos": 2, "status": "OK", "attributes": {"durable.execution.arn": "execution"}}
    return [
        {**base, "kind": "OTEL_ENTER", "carrier": HEADER, "view": "execution"},
        {**base, "kind": "OTEL_FACTORY", "pluginInstance": "unique", "pluginExecution": "execution",
         "capturedHeader": HEADER, "firstInvocation": True},
        {**base, "kind": "OTEL_EXPORT", "outcome": "success", "span": span},
        {**base, "kind": "OTEL_EXPORT", "outcome": "success", "span": {**span, "name": "Workflow"}},
        {**base, "kind": "OTEL_END", "contextRestored": True, "mdcRestored": True,
         "ownerThread": 1, "endThread": 1, "exporterShutdowns": 0, "status": "SUCCEEDED"},
    ]


class OtelEvidenceTest(unittest.TestCase):
    def test_export_completion_and_outcome_are_required_for_quiescence(self):
        from test_evidence import event
        trace = [
            event("WRAPPER_ENTER", 1), event("ROOT_ENTER", 2),
            event("OTEL_EXPORT_ENTER", 3, exports=1),
            event("ROOT_EXIT", 4, exports=1),
            event("WRAPPER_RETURN", 5, exports=1, status="SUCCEEDED"),
        ]
        self.assertFalse(cloud_suite.runtime_requests_drained(trace, "a"))
        trace.append(event("OTEL_EXPORT", 6, exports=1))
        self.assertFalse(cloud_suite.runtime_requests_drained(trace, "a"))
        trace.append(event("OTEL_EXPORT_EXIT", 7, exports=0))
        self.assertTrue(cloud_suite.runtime_requests_drained(trace, "a"))
        del trace[-2]
        self.assertFalse(cloud_suite.runtime_requests_drained(trace, "a"))

    def test_valid_evidence(self):
        validate_invocations(events(), SETTINGS)

    def test_corrupted_owner_parent_trace_and_context_fail(self):
        for mutation in ("owner", "trace", "parent", "context", "thread", "duplicate", "carrier"):
            with self.subTest(mutation=mutation):
                records = events()
                if mutation == "owner":
                    records[2]["span"]["attributes"]["durable.execution.arn"] = "other"
                elif mutation == "trace":
                    records[2]["span"]["traceId"] = "0" * 32
                elif mutation == "parent":
                    records[2]["span"]["parentId"] = "0" * 16
                elif mutation == "context":
                    records[-1]["mdcRestored"] = False
                elif mutation == "thread":
                    records[-1]["endThread"] = 2
                elif mutation == "duplicate":
                    records.append(copy.deepcopy(records[2]))
                else:
                    records[1]["capturedHeader"] = "stale"
                with self.assertRaises(AssertionError):
                    validate_invocations(records, SETTINGS)

    def test_missing_span_or_runtime_evidence_is_not_success(self):
        for missing in (0, 1, 2, 4):
            records = events()
            del records[missing]
            with self.assertRaises(CollectionError):
                validate_invocations(records, SETTINGS)

    def test_unsampled_requires_zero_exports(self):
        records = [event for event in events() if event["kind"] != "OTEL_EXPORT"]
        records[0]["carrier"] = records[1]["capturedHeader"] = HEADER[:-1] + "0"
        settings = {**SETTINGS, "traceHeader": HEADER[:-1] + "0"}
        validate_invocations(records, settings)
        records.insert(2, events()[2])
        with self.assertRaisesRegex(AssertionError, "Sampled=0"):
            validate_invocations(records, settings)

    def test_extended_catalog_has_32_distinct_observability_cases(self):
        catalog = additional_cases(SimpleNamespace(), cloud_suite)
        self.assertEqual(32, len(catalog))
        self.assertEqual(32, len({name for _, name, _ in catalog}))
        self.assertEqual({"default1", "default2"}, {fixture for fixture, _, _ in catalog})

    def test_instrumented_invoker_keeps_carrier_out_of_handler_payload(self):
        cloud = Cloud.__new__(Cloud)
        cloud.manifest = {"functions": {"default2": {"arn": "function:qualified"}}}
        payload = {"runId": "run", "marker": "marker", "scenario": "success",
                   "otelView": "execution", "traceHeader": HEADER}
        reply = {"headers": {"DurableExecutionArn": "execution"}, "body": ""}
        with patch("cloud_support.subprocess.run", return_value=SimpleNamespace(
                returncode=0, stdout=json.dumps(reply), stderr="")) as invoke:
            self.assertEqual(reply, cloud._invoke_request("default2", payload))
        args, kwargs = invoke.call_args
        self.assertEqual("java", args[0][0])
        self.assertEqual("software.amazon.lambda.durable.lmi.OtelInvoke", args[0][-1])
        request = json.loads(kwargs["input"])
        self.assertEqual(HEADER, request["traceHeader"])
        self.assertNotIn("traceHeader", json.loads(request["payload"]))
        self.assertEqual("run-marker", request["executionName"])
