# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Cloud API adapter and evidence assertions; no third-party Python dependencies."""
import concurrent.futures
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


TERMINAL_DURABLE_STATUSES = {"SUCCEEDED", "FAILED", "TIMED_OUT", "STOPPED"}


class PreconditionError(RuntimeError):
    """The scenario did not establish the required infrastructure/placement."""


class CollectionError(RuntimeError):
    """Required cloud evidence could not be retrieved."""


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def scrub(value):
    if isinstance(value, dict):
        return {k: ("<redacted>" if k in {"controlUrl", "CheckpointToken"} else scrub(v))
                for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    if isinstance(value, str):
        return re.sub(r'https://[^\s"<>]+', '<redacted-url>', value)
    return value


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(scrub(value), indent=2, default=str) + "\n")


def aws(service, operation, data=None, extra=(), timeout=30, raw=False):
    command = ["aws", service, operation, "--no-cli-pager", "--output", "json",
               "--cli-connect-timeout", "5", "--cli-read-timeout", str(timeout)]
    if data is not None:
        command += ["--cli-input-json", json.dumps(data)]
    command += list(extra)
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout + 10,
                            env={**os.environ, "AWS_MAX_ATTEMPTS": "2", "AWS_PAGER": ""})
    if result.returncode:
        raise RuntimeError(f"{service} {operation} (exit {result.returncode}): {scrub(result.stderr[-4000:])}")
    return result.stdout.strip() if raw else json.loads(result.stdout or "{}")


def diagnostic(message):
    """Accept both raw stdout and the LMI structured JSON logging envelope."""
    try:
        envelope = json.loads(message)
        if isinstance(envelope, dict):
            message = envelope.get("message", "")
    except (ValueError, TypeError):
        pass
    if not isinstance(message, str) or "LMI_TEST " not in message:
        return None
    try:
        return json.loads(message.split("LMI_TEST ", 1)[1])
    except ValueError:
        return None


def selected(events, kind=None, marker=None):
    return [e for e in events if (kind is None or e["kind"] == kind)
            and (marker is None or e["marker"] == marker)]


def assert_overlap(events, markers, count, environment=None):
    """Use actual task intervals on one JVM; driver parallelism is not evidence."""
    points = {}
    for event in events:
        if event["marker"] not in markers or event["kind"] not in {"TASK_ENTER", "TASK_EXIT"}:
            continue
        if environment is not None and event["environment"] != environment:
            continue
        points.setdefault(event["environment"], []).append(event)
    for env, entries in points.items():
        active = {}
        for event in sorted(entries, key=lambda e: e["sequence"]):
            key = event["requestId"]
            active[key] = active.get(key, 0) + (1 if event["kind"] == "TASK_ENTER" else -1)
            if sum(n > 0 for n in active.values()) >= count:
                return env
    raise PreconditionError(f"No evidence of {count} overlapping request IDs in one JVM")


def assert_lifecycle(events, allow_residual=False):
    returns = selected(events, "WRAPPER_RETURN")
    require(returns, "No SDK wrapper return observed")
    for returned in returns:
        local = [e for e in events if e["requestId"] == returned["requestId"]
                 and e["environment"] == returned["environment"]]
        if returned["status"] in {"SUCCEEDED", "FAILED", "PENDING"}:
            require(returned["rootExited"], f"{returned['status']} returned before root exit")
            require(returned["tasks"] == 0, f"{returned['status']} returned with live invocation tasks")
            roots = selected(local, "ROOT_EXIT")
            require(roots and roots[-1]["sequence"] < returned["sequence"], "Missing causal root exit")
        if not allow_residual:
            require(not selected(local, "ESCAPE"), "A test-only escape was needed to finish SDK work")
        late = [e for e in local if e["sequence"] > returned["sequence"]
                and e["kind"] in {"CHECKPOINT_CALL", "POLL_CALL", "CHECKPOINT_EXIT", "POLL_EXIT"}]
        require(not late, "SDK checkpoint/poll activity continued after wrapper return")


def assert_replay(events, history, marker):
    calls = selected(events, "WRAPPER_ENTER", marker)
    require(len({e["requestId"] for e in calls}) >= 2, "No real invocation resume observed")
    require(any(e.get("status") == "PENDING" for e in selected(events, "WRAPPER_RETURN", marker)),
            "No real suspension observed")
    for name, event_type in [("success", "StepSucceeded"), ("failure", "StepFailed")]:
        bodies = [e for e in selected(events, "BODY", marker) if e["name"] == name]
        require(len(bodies) == 1, f"Checkpointed {name} body ran {len(bodies)} times")
        require(bodies[0]["value"] == marker, "Cross-execution result contamination")
        entries = [e for e in history if e.get("Name") == name]
        terminal = [e for e in entries if e.get("EventType") == event_type]
        require(len(terminal) == 1, f"Missing/duplicate real {event_type} history")
        require(len({e["Id"] for e in entries}) == 1, f"{name} operation identity changed")
    failures = selected(events, "STORED_FAILURE", marker)
    require(len(failures) >= 2 and all(e["message"] == "expected:" + marker for e in failures),
            "Stored failure meaning was not reproduced on replay")
    require(any(e.get("EventType") == "WaitSucceeded" for e in history), "No service wait completion")


def assert_fixed(events, markers):
    entered = selected(events, "BARRIER_ENTER")
    passed = selected(events, "BARRIER_PASSED")
    require(len({e["marker"] for e in passed}) == len(markers), "Root barrier was not passed by every participant")
    envs = {e["environment"] for e in passed}
    if len(envs) != 1:
        raise PreconditionError("Fixed-executor roots were placed in different JVMs")
    require(len({e["requestId"] for e in entered}) == len(markers), "Missing distinct runtime invocations")
    require(max(e["sequence"] for e in entered) < min(e["sequence"] for e in passed),
            "Root invocations did not overlap at the barrier")
    require(not selected(events, "ESCAPE"), "Shared fixed executor starved its own queued work")
    for marker in markers:
        progress = selected(events, "PROGRESS", marker)
        start = selected(events, "BARRIER_PASSED", marker)
        require(progress and (progress[-1]["nanos"] - start[0]["nanos"]) < 8_000_000_000,
                "Shared executor did not progress within budget")


class Cloud:
    def __init__(self, manifest, artifacts):
        self.manifest, self.artifacts = manifest, Path(artifacts)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=32)
        self.events, self.raw_logs, self.invocations = {}, {}, []
        self.start_ms = manifest.get("logStartMillis", int(time.time() * 1000))
        self.gates = set()

    def gate(self, name, release=False):
        self.gates.add(name)
        with tempfile.NamedTemporaryFile(mode="w") as body:
            body.write("release" if release else "hold")
            body.flush()
            aws("s3api", "put-object", {"Bucket": self.manifest["bucket"], "Key": "control/" + self.manifest["runId"] + "/" + name},
                extra=["--body", body.name])
        if release:
            return None
        return aws("s3", "presign", extra=[f"s3://{self.manifest['bucket']}/control/{self.manifest['runId']}/{name}",
                                           "--expires-in", "3600"], raw=True)

    def launch(self, fixture, scenario, marker, cohort=None, target=None, peers=1, hold_ms=100000, gate=None):
        item = {"marker": marker, "fixture": fixture, "started": time.time()}
        payload = {"runId": self.manifest["runId"], "cohort": cohort or marker,
                   "scenario": scenario, "marker": marker, "controlUrl": gate,
                   "targetEnvironment": target, "peers": peers, "holdMillis": hold_ms}
        item["future"] = self.pool.submit(self._invoke, fixture, payload)
        self.invocations.append(item)
        return item

    def _invoke(self, fixture, payload):
        started = time.time()
        artifact = self.artifacts / "invocations" / (payload["marker"] + ".json")
        details = {"fixture": fixture, "functionArn": self.manifest["functions"][fixture]["arn"],
                   "scenario": payload["scenario"], "marker": payload["marker"], "started": started}
        save(artifact, {**details, "state": "STARTED"})
        try:
            result = self._invoke_request(fixture, payload)
            save(artifact, {**details, **result, "state": "RETURNED", "elapsedSeconds": time.time() - started})
            return result
        except Exception as error:
            save(artifact, {**details, "state": "REQUEST_FAILED", "elapsedSeconds": time.time() - started,
                            "errorType": type(error).__name__, "error": str(error)})
            raise

    def _invoke_request(self, fixture, payload):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "response.json"
            source = Path(directory) / "payload.json"
            source.write_text(json.dumps(payload), encoding="utf-8")
            # lambda invoke is a custom AWS CLI command: required flags cannot come from --cli-input-json.
            # fileb:// sends the original JSON bytes regardless of the user's CLI binary-format setting.
            headers = aws("lambda", "invoke", extra=[
                "--function-name", self.manifest["functions"][fixture]["arn"],
                "--invocation-type", "Event" if payload["scenario"] in {"timeout", "stubborn"} else "RequestResponse",
                "--payload", "fileb://" + str(source), str(path)], timeout=150)
            try:
                body = json.loads(path.read_text())
            except ValueError:
                body = path.read_text()
            return {"headers": headers, "body": body}

    def refresh(self, fixture):
        group = self.manifest["functions"][fixture]["logGroup"]
        data = aws("logs", "filter-log-events", {"logGroupName": group, "startTime": self.start_ms})
        for event in data.get("events", []):
            self.raw_logs[event["eventId"]] = event
            parsed = diagnostic(event["message"])
            if parsed and parsed.get("runId") == self.manifest["runId"]:
                parsed["fixture"] = fixture
                self.events[(parsed["environment"], parsed["sequence"])] = parsed
        save(self.artifacts / "diagnostics.json", list(self.events.values()))
        save(self.artifacts / "cloudwatch.json", list(self.raw_logs.values()))
        events = [e for e in self.events.values() if e["fixture"] == fixture]
        if any(e.get("deploymentRunId") != self.manifest["runId"] or e.get("commit") != self.manifest["commit"]
               for e in events):
            raise CollectionError("Invocation reached an outdated deployment; inspect the recorded commit and deploymentRunId")
        return events

    def poll(self, fixture, predicate, seconds=25, category=AssertionError, items=()):
        deadline = time.monotonic() + seconds
        while True:
            for item in items:
                future = item["future"]
                if future.done() and future.exception() is not None:
                    error = future.exception()
                    raise CollectionError(f"Invocation request failed for {item['marker']}: {scrub(str(error))}") from error
            events = self.refresh(fixture)
            result = predicate(events)
            if result:
                return result
            if time.monotonic() >= deadline:
                markers = {item["marker"] for item in items}
                if items and not any(e["marker"] in markers and e["kind"] == "WRAPPER_ENTER" for e in events):
                    raise CollectionError(f"No runtime-entry evidence for {fixture}; inspect invocations and CloudWatch artifacts")
                raise category(f"Evidence deadline exceeded for {fixture}")
            time.sleep(1)

    def events_for(self, item):
        return selected(list(self.events.values()), marker=item["marker"])

    def finish(self, item, expected="SUCCEEDED", seconds=100):
        deadline = time.monotonic() + seconds
        try:
            result = item["future"].result(timeout=max(0, deadline - time.monotonic()))
        except (concurrent.futures.TimeoutError, subprocess.TimeoutExpired) as failure:
            raise CollectionError("Client HTTP/driver timeout; not server invocation timeout evidence") from failure
        except Exception as failure:
            raise CollectionError(
                f"Invocation request failed for {item['marker']}: {scrub(str(failure))}") from failure
        events = self.events_for(item)
        arn = result["headers"].get("DurableExecutionArn")
        if not arn and events:
            arn = events[0]["executionArn"]
        if not arn:
            raise CollectionError("No durable execution ARN")
        item["arn"] = arn
        while True:
            remaining = deadline - time.monotonic()
            try:
                final = aws("lambda", "get-durable-execution", {"DurableExecutionArn": arn},
                            timeout=max(1, min(30, int(max(0, remaining)) + 1)))
            except (RuntimeError, subprocess.TimeoutExpired) as failure:
                raise CollectionError(
                    f"Could not read durable execution for {item['marker']}: {scrub(str(failure))}") from failure
            save(self.artifacts / "executions" / (item["marker"] + ".json"), final)
            if expected is None or final.get("Status") in TERMINAL_DURABLE_STATUSES:
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError(
                    f"{item['marker']}: durable status {final.get('Status')} did not become terminal within {seconds}s")
            time.sleep(min(1, remaining))
        if expected:
            require(final["Status"] == expected, f"{item['marker']}: durable status {final['Status']}, expected {expected}")
            if expected == "SUCCEEDED":
                require(json.loads(final["Result"]) == item["marker"], "Execution returned another input's result")
        return final

    def finish_all(self, items, expected=None, seconds=100):
        """Drain every invocation future, preserving the first error after all items have been observed."""
        deadline = time.monotonic() + seconds
        results, failures = [], []
        for item in items:
            try:
                results.append(self.finish(item, expected=expected, seconds=max(0, deadline - time.monotonic())))
            except Exception as failure:
                failures.append(failure)
        if failures:
            raise failures[0]
        return results

    def history(self, item):
        events, marker = [], None
        arn = item.get("arn") or self.events_for(item)[0]["executionArn"]
        while True:
            request = {"DurableExecutionArn": arn, "IncludeExecutionData": True}
            if marker:
                request["Marker"] = marker
            result = aws("lambda", "get-durable-execution-history", request)
            events.extend(result.get("Events", []))
            marker = result.get("NextMarker")
            if not marker:
                break
        save(self.artifacts / "histories" / (item["marker"] + ".json"), events)
        return events

    def release_all(self):
        for gate in list(self.gates):
            self.gate(gate, release=True)
        self.gates.clear()

    def close(self):
        self.release_all()
        # All calls have finite HTTP and subprocess budgets; preserve late evidence before teardown.
        self.pool.shutdown(wait=True)
