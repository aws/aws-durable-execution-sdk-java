# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual CLI's custom invoke command against a local unsigned endpoint."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory
import threading
import unittest
from unittest.mock import patch
from urllib.parse import unquote

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_support
from cloud_support import Cloud


@unittest.skipUnless(shutil.which("aws"), "AWS CLI is required for the local invocation contract test")
class AwsCliInvocationTest(unittest.TestCase):
    def test_sync_and_async_invocations_send_payload_bytes_and_preserve_results(self):
        received = []
        execution_arn = "arn:aws:lambda:us-west-2:123456789012:function:test/durable-execution/test/uuid"
        function_arn = "arn:aws:lambda:us-west-2:123456789012:function:test:$LATEST.PUBLISHED"

        class Endpoint(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                payload = json.loads(body)
                invocation_type = self.headers["X-Amz-Invocation-Type"]
                received.append((unquote(self.path), payload, invocation_type, self.headers.get("Authorization")))
                response = json.dumps(payload["marker"]).encode() if invocation_type == "RequestResponse" else b""
                self.send_response(200 if invocation_type == "RequestResponse" else 202)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response)))
                self.send_header("X-Amz-Durable-Execution-Arn", execution_arn)
                self.end_headers()
                self.wfile.write(response)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Endpoint)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        real_aws = cloud_support.aws

        def local_aws(service, operation, data=None, extra=(), **kwargs):
            self.assertEqual(("lambda", "invoke"), (service, operation))
            kwargs["timeout"] = 5
            return real_aws(service, operation, data, extra=[*extra,
                "--endpoint-url", f"http://127.0.0.1:{server.server_port}",
                "--region", "us-west-2", "--no-sign-request"], **kwargs)

        try:
            with TemporaryDirectory() as directory, patch("cloud_support.aws", side_effect=local_aws):
                cloud = Cloud({"functions": {"default1": {"arn": function_arn}}}, directory)
                try:
                    for scenario, invocation_type in [("baseline", "RequestResponse"), ("timeout", "Event")]:
                        with self.subTest(scenario=scenario):
                            payload = {"runId": "test", "scenario": scenario, "marker": scenario + "-λ",
                                       "controlUrl": "https://example.test/control?signature=example"}
                            result = cloud._invoke("default1", payload)
                            path, body, actual_type, authorization = received[-1]
                            self.assertIn(function_arn, path)
                            self.assertEqual(payload, body)
                            self.assertEqual(invocation_type, actual_type)
                            self.assertIsNone(authorization)
                            self.assertEqual(execution_arn, result["headers"]["DurableExecutionArn"])
                            self.assertEqual(payload["marker"] if invocation_type == "RequestResponse" else "", result["body"])
                            report = json.loads((Path(directory) / "invocations" / (payload["marker"] + ".json")).read_text())
                            self.assertEqual("RETURNED", report["state"])
                            self.assertEqual(function_arn, report["functionArn"])
                finally:
                    cloud.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
