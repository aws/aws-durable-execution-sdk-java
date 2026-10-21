# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Cleanup reconciles acceptance while retaining every late driver request failure."""
from concurrent.futures import Future
from pathlib import Path
import sys
import traceback
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_support import Cloud, CollectionError
from cloud_suite import cleanup_case, execute_case
from test_evidence import event


class CleanupRequestAuditTest(unittest.TestCase):
    def item(self, marker, failure=None):
        future = Future()
        if failure:
            future.set_exception(failure)
        else:
            future.set_result({"headers": {"DurableExecutionArn": "arn:" + marker}})
        return {"marker": marker, "future": future, "arn": "arn:" + marker}

    def test_discarded_placement_attempt_failure_survives_successful_reconciliation(self):
        with TemporaryDirectory() as directory:
            cloud = Cloud({"invocationTimeout": 60}, directory)
            discarded = {"marker": "discarded", "future": Future(), "arn": "arn:discarded"}
            admitted = self.item("admitted")
            def case():
                cloud.invocations.extend([discarded, admitted])
                cloud.events["miss"] = event("PLACEMENT_MISS", 1, "discarded")
                # The case stops polling this attempt after its placement diagnostic.
                discarded["future"].set_exception(RuntimeError("late CLI response failure"))
            try:
                with patch("cloud_support.aws", return_value={"Status": "FAILED"}) as api, \
                        patch("cloud_suite.wait_for_runtime_quiescence") as quiet:
                    primary, cleanup = execute_case(cloud, "default2", case)
                self.assertIsNone(primary)
                self.assertIsInstance(cleanup, CollectionError)
                self.assertIn("discarded", str(cleanup))
                self.assertIn("late CLI response failure", str(cleanup))
                # Both accepted executions were still reconciled/drained through the real stop path.
                self.assertEqual(2, api.call_count)
                quiet.assert_called_once()
            finally:
                cloud.close()

    def test_all_future_errors_remain_visible_when_other_cleanup_stages_fail(self):
        cloud = Mock()
        cloud.invocations = [self.item("first", RuntimeError("lost response")),
                             self.item("second", RuntimeError("late request error"))]
        cloud.release_all.side_effect = RuntimeError("release failed")
        cloud.stop_all.side_effect = RuntimeError("stop failed")
        with patch("cloud_suite.wait_for_runtime_quiescence", side_effect=AssertionError("not quiet")) as quiet:
            with self.assertRaises(CollectionError) as raised:
                cleanup_case(cloud, "default2", 0, 90)
        for message in ("release failed", "stop failed", "not quiet", "lost response", "late request error"):
            self.assertIn(message, str(raised.exception))
        cloud.stop_all.assert_called_once()
        quiet.assert_called_once()

    def test_request_failure_discovered_during_quiescence_is_audited(self):
        cloud = Mock()
        future = Future()
        cloud.invocations = [{"marker": "discarded", "future": future}]
        def settle(*args, **kwargs):
            future.set_exception(RuntimeError("response failed after drainage"))
        with patch("cloud_suite.wait_for_runtime_quiescence", side_effect=settle):
            with self.assertRaisesRegex(CollectionError, "after drainage"):
                cleanup_case(cloud, "default2", 0, 90)

    def test_pending_and_cancelled_requests_cannot_report_success(self):
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled):
                cloud = Mock()
                future = Future()
                if cancelled:
                    future.cancel()
                cloud.invocations = [{"marker": "unfinished", "future": future}]
                with patch("cloud_suite.wait_for_runtime_quiescence"):
                    with self.assertRaises(CollectionError):
                        cleanup_case(cloud, "default2", 0, 90)

    def test_primary_assertion_and_late_transport_failure_are_reported_separately(self):
        cloud = Mock()
        cloud.manifest = {"invocationTimeout": 60}
        cloud.invocations = []
        original = AssertionError("SDK deadline regression")
        def case():
            cloud.invocations.append(self.item("discarded", RuntimeError("lost reply")))
            raise original
        with patch("cloud_suite.wait_for_runtime_quiescence"):
            primary, cleanup = execute_case(cloud, "default2", case)
        self.assertIs(original, primary)
        self.assertIsInstance(cleanup, CollectionError)
        self.assertIn("lost reply", str(cleanup))

    def test_successful_requests_and_an_earlier_case_failure_do_not_fail_current_cleanup(self):
        cloud = Mock()
        cloud.invocations = [self.item("old", RuntimeError("previous case")), self.item("current")]
        with patch("cloud_suite.wait_for_runtime_quiescence"):
            cleanup_case(cloud, "default2", 1, 90)
        cloud.stop_all.assert_called_once_with(cloud.invocations[1:], seconds=90)

    def test_transport_error_urls_remain_redacted(self):
        cloud = Mock()
        cloud.invocations = [self.item("discarded", RuntimeError("lost https://control.example/secret?token=value"))]
        with patch("cloud_suite.wait_for_runtime_quiescence"):
            with self.assertRaises(CollectionError) as raised:
                cleanup_case(cloud, "default2", 0, 90)
        self.assertIn("<redacted-url>", str(raised.exception))
        self.assertNotIn("secret", "".join(traceback.format_exception(
            type(raised.exception), raised.exception, raised.exception.__traceback__)))


if __name__ == "__main__":
    unittest.main()
