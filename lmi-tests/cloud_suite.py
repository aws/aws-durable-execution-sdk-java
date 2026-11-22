# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Opt-in real-service LMI tests. See README.md for design and ownership."""
import argparse
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from cloud_support import (Cloud, CollectionError, PreconditionError, api_calls_complete, assert_fixed,
                           assert_lifecycle, assert_nested_reached, assert_overlap, assert_replay, aws,
                           platform_timeout, request_identities, require, save, scrub, selected)

ROOT = Path(__file__).resolve().parent
ARTIFACTS = ROOT / "artifacts"
MANIFEST = ARTIFACTS / "manifest.json"
OWNER = "java-sdk-lmi-e2e"
DEFAULT_STACK = "java-lmi-e2e"
FIXTURES = {"default1": (1, "default"), "default2": (2, "default"),
            "default8": (8, "default"), "fixed2": (2, "invocation-fixed"), "nested2": (2, "nested")}
PEER_LOG_OBSERVATION_SECONDS = 20
PEER_PLACEMENT_SECONDS = 60


def template(manifest):
    resources, outputs = {}, {}
    for key, (concurrency, executor) in FIXTURES.items():
        name = manifest["stack"] + "-" + key
        log_id, fn_id = key + "Logs", key + "Function"
        resources[log_id] = {"Type": "AWS::Logs::LogGroup", "Properties": {
            "LogGroupName": "/aws/lambda/" + name, "RetentionInDays": 1}}
        resources[fn_id] = {"Type": "AWS::Lambda::Function", "Properties": {
            "FunctionName": name, "Runtime": "java25", "Architectures": ["arm64"],
            "Role": manifest["role"], "Handler": "software.amazon.lambda.durable.lmi.LifecycleHandler",
            "Code": {"S3Bucket": manifest["bucket"], "S3Key": manifest["codeKey"]},
            "Timeout": manifest["invocationTimeout"], "MemorySize": 2048,
            "FunctionScalingConfig": {"MinExecutionEnvironments": 1, "MaxExecutionEnvironments": 1},
            "DurableConfig": {"ExecutionTimeout": 240, "RetentionPeriodInDays": 1},
            "CapacityProviderConfig": {"LambdaManagedInstancesCapacityProviderConfig": {
                "CapacityProviderArn": manifest["provider"],
                "PerExecutionEnvironmentMaxConcurrency": concurrency,
                "ExecutionEnvironmentMemoryGiBPerVCpu": 2}},
            "Environment": {"Variables": {"LMI_EXECUTOR": executor, "LMI_COMMIT": manifest["commit"],
                                          "LMI_TEST_RUN_ID": manifest["runId"]}},
            "LoggingConfig": {"LogFormat": "JSON", "ApplicationLogLevel": "INFO",
                              "SystemLogLevel": "INFO", "LogGroup": {"Ref": log_id}}}}
        # CloudFormation automatically publishes $LATEST.PUBLISHED for an LMI function.
        # An additional numbered version would provision a second independent set of environments.
        outputs[key] = {"Value": {"Fn::Join": ["", [{"Fn::GetAtt": [fn_id, "Arn"]}, ":$LATEST.PUBLISHED"]]}}
    return {"AWSTemplateFormatVersion": "2010-09-09", "Resources": resources, "Outputs": outputs}


def deploy(run_id, invocation_timeout, stack_name=DEFAULT_STACK):
    if not re.fullmatch(r"[a-z0-9-]{1,24}", run_id):
        raise PreconditionError("run-id must be 1-24 lowercase letters, digits, or hyphens")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,49}", stack_name):
        raise PreconditionError("stack-name must be 1-50 letters, digits, or hyphens, starting with a letter")
    provider = os.environ["CAPACITY_PROVIDER_ARN"]
    region = provider.split(":")[3]
    if region != os.environ.get("AWS_REGION"):
        raise PreconditionError("AWS_REGION must match the capacity provider region")
    account = aws("sts", "get-caller-identity")["Account"]
    if account != provider.split(":")[4]:
        raise PreconditionError("Capacity provider must belong to the authenticated test account")
    name = provider.rsplit(":", 1)[-1].rsplit("/", 1)[-1]
    capacity = aws("lambda", "get-capacity-provider", {"CapacityProviderName": name})
    save(ARTIFACTS / "capacity-provider.json", capacity)
    scaling = capacity["CapacityProvider"].get("CapacityProviderScalingConfig", {})
    if not 2 <= scaling.get("MaxVCpuCount", 0) <= 128:
        raise PreconditionError("Dedicated provider must have an explicit maximum of 2-128 vCPUs")
    jar = ROOT / "target/lmi-fixtures.jar"
    digest = hashlib.sha256(jar.read_bytes()).digest()
    bucket_scope = hashlib.sha256(f"{region}:{stack_name}".encode()).hexdigest()[:12]
    manifest = {"runId": run_id, "stack": stack_name, "persistent": True, "account": account,
                "bucket": f"java-lmi-e2e-{account}-{bucket_scope}", "region": region,
                "role": os.environ["TEST_LAMBDA_EXECUTION_ROLE_ARN"], "provider": provider,
                "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "codeSha256": base64.b64encode(digest).decode(), "codeKey": f"code/{digest.hex()}.jar",
                "invocationTimeout": invocation_timeout, "created": int(time.time()), "functions": {}}
    save(MANIFEST, manifest)
    tags = [{"Key": "Suite", "Value": OWNER}, {"Key": "Stack", "Value": stack_name},
            {"Key": "Persistent", "Value": "true"}]
    ensure_bucket(manifest, tags)
    aws("s3api", "put-object", {"Bucket": manifest["bucket"], "Key": manifest["codeKey"]}, extra=["--body", str(jar)])
    deploy_fixtures(manifest, tags)
    manifest["logStartMillis"] = int(time.time() * 1000)
    save(MANIFEST, manifest)


def ensure_bucket(manifest, tags):
    """Reuse the suite-owned bucket; only per-run control objects have automatic expiry."""
    bucket = manifest["bucket"]
    exists = True
    try:
        aws("s3api", "head-bucket", {"Bucket": bucket, "ExpectedBucketOwner": manifest["account"]})
    except RuntimeError as error:
        if not any(text in str(error) for text in ("404", "Not Found", "NoSuchBucket")):
            raise
        exists = False
    if exists:
        actual = aws("s3api", "get-bucket-tagging", {"Bucket": bucket})["TagSet"]
        owner = {tag["Key"]: tag["Value"] for tag in actual}
        if owner.get("Suite") != OWNER or owner.get("Stack") != manifest["stack"]:
            raise PreconditionError("The persistent bucket is not owned by this test stack")
    else:
        request = {"Bucket": bucket}
        if manifest["region"] != "us-east-1":
            request["CreateBucketConfiguration"] = {"LocationConstraint": manifest["region"]}
        aws("s3api", "create-bucket", request)
        aws("s3api", "put-bucket-tagging", {"Bucket": bucket, "Tagging": {"TagSet": tags}})
    aws("s3api", "put-public-access-block", {"Bucket": bucket, "PublicAccessBlockConfiguration": {
        "BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True}})
    aws("s3api", "put-bucket-lifecycle-configuration", {"Bucket": bucket,
        "LifecycleConfiguration": {"Rules": [{"ID": "expire-controls", "Status": "Enabled",
            "Filter": {"Prefix": "control/"}, "Expiration": {"Days": 1},
            "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}}]}})


def remaining_budget(deadline, maximum):
    remaining = int(deadline - time.monotonic())
    if remaining <= 0:
        raise PreconditionError("Five-function provisioning budget exhausted")
    return min(remaining, maximum)


def existing_stack(name):
    try:
        return aws("cloudformation", "describe-stacks", {"StackName": name})["Stacks"][0]
    except RuntimeError as error:
        if "does not exist" in str(error):
            return None
        raise


def wait_for_idle_functions(stack, seconds=270):
    """Do not change code while previous executions of these persistent fixtures are running."""
    deadline = time.monotonic() + seconds
    while True:
        running = []
        for output in stack.get("Outputs", []):
            if output["OutputKey"] not in FIXTURES:
                continue
            function, qualifier = output["OutputValue"].rsplit(":", 1)
            marker = None
            while True:
                request = {"FunctionName": function, "Qualifier": qualifier, "Statuses": ["RUNNING"]}
                if marker:
                    request["Marker"] = marker
                response = aws("lambda", "list-durable-executions-by-function", request, extra=["--no-paginate"])
                running.extend(response.get("DurableExecutions", []))
                marker = response.get("NextMarker")
                if not marker:
                    break
                if time.monotonic() >= deadline:
                    raise PreconditionError("Could not finish checking previous executions; persistent code was not updated")
        save(ARTIFACTS / "pre-deploy-executions.json", running)
        if not running:
            return
        if time.monotonic() >= deadline:
            raise PreconditionError("Previous durable test executions are still running; persistent code was not updated")
        time.sleep(2)


def deploy_fixtures(manifest, tags, seconds=1800):
    """Create once, then update the same stack and functions without test teardown."""
    deadline = time.monotonic() + seconds
    remaining_budget(deadline, seconds)
    save(MANIFEST, manifest)
    spec = template(manifest)
    save(ARTIFACTS / "template.json", spec)
    stack = existing_stack(manifest["stack"])
    request = {"StackName": manifest["stack"], "TemplateBody": json.dumps(spec), "Tags": tags}
    if stack is None:
        request["TimeoutInMinutes"] = 25
        request["OnFailure"] = "DO_NOTHING"
        aws("cloudformation", "create-stack", request)
        expected = "CREATE_COMPLETE"
    else:
        owner = {tag["Key"]: tag["Value"] for tag in stack.get("Tags", [])}
        if owner.get("Suite") != OWNER:
            raise PreconditionError("The persistent stack is not owned by this suite")
        if stack["StackStatus"] not in {"CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE"}:
            raise PreconditionError(f"Persistent stack requires recovery from {stack['StackStatus']}; it was retained")
        wait_for_idle_functions(stack, seconds=remaining_budget(deadline, 270))
        try:
            aws("cloudformation", "update-stack", request)
            expected = "UPDATE_COMPLETE"
        except RuntimeError as error:
            if "No updates are to be performed" not in str(error):
                raise
            expected = None
    if expected:
        wait_stack(manifest["stack"], expected, remaining_budget(deadline, seconds))
    stack = existing_stack(manifest["stack"])
    outputs = {output["OutputKey"]: output["OutputValue"] for output in stack["Outputs"]}
    require(set(outputs) == set(FIXTURES), "The stack must expose all required LMI fixtures")
    for fixture in FIXTURES:
        arn = outputs[fixture]
        record_fixture(manifest, fixture, arn)
        verify_function_scaling(arn, fixture, seconds=remaining_budget(deadline, 300))


def record_fixture(manifest, key, arn):
    config = aws("lambda", "get-function-configuration", {"FunctionName": arn})
    save(ARTIFACTS / "configuration" / (key + ".json"), config)
    actual = config.get("CapacityProviderConfig", {}).get("LambdaManagedInstancesCapacityProviderConfig", {})
    require(actual.get("CapacityProviderArn") == manifest["provider"], "Deployment is not associated with the requested LMI provider")
    require(actual.get("PerExecutionEnvironmentMaxConcurrency") == FIXTURES[key][0], "Concurrency readback mismatch")
    require(config["Runtime"] == "java25" and config["Architectures"] == ["arm64"], "Unsupported runtime/architecture")
    require(config.get("DurableConfig", {}).get("ExecutionTimeout") == 240, "Function is not durable")
    require(config["Version"] == "$LATEST.PUBLISHED" and config["CodeSha256"] == manifest["codeSha256"], "Artifact/version mismatch")
    require(config["MemorySize"] == 2048, "Fixture must use the minimum 2 GiB / 1 vCPU allocation")
    manifest["functions"][key] = {"arn": arn, "logGroup": config["LoggingConfig"]["LogGroup"], "concurrency": FIXTURES[key][0]}
    save(MANIFEST, manifest)


def verify_function_scaling(arn, fixture, seconds=300):
    """Read back CloudFormation's applied limits and wait for the published version to be active."""
    desired = {"MinExecutionEnvironments": 1, "MaxExecutionEnvironments": 1}
    function_name, qualifier = arn.rsplit(":", 1)
    request = {"FunctionName": function_name, "Qualifier": qualifier}
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        scaling = aws("lambda", "get-function-scaling-config", request)
        config = aws("lambda", "get-function-configuration", {"FunctionName": arn})
        save(ARTIFACTS / "configuration" / (fixture + "-scaling.json"), scaling)
        save(ARTIFACTS / "configuration" / (fixture + ".json"), config)
        if config.get("State") == "Failed":
            raise PreconditionError("LMI version provisioning failed: " + config.get("StateReason", "unknown"))
        if scaling.get("AppliedFunctionScalingConfig") == desired and config.get("State") == "Active":
            return
        time.sleep(2)
    raise PreconditionError("Function scaling/provisioning did not reach the bounded configuration")


def wait_stack(name, expected, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        response = aws("cloudformation", "describe-stacks", {"StackName": name})
        status = response["Stacks"][0]["StackStatus"]
        if status == expected:
            return
        if "FAILED" in status or "ROLLBACK" in status:
            save(ARTIFACTS / "stack-events.json", aws("cloudformation", "describe-stack-events", {"StackName": name}))
            raise PreconditionError(f"Deployment {status}: see stack-events.json; no fallback to ordinary Lambda")
        time.sleep(5)
    raise PreconditionError("Provisioning budget exhausted")


def wait_returns(cloud, fixture, items, seconds=30):
    markers = {i["marker"] for i in items}
    cloud.poll(fixture, lambda events: markers <= {e["marker"] for e in selected(events, "WRAPPER_RETURN")}, seconds, items=items)


def assert_healthy_request_success(cloud, fixture, item):
    """A later durable retry cannot replace the admitted healthy request's outcome."""
    item.setdefault("healthyAdmission", (item["marker"], item["environment"], item["requestId"]))
    returns = cloud.poll(
        fixture, lambda events: selected(request_trace(
            events, item["marker"], item["requestId"], item["environment"]), "WRAPPER_RETURN"),
        seconds=PEER_LOG_OBSERVATION_SECONDS, items=[item])
    require(len(returns) == 1 and returns[0].get("status") == "SUCCEEDED",
            f"Original healthy request {item['requestId']} did not return SUCCEEDED")
    assert_lifecycle(request_trace(
        cloud.events_for(item), item["marker"], item["requestId"], item["environment"]))


def wait_for_wrapper_status(cloud, fixture, item, status, seconds=30):
    return cloud.poll(
        fixture,
        lambda events: any(e.get("status") == status
                           for e in selected(events, "WRAPPER_RETURN", item["marker"])),
        seconds=seconds,
        items=[item])


def healthy_peers(cloud, fixture, target, count, prefix):
    """Bounded placement only; never retry an established lifecycle assertion."""
    gate_name = prefix + "-gate"
    gate = cloud.gate(gate_name)
    admitted, attempts = [], []
    deadline = time.monotonic() + PEER_PLACEMENT_SECONDS
    for attempt in range(4):
        if len(admitted) >= count or time.monotonic() >= deadline:
            break
        batch = [cloud.launch(fixture, "hold", f"{prefix}-{attempt}-{i}", target=target, gate=gate)
                 for i in range(count - len(admitted))]
        attempts.extend(batch)
        markers = {i["marker"] for i in batch}
        observation_seconds = min(
            PEER_LOG_OBSERVATION_SECONDS, max(1, int(deadline - time.monotonic())))
        cloud.poll(fixture, lambda events: markers <= {e["marker"] for e in events
                   if e["kind"] in {"HEARTBEAT", "PLACEMENT_MISS"}},
                   seconds=observation_seconds, category=PreconditionError, items=batch)
        for item in batch:
            heartbeats = selected(cloud.events_for(item), "HEARTBEAT")
            if heartbeats:
                admitted_event = min(heartbeats, key=lambda event: event["epochMillis"])
                item["requestId"] = admitted_event["requestId"]
                item["environment"] = admitted_event["environment"]
                item["admittedNanos"] = admitted_event["nanos"]
                admitted.append(item)
    if len(admitted) != count:
        cloud.gate(gate_name, release=True)
        raise PreconditionError(f"Could not place {count} healthy peers in original environment {target}")
    return admitted, gate_name


def replay_case(cloud, fixture, scenario):
    prefix = uuid.uuid4().hex[:12]
    # A healthy holder supplies the target JVM before the suspension is triggered.
    anchor = None
    if fixture != "default1":
        gate_name = prefix + "-anchor"
        gate = cloud.gate(gate_name)
        anchor = cloud.launch(fixture, "hold", prefix + "-healthy", gate=gate)
        evidence = cloud.poll(fixture, lambda events: selected(events, "HEARTBEAT", anchor["marker"]), category=PreconditionError, items=[anchor])
        admitted = min(evidence, key=lambda event: event["epochMillis"])
        target = admitted["environment"]
        anchor["requestId"] = admitted["requestId"]
        anchor["environment"] = admitted["environment"]
    else:
        target = None
    victim = None
    for attempt in range(4):
        item = cloud.launch(fixture, scenario, f"{prefix}-victim-{attempt}", target=target)
        cloud.poll(fixture, lambda events: selected(events, "WRAPPER_RETURN", item["marker"]), seconds=30, items=[item])
        if not selected(cloud.events_for(item), "PLACEMENT_MISS"):
            victim = item
            break
    if victim is None:
        raise PreconditionError("No replay victim admitted to the anchor JVM")
    cloud.finish(victim)
    wait_for_wrapper_status(cloud, fixture, victim, "SUCCEEDED")
    if anchor:
        cloud.gate(gate_name, release=True)
        cloud.finish(anchor)
        wait_returns(cloud, fixture, [anchor])
        wait_for_runtime_quiescence(cloud, fixture, anchor["marker"])
        assert_healthy_request_success(cloud, fixture, anchor)
        # Root/task interval overlap is required, including the first victim invocation.
        assert_overlap(list(cloud.events.values()), {anchor["marker"], victim["marker"]}, 2, target)
    wait_for_runtime_quiescence(cloud, fixture, victim["marker"])
    history = cloud.history(victim)
    events = cloud.events_for(victim)
    victim["replayHistory"] = history
    assert_replay(events, history, victim["marker"])
    if scenario == "suspend":
        if anchor:
            cloud.poll(
                fixture, lambda events: cleanup_progress_observed(events, victim["marker"], anchor),
                seconds=PEER_LOG_OBSERVATION_SECONDS, items=[victim, anchor])
        assert_lifecycle(cloud.events_for(victim))


def overlap_case(cloud, fixture, target=None):
    count = FIXTURES[fixture][0]
    prefix = uuid.uuid4().hex[:12]
    gate_name = prefix + "-anchor"
    anchor = cloud.launch(fixture, "hold", prefix + "-anchor", target=target, gate=cloud.gate(gate_name))
    heartbeats = cloud.poll(fixture, lambda events: selected(events, "HEARTBEAT", anchor["marker"]), category=PreconditionError, items=[anchor])
    heartbeat = min(heartbeats, key=lambda event: event["epochMillis"])
    anchor["requestId"], anchor["environment"] = heartbeat["requestId"], heartbeat["environment"]
    require(target is None or heartbeat["environment"] == target,
            "Overlap anchor did not enter the requested original environment")
    peers, peer_gate = healthy_peers(cloud, fixture, heartbeat["environment"], count - 1, prefix + "-peer")
    items = [anchor] + peers
    assert_overlap(list(cloud.events.values()), {i["marker"] for i in items}, count, heartbeat["environment"])
    cloud.gate(gate_name, release=True)
    cloud.gate(peer_gate, release=True)
    for item in items:
        cloud.finish(item)
        cloud.history(item)
    wait_returns(cloud, fixture, items)
    wait_for_runtime_quiescence(cloud, fixture, {item["marker"] for item in items})
    for item in items:
        assert_healthy_request_success(cloud, fixture, item)
        assert_lifecycle(cloud.events_for(item))


def fixed_cohort_setup(events, markers):
    """Missing CloudWatch diagnostics are not affirmative placement-failure evidence."""
    events = [e for e in events if e["marker"] in markers]
    entered = selected(events, "BARRIER_ENTER")
    if len(entered) != len(markers) or {e["marker"] for e in entered} != markers:
        return None
    identities = request_identities(entered)
    if (identities != request_identities(events)
            or len({e["requestId"] for e in entered}) != len(markers)):
        return None
    passed = selected(events, "BARRIER_PASSED")
    if len(passed) == len(markers) and request_identities(passed) == identities:
        return "admitted"
    if not passed and len({e["environment"] for e in entered}) == len(markers):
        return "misplaced"
    return None


def fixed_attempt_events(events, markers):
    events = [e for e in events if e["marker"] in markers]
    require(not selected(events, "ESCAPE"),
            "A fixed-pool cohort needed an escape; refusing to rerun or pass the assertion")
    return events


def fixed_case(cloud, fixture, scenario):
    attempted = []
    for attempt in range(3):
        prefix = uuid.uuid4().hex[:12]
        items = [cloud.launch(fixture, scenario, prefix + f"-{i}", cohort=prefix, peers=2) for i in range(2)]
        attempted.extend(items)
        markers = {item["marker"] for item in items}
        all_markers = {item["marker"] for item in attempted}
        wait_returns(cloud, fixture, items, seconds=35)
        setup = cloud.poll(
            fixture, lambda events: fixed_cohort_setup(fixed_attempt_events(events, all_markers), markers),
            seconds=PEER_LOG_OBSERVATION_SECONDS, category=CollectionError, items=attempted)
        if setup == "misplaced":
            # Retry only a positively identified placement miss, after both executions
            # are terminal and their runtime work is drained. Recheck delayed evidence.
            cloud.finish_all(items, expected="FAILED")
            for item in items:
                cloud.history(item)
            wait_for_runtime_quiescence(cloud, fixture, all_markers)
            events = fixed_attempt_events([e for item in attempted for e in cloud.events_for(item)], all_markers)
            require(fixed_cohort_setup(events, markers) == "misplaced",
                    "Fixed-pool setup changed during settlement; refusing to rerun the assertion")
            assert_lifecycle(events)
            continue
        admitted = tuple(sorted(request_identities(selected(
            [event for item in items for event in cloud.events_for(item)], "BARRIER_ENTER"))))
        for item in items:
            item.setdefault("fixedAdmission", (admitted, scenario))
        if scenario == "nested":
            # A broken child barrier is setup failure, not nested starvation. Its
            # diagnostics may arrive after the wrapper-return CloudWatch batch.
            cloud.poll(fixture, lambda events: selected(
                [e for e in fixed_attempt_events(events, all_markers) if e["marker"] in markers],
                "CHILD_BARRIER_FAILED")
                or len([e for e in selected(events, "CHILD_BARRIER_PASSED")
                        if e["marker"] in markers]) == 2,
                seconds=PEER_LOG_OBSERVATION_SECONDS, items=attempted)
            events = [e for i in items for e in cloud.events_for(i)]
            if selected(events, "CHILD_BARRIER_FAILED"):
                raise PreconditionError("Nested child barrier was not established")
        # A failure after admission is final, even if the escape grows the pool.
        for item in items:
            cloud.finish(item)
            cloud.history(item)
        wait_for_runtime_quiescence(cloud, fixture, all_markers)
        all_events = fixed_attempt_events([e for item in attempted for e in cloud.events_for(item)], all_markers)
        events = [e for e in all_events if e["marker"] in markers]
        if scenario == "nested":
            assert_nested_reached(events, markers)
        assert_fixed(events, markers,
                     progress_start="CHILD_BARRIER_PASSED" if scenario == "nested" else "BARRIER_PASSED")
        assert_lifecycle(all_events)
        return
    raise PreconditionError("Two fixed-executor roots could not meet in one JVM")


def request_trace(events, marker, request_id, environment):
    """Return causally ordered events for one runtime request in one JVM."""
    return sorted(
        [e for e in events if e["marker"] == marker
         and e["requestId"] == request_id and e["environment"] == environment],
        key=lambda event: event["sequence"])


def cleanup_progress_observed(events, marker, peer):
    """Pair the original JVM's first suspension cleanup with its admitted healthy peer."""
    suspensions = [e for e in selected(events, "WRAPPER_RETURN", marker)
                   if e["environment"] == peer["environment"] and e.get("status") == "PENDING"]
    if not suspensions:
        return False
    suspended = min(suspensions, key=lambda e: e["sequence"])
    trace = request_trace(events, marker, suspended["requestId"], suspended["environment"])
    starts, ends = selected(trace, "CLEANUP_ENTER"), selected(trace, "CLEANUP_EXIT")
    if len(starts) != 1 or len(ends) != 1 or starts[0]["sequence"] >= ends[0]["sequence"]:
        return False
    peer_trace = request_trace(events, peer["marker"], peer["requestId"], peer["environment"])
    return any(starts[0]["sequence"] < e["sequence"] < ends[0]["sequence"]
               for e in selected(peer_trace, "HEARTBEAT"))


def assert_request_lifecycles(events, marker, allow_residual_tasks=False):
    requests = request_identities(selected(events, marker=marker))
    require(requests, f"No runtime request evidence for {marker}")
    for _, environment, request_id in requests:
        assert_lifecycle(
            request_trace(events, marker, request_id, environment),
            allow_residual=allow_residual_tasks)


def residual_outcome_observed(events, marker, request_id, environment):
    trace = request_trace(events, marker, request_id, environment)
    return bool(selected(trace, "RESIDUAL_EXIT")) and bool(
        selected(trace, "LATE_REJECTED") or selected(trace, "LATE_ACCEPTED"))


def wait_until_wall_time(cloud, fixture, wall_time, seconds, items):
    """Wait on the wall clock while surfacing failures from every invocation being observed."""
    return cloud.poll(fixture, lambda events: time.time() >= wall_time, seconds=seconds, items=items)


def runtime_requests_drained(events, markers, allow_no_entry=False):
    if isinstance(markers, str):
        markers = {markers}
    events = [e for e in events if e["marker"] in markers]
    requests = request_identities(events)
    entered = request_identities(selected(events, "WRAPPER_ENTER"))
    returned = request_identities(selected(events, "WRAPPER_RETURN"))
    # allow_no_entry permits an entirely unseen request, never activity with missing wrapper logs.
    if not (allow_no_entry or requests) or requests != entered or requests != returned:
        return False
    if not api_calls_complete(events):
        return False
    for marker, environment, request_id in requests:
        trace = request_trace(events, marker, request_id, environment)
        wrapper_returns = selected(trace, "WRAPPER_RETURN")
        if not wrapper_returns:
            return False
        if len(selected(trace, "ROOT_ENTER")) != len(selected(trace, "ROOT_EXIT")):
            return False
        task_enters = Counter(event.get("name") for event in selected(trace, "TASK_ENTER"))
        task_exits = Counter(event.get("name") for event in selected(trace, "TASK_EXIT"))
        if task_enters != task_exits:
            return False
        returned_at = wrapper_returns[-1]["sequence"]
        if any(event["sequence"] > returned_at and event["kind"] in {
                "BODY", "CHECKPOINT_CALL", "POLL_CALL", "CHECKPOINT_EXIT", "POLL_EXIT",
                "ROOT_ENTER", "TASK_ENTER"} for event in trace):
            return False
    return True


def wait_for_runtime_quiescence(
        cloud, fixture, markers, seconds=30, quiet_seconds=10, allow_no_entry=False):
    """Require every visible request to return and no request set changes during a quiet period."""
    if isinstance(markers, str):
        markers = {markers}
    deadline = time.monotonic() + seconds
    previous, quiet_since = None, None
    while True:
        events = cloud.refresh(fixture)
        current = request_identities([e for e in events if e["marker"] in markers])
        now = time.monotonic()
        if runtime_requests_drained(events, markers, allow_no_entry=allow_no_entry):
            if current != previous or quiet_since is None:
                quiet_since = now
            elif quiet_since is not None and now - quiet_since >= quiet_seconds:
                return events
        else:
            quiet_since = None
        previous = current
        if now >= deadline:
            raise AssertionError(f"Runtime requests for {sorted(markers)} did not reach stable quiescence")
        time.sleep(1)


def server_timeout_logs(cloud, request_id):
    return [event for event in cloud.raw_logs.values()
            if platform_timeout(event["message"], request_id)]


def timeout_evidence_ready(
        cloud, events, victim_marker, request_id, environment, peers, probes, deadline):
    trace = request_trace(events, victim_marker, request_id, environment)
    returned = selected(trace, "WRAPPER_RETURN")
    interrupted = selected(trace, "INTERRUPTED") + selected(trace, "IGNORED_INTERRUPT")
    cancellation = returned and returned[0]["status"] == "THREW" and interrupted
    recovered = recovery_admissions(events, environment, peers, probes, deadline)
    return (bool(returned) and bool(recovered)
            and bool(server_timeout_logs(cloud, request_id) or cancellation))


def recovery_admissions(events, environment, peers, probes, deadline):
    """Prove a probe could not have used a slot released by an original healthy peer.

    Wait for complete peer intervals: absence of an exit in delayed logs is not
    occupancy evidence. Retried peers and replacement JVMs cannot fill the interval.
    """
    intervals = []
    for peer in peers:
        trace = request_trace(events, peer["marker"], peer["requestId"], peer["environment"])
        boundaries = [selected(trace, "WRAPPER_ENTER"),
                      [e for e in selected(trace, "TASK_ENTER") if e.get("name") == "held-step"],
                      [e for e in selected(trace, "TASK_EXIT") if e.get("name") == "held-step"],
                      selected(trace, "WRAPPER_RETURN")]
        if peer["environment"] != environment or any(len(points) != 1 for points in boundaries):
            return []
        start, task_start, task_end, end = [points[0] for points in boundaries]
        if not start["sequence"] < task_start["sequence"] < task_end["sequence"] < end["sequence"]:
            return []
        beats = selected(trace, "HEARTBEAT")
        if not (any(e["nanos"] < deadline for e in beats) and any(e["nanos"] >= deadline for e in beats)):
            return []
        intervals.append((task_start["sequence"], task_end["sequence"]))
    return [e for probe in probes for e in selected(events, "TASK_ENTER", probe["marker"])
            if e.get("name") == "held-step" and e["environment"] == environment
            and e["nanos"] <= deadline + 8_000_000_000
            and all(start < e["sequence"] < end for start, end in intervals)]


def invocation_request_failures(items):
    """Reconciliation can recover an ARN, but cannot erase the driver's transport failure."""
    failures = []
    for item in items:
        future = item["future"]
        if not future.done():
            failures.append(CollectionError(f"Invocation request did not settle for {item['marker']}"))
            continue
        try:
            future.result()
        except Exception as failure:
            error = CollectionError(
                f"Invocation request failed for {item['marker']}: {type(failure).__name__}: {scrub(str(failure))}")
            failures.append(error)
    return failures


def cleanup_observation_seconds(items):
    """Allow pre-stop retries their bounded fixture hold, then collect a quiet trace."""
    # Match LifecycleHandler.stubborn's 120-second cap. The full hold is conservative
    # even when the retry's task-entry log has not arrived when stop_all completes.
    residual = max((max(0, min(item["holdMillis"], 120_000)) / 1000
                    for item in items if item.get("scenario") == "stubborn"), default=0)
    # Keep the existing collection margin and required quiet period after residual exit.
    return residual + 30 + PEER_LOG_OBSERVATION_SECONDS


def cleanup_case(cloud, fixture, invocation_start, seconds):
    """Release and terminate every request created by one case before the next case starts."""
    failures = []
    items = cloud.invocations[invocation_start:]
    try:
        cloud.release_all()
    except Exception as failure:
        failures.append(failure)
    try:
        cloud.stop_all(items, seconds=seconds)
    except Exception as failure:
        failures.append(failure)
    markers = {item["marker"] for item in items}
    if markers:
        try:
            wait_for_runtime_quiescence(
                cloud, fixture, markers, seconds=cleanup_observation_seconds(items),
                quiet_seconds=PEER_LOG_OBSERVATION_SECONDS,
                allow_no_entry=True)
        except Exception as failure:
            failures.append(failure)
    failures.extend(invocation_request_failures(items))
    if len(failures) > 1:
        raise CollectionError("; ".join(scrub(str(failure)) for failure in failures)) from failures[0]
    if failures:
        raise failures[0]


def timeout_case(cloud, fixture, stubborn=False):
    invocation_start = len(cloud.invocations)
    prefix = uuid.uuid4().hex[:12]
    timeout = cloud.manifest["invocationTimeout"]
    scenario = "stubborn" if stubborn else "timeout"
    victim = cloud.launch(fixture, scenario, prefix + "-victim", hold_ms=(timeout + 20) * 1000)
    target = assert_timeout_case(cloud, fixture, stubborn, prefix, timeout, victim)
    cleanup_case(cloud, fixture, invocation_start, timeout + 30)
    assert_request_lifecycles(
        cloud.events_for(victim), victim["marker"], allow_residual_tasks=stubborn)
    # All lanes, not just one replacement invocation, must be available again.
    overlap_case(cloud, fixture, target=target)


def assert_timeout_case(cloud, fixture, stubborn, prefix, timeout, victim):
    entries = cloud.poll(fixture, lambda events: selected(events, "TASK_ENTER", victim["marker"]),
                         category=PreconditionError, items=[victim])
    entry = min(entries, key=lambda event: event["epochMillis"])
    target = entry["environment"]
    request_id = entry["requestId"]
    victim.setdefault("timeoutAdmission", (victim["marker"], target, request_id))
    runtime_entry = cloud.poll(
        fixture,
        lambda events: selected(request_trace(events, victim["marker"], request_id, target), "WRAPPER_ENTER"),
        category=PreconditionError,
        items=[victim])[0]
    deadline_wall = (runtime_entry["epochMillis"] + runtime_entry["remainingMillis"]) / 1000
    deadline = runtime_entry["nanos"] + runtime_entry["remainingMillis"] * 1_000_000
    # Stagger admission: healthy invocations must outlive the victim's real deadline.
    wait_until_wall_time(cloud, fixture, deadline_wall - timeout / 2, timeout, [victim])
    peers, gate = healthy_peers(cloud, fixture, target, FIXTURES[fixture][0] - 1, prefix + "-peer")
    require(all(peer["environment"] == target and peer["admittedNanos"] < deadline for peer in peers),
            "Healthy peers were not active in the original JVM before the victim deadline")
    assert_overlap(list(cloud.events.values()), {victim["marker"], *(p["marker"] for p in peers)}, FIXTURES[fixture][0], target)
    # Launch probes while all other established slots remain occupied. Retry placement only.
    wait_until_wall_time(cloud, fixture, deadline_wall - 5, timeout, [victim])
    probe_gate_name = prefix + "-probe-gate"
    probe_gate = cloud.gate(probe_gate_name)
    probes = [cloud.launch(fixture, "probe", prefix + f"-probe-{i}", target=target, gate=probe_gate) for i in range(4)]
    try:
        wait_until_wall_time(cloud, fixture, deadline_wall + 8, 20, probes)
    finally:
        cloud.gate(gate, release=True)
        cloud.gate(probe_gate_name, release=True)
    cloud.finish_all(probes, expected=None, seconds=timeout + 30)
    for peer in peers:
        cloud.finish(peer)
    # Residual Java code is bounded, but arbitrary code cannot be forcibly killed.
    cloud.poll(
        fixture,
        lambda events: timeout_evidence_ready(
            cloud, events, victim["marker"], request_id, target, peers, probes, deadline),
        seconds=timeout + 30,
        items=[victim, *peers, *probes])
    if peers:
        # Slot-readiness evidence can precede a healthy root-exit log. Collect the
        # complete peer traces before applying their separate lifecycle assertions.
        wait_for_runtime_quiescence(cloud, fixture, {peer["marker"] for peer in peers})
    cloud.finish(victim, expected=None)
    history = cloud.history(victim)
    if stubborn:
        cloud.poll(
            fixture,
            lambda events: residual_outcome_observed(events, victim["marker"], request_id, target),
            seconds=timeout + 30,
            items=[victim])
    all_victim_events = cloud.events_for(victim)
    trace = request_trace(all_victim_events, victim["marker"], request_id, target)
    returned = selected(trace, "WRAPPER_RETURN")[0]
    recovered = recovery_admissions(list(cloud.events.values()), target, peers, probes, deadline)
    retry_request_ids = sorted({e["requestId"] for e in all_victim_events if e["requestId"] != request_id})
    report = {"requestId": request_id, "environment": target, "deadlineNanos": deadline,
              "wrapperReturnNanos": returned["nanos"], "taskExits": selected(trace, "TASK_EXIT"),
              "interrupts": selected(trace, "INTERRUPTED") + selected(trace, "IGNORED_INTERRUPT"),
              "recoveryAdmissions": recovered, "retryRequestIds": retry_request_ids,
              "durableHistoryEvents": len(history)}
    report["serverTimeoutLogs"] = server_timeout_logs(cloud, request_id)
    save(ARTIFACTS / "timeouts" / (prefix + ".json"), report)
    errors = []
    def check(condition, message):
        if not condition:
            errors.append(message)
    check(returned["nanos"] <= deadline + 5_000_000_000, "SDK cleanup exceeded the actual invocation deadline + 5s")
    check(bool(report["serverTimeoutLogs"]) or (returned["status"] == "THREW" and bool(report["interrupts"])),
          "Neither server-reported invocation timeout nor explicit SDK deadline cancellation observed")
    check(bool(recovered), "Affected worker slot was not recovered in the original JVM while healthy slots stayed occupied")
    for peer in peers:
        peer_events = request_trace(
            cloud.events_for(peer), peer["marker"], peer["requestId"], peer["environment"])
        heartbeats = selected(peer_events, "HEARTBEAT")
        check(any(e["nanos"] >= deadline for e in heartbeats), "Healthy invocation stopped progressing at victim deadline")
        assert_healthy_request_success(cloud, fixture, peer)
    if stubborn:
        check(bool(selected(trace, "IGNORED_INTERRUPT")), "Cancellation was not attempted for the non-cooperative child")
        check(bool(selected(trace, "LATE_REJECTED")) and not selected(trace, "LATE_ACCEPTED"), "Residual child could issue later SDK work")
        check(not [e for e in selected(trace, "BODY") if e["name"] == "late-work"], "Late step body executed")
    else:
        check(bool(selected(trace, "INTERRUPTED")), "Interruptible invocation task was not cancelled")
        exits = selected(trace, "TASK_EXIT")
        check(bool(exits) and exits[0]["nanos"] <= deadline + 5_000_000_000, "Task exit exceeded cleanup budget")
        check(not selected(trace, "ESCAPE"), "Timed-out task survived until the test-only escape")
    if errors:
        raise AssertionError("; ".join(errors))
    assert_lifecycle(trace, allow_residual=stubborn)
    return target


def inflight_case(cloud, fixture, scenario):
    prefix = uuid.uuid4().hex[:12]
    gate_name = prefix + "-gate"
    anchor = cloud.launch(fixture, "hold", prefix + "-healthy", gate=cloud.gate(gate_name))
    heartbeats = cloud.poll(fixture, lambda events: selected(events, "HEARTBEAT", anchor["marker"]), category=PreconditionError, items=[anchor])
    admitted = min(heartbeats, key=lambda event: event["epochMillis"])
    target = admitted["environment"]
    anchor["requestId"], anchor["environment"] = admitted["requestId"], target
    victim = cloud.launch(fixture, scenario, prefix + "-victim", target=target, hold_ms=1500)
    wait_returns(cloud, fixture, [victim])
    cloud.gate(gate_name, release=True)
    cloud.finish(anchor)
    wait_for_runtime_quiescence(cloud, fixture, anchor["marker"])
    assert_healthy_request_success(cloud, fixture, anchor)
    if selected(cloud.events_for(victim), "PLACEMENT_MISS"):
        raise PreconditionError("In-flight cleanup victim placed in another environment")
    cloud.finish(victim, expected="FAILED" if scenario == "failure-inflight" else "SUCCEEDED")
    cloud.history(victim)
    wait_for_runtime_quiescence(cloud, fixture, victim["marker"])
    assert_overlap(list(cloud.events.values()), {anchor["marker"], victim["marker"]}, 2, target)
    assert_lifecycle(cloud.events_for(victim))


def warm_environment(trace, target):
    environments = {event["environment"] for event in trace
                    if event["kind"] in {"WRAPPER_ENTER", "WRAPPER_RETURN"}}
    require(environments, "Missing warm invocation environment evidence")
    if target is None:
        require(len(environments) == 1, "Warm execution started across multiple environments")
        return next(iter(environments))
    require(environments == {target}, "Warm replay resumed in a replacement environment")
    return target


def warm_case(cloud, fixture):
    target, items = None, []
    for batch in range(3):
        for scenario in ["success", "failure", "replay"]:
            marker = uuid.uuid4().hex[:12]
            item = cloud.launch(fixture, scenario, marker, target=target)
            wait_returns(cloud, fixture, [item])
            if selected(cloud.events_for(item), "PLACEMENT_MISS"):
                raise PreconditionError("Warm-environment fixture replaced; cannot claim no accumulation")
            cloud.finish(item, expected="FAILED" if scenario == "failure" else "SUCCEEDED")
            if scenario == "replay":
                wait_for_wrapper_status(cloud, fixture, item, "SUCCEEDED")
            wait_for_runtime_quiescence(cloud, fixture, item["marker"])
            trace = cloud.events_for(item)
            target = warm_environment(trace, target)
            item.setdefault("warmEnvironment", target)
            item.setdefault("warmRequests", sorted(request_identities(trace)))
            items.append(item)
            if scenario == "replay":
                item["replayHistory"] = cloud.history(item)
                assert_replay(trace, item["replayHistory"], marker)
            assert_lifecycle(trace)
    # Preserve proven resource failures even if cleanup also fails. An incomplete endpoint
    # must wait for the final case audit before it can establish thread growth.
    complete = not incomplete_warm_snapshots(cloud, items)
    snapshots = [event for item in items for event in selected(cloud.events_for(item), "SNAPSHOT")]
    assert_warm_resources(snapshots)
    if complete:
        assert_warm_snapshots(snapshots)


def assert_warm_resources(snapshots):
    require(all(e["liveTasks"] == 0 and e["liveRoots"] == 0 and e["queued"] == 0 for e in snapshots),
            "Invocation-owned tasks, roots or queued work accumulated across warm batches")


def assert_warm_snapshots(snapshots):
    # All snapshots belong to the same JVM, whose sequence gives causal order.
    snapshots = sorted(snapshots, key=lambda event: event["sequence"])
    # Default cached pools retain idle threads: measure live invocation-owned work/queue instead.
    require(snapshots, "Missing warm task snapshots")
    assert_warm_resources(snapshots)
    require(snapshots[-1]["threads"] <= snapshots[0]["threads"] + 16,
            "Thread growth exceeded bounded cache tolerance (+16)")


def incomplete_warm_snapshots(cloud, items):
    missing = set()
    for item in items:
        expected = {tuple(identity) for identity in item.get("warmRequests", [])}
        if not expected:
            raise CollectionError(f"Missing original warm request identities for {item['marker']}")
        events = cloud.events_for(item)
        if selected(events, "WRAPPER_ENTER") or selected(events, "WRAPPER_RETURN"):
            warm_environment(events, item["warmEnvironment"])
        require(request_identities(events) <= expected,
                "Original warm request cohort changed after collecting delayed evidence")
        for identity in expected:
            marker, environment, request_id = identity
            trace = request_trace(events, marker, request_id, environment)
            returns, snapshots = selected(trace, "WRAPPER_RETURN"), selected(trace, "SNAPSHOT")
            if (len(returns) != 1 or len(snapshots) != 1
                    or snapshots[0]["sequence"] >= returns[0]["sequence"]):
                missing.add(identity)
    return missing


def collect_warm_snapshots(cloud, items, seconds=30):
    """Complete the original warm measurements without changing their observation times."""
    missing = incomplete_warm_snapshots(cloud, items)
    if not missing:
        return
    deadline = time.monotonic() + seconds
    fixtures = {item["fixture"] for item in items}
    while missing:
        if time.monotonic() >= deadline:
            raise CollectionError(f"Incomplete warm wrapper-return snapshots for {sorted(missing)}")
        for fixture in sorted(fixtures):
            cloud.refresh(fixture)
        missing = incomplete_warm_snapshots(cloud, items)
        if missing:
            time.sleep(1)


def cases_for_fixture(cloud, fixture):
    if fixture == "default1":
        return [("baseline-concurrency1", lambda: replay_case(cloud, fixture, "baseline"))]
    if fixture == "fixed2":
        return [("isolated-fixed-two-roots", lambda: fixed_case(cloud, fixture, "fixed"))]
    if fixture == "nested2":
        return []
    cases = [(fixture + "-isolation", lambda: overlap_case(cloud, fixture)),
             (fixture + "-suspend-cleanup-replay", lambda: replay_case(cloud, fixture, "suspend")),
             (fixture + "-timeout-recovery", lambda: timeout_case(cloud, fixture))]
    if fixture == "default2":
        cases += [("non-cooperative-child", lambda: timeout_case(cloud, fixture, True)),
                  ("return-with-inflight-step", lambda: inflight_case(cloud, fixture, "return-inflight")),
                  ("failure-with-inflight-step", lambda: inflight_case(cloud, fixture, "failure-inflight")),
                  ("warm-repeated-batches", lambda: warm_case(cloud, fixture))]
    return cases


def assert_original_healthy_admission(events, admission):
    """Reject changed admission evidence instead of repinning earlier timing assertions."""
    marker, environment, request_id = admission
    heartbeats = selected(events, "HEARTBEAT", marker)
    require(heartbeats, "Missing original healthy admission heartbeat")
    original = min(heartbeats, key=lambda event: event["epochMillis"])
    require((original["marker"], original["environment"], original["requestId"]) == admission,
            "Original healthy admission changed after collecting delayed evidence")
    returns = selected(request_trace(events, marker, request_id, environment), "WRAPPER_RETURN")
    require(len(returns) == 1 and returns[0].get("status") == "SUCCEEDED",
            f"Original healthy request {request_id} did not return SUCCEEDED")


def assert_original_timeout_admission(events, admission):
    """A retry cannot inherit the first selected request's deadline and recovery evidence."""
    entries = selected(events, "TASK_ENTER", admission[0])
    require(entries, "Missing original timeout admission task entry")
    original = min(entries, key=lambda event: event["epochMillis"])
    require((original["marker"], original["environment"], original["requestId"]) == admission,
            "Original timeout admission changed after collecting delayed evidence")


def assert_original_fixed_admission(cloud, items, admission):
    """Recheck cohort identity and progress after the last collection window."""
    original, scenario = admission
    markers = {identity[0] for identity in original}
    events = [event for item in items if item["marker"] in markers for event in cloud.events_for(item)]
    require(fixed_cohort_setup(events, markers) == "admitted"
            and request_identities(selected(events, "BARRIER_ENTER")) == set(original),
            "Original fixed cohort changed after collecting delayed evidence")
    if scenario == "nested":
        assert_nested_reached(events, markers)
    assert_fixed(events, markers,
                 progress_start="CHILD_BARRIER_PASSED" if scenario == "nested" else "BARRIER_PASSED")


def assert_case_request_lifecycles(cloud, invocation_start, invocation_end=None):
    """Settlement cannot turn an earlier wrapper-ordering violation into a passing case."""
    items = cloud.invocations[invocation_start:invocation_end]
    warm_items = [item for item in items if "warmEnvironment" in item]
    if warm_items:
        collect_warm_snapshots(cloud, warm_items)
    checked_cohorts, warm_snapshots = set(), []
    for item in items:
        events = cloud.events_for(item)
        assert_request_lifecycles(
            events, item["marker"], allow_residual_tasks=item.get("scenario") == "stubborn")
        if "healthyAdmission" in item:
            assert_original_healthy_admission(events, item["healthyAdmission"])
        if "timeoutAdmission" in item:
            assert_original_timeout_admission(events, item["timeoutAdmission"])
        if "replayHistory" in item:
            assert_replay(events, item["replayHistory"], item["marker"])
        if "warmEnvironment" in item:
            warm_environment(events, item["warmEnvironment"])
            warm_snapshots.extend(selected(events, "SNAPSHOT"))
        if "fixedAdmission" in item and item["fixedAdmission"] not in checked_cohorts:
            assert_original_fixed_admission(cloud, items, item["fixedAdmission"])
            checked_cohorts.add(item["fixedAdmission"])
    if warm_items:
        assert_warm_snapshots(warm_snapshots)


def execute_case(cloud, fixture, case):
    """Run one assertion and return its primary and isolation-cleanup failures separately."""
    invocation_start = len(cloud.invocations)
    primary_failure = None
    try:
        case()
    except Exception as failure:
        primary_failure = failure
    cleanup_failure = None
    try:
        cleanup_case(cloud, fixture, invocation_start, cloud.manifest["invocationTimeout"] + 30)
    except Exception as failure:
        cleanup_failure = failure
    if primary_failure is None and cleanup_failure is None:
        try:
            assert_case_request_lifecycles(cloud, invocation_start)
        except Exception as failure:
            primary_failure = failure
    return primary_failure, cleanup_failure


def record_failure(node, failure, kind=None):
    kind = kind or ("failure" if isinstance(failure, AssertionError) else "error")
    ET.SubElement(node, kind, type=type(failure).__name__, message=str(failure)).text = "".join(
        traceback.format_exception(type(failure), failure, failure.__traceback__))
    print(kind.upper(), node.get("name"), str(failure), flush=True)


def save_case_assertions(cloud, records):
    fields = {"marker", "fixture", "scenario", "healthyAdmission", "timeoutAdmission", "warmEnvironment",
              "warmRequests", "fixedAdmission", "replayHistory"}
    save(ARTIFACTS / "case-assertions.json", {
        "runId": cloud.manifest["runId"], "commit": cloud.manifest["commit"],
        "cases": [{"name": node.get("name"), "items": [
            {key: value for key, value in item.items() if key in fields}
            for item in cloud.invocations[start:end]]} for node, start, end in records]})


def revalidate_cases(cloud, records):
    for node, start, end in records:
        if node.find("failure") is not None or node.find("error") is not None:
            continue
        try:
            assert_case_request_lifecycles(cloud, start, end)
        except Exception as failure:
            record_failure(node, failure)


def run_tests():
    manifest = json.loads(MANIFEST.read_text())
    if set(manifest["functions"]) != set(FIXTURES):
        raise PreconditionError("All required functions must be deployed before running the cloud suite")
    cloud = Cloud(manifest, ARTIFACTS)
    suite = ET.Element("testsuite", name="LMI cloud lifecycle")
    records = []
    cases = [(fixture, name, case) for fixture in FIXTURES
             for name, case in cases_for_fixture(cloud, fixture)]
    try:
        for fixture, name, case in cases:
            started = time.monotonic()
            node = ET.SubElement(suite, "testcase", classname="LmiCloudLifecycle", name=name)
            invocation_start = len(cloud.invocations)
            primary_failure, cleanup_failure = execute_case(cloud, fixture, case)
            records.append((node, invocation_start, len(cloud.invocations)))
            save_case_assertions(cloud, records)
            if primary_failure is not None:
                record_failure(node, primary_failure)
            if cleanup_failure is not None:
                record_failure(node, cleanup_failure, "error")
            refresh_failure = None
            try:
                for current_fixture in manifest["functions"]:
                    cloud.refresh(current_fixture)
            except Exception as error:
                refresh_failure = error
                ET.SubElement(node, "error", type="CollectionError", message=str(error))
            revalidate_cases(cloud, records)
            if not len(node):
                print("PASS", name, flush=True)
            node.set("time", str(round(time.monotonic() - started, 3)))
            write_junit(suite)
            if cleanup_failure is not None or refresh_failure is not None:
                break
    finally:
        cloud.close()
        write_junit(suite)
    require(not suite.findall(".//failure") and not suite.findall(".//error"), "LMI cloud cases failed; see JUnit and artifacts")


def revalidate_collected_cases(cloud):
    path = ARTIFACTS / "case-assertions.json"
    if not path.exists():
        return
    saved = json.loads(path.read_text())
    require(saved["runId"] == cloud.manifest["runId"] and saved["commit"] == cloud.manifest["commit"],
            "Case assertions belong to a different deployed run")
    suite = ET.parse(ARTIFACTS / "junit.xml").getroot()
    nodes = {node.get("name"): node for node in suite.findall("testcase")}
    records = []
    for case in saved["cases"]:
        start = len(cloud.invocations)
        for item in case["items"]:
            for field in ("healthyAdmission", "timeoutAdmission"):
                if field in item:
                    item[field] = tuple(item[field])
            if "fixedAdmission" in item:
                identities, scenario = item["fixedAdmission"]
                item["fixedAdmission"] = (tuple(tuple(identity) for identity in identities), scenario)
            cloud.invocations.append(item)
        records.append((nodes[case["name"]], start, len(cloud.invocations)))
    revalidate_cases(cloud, records)
    write_junit(suite)
    require(not suite.findall(".//failure") and not suite.findall(".//error"),
            "LMI cloud cases failed after final collection; see JUnit and artifacts")


def write_junit(suite):
    suite.set("tests", str(len(suite.findall("testcase"))))
    suite.set("failures", str(len(suite.findall(".//failure"))))
    suite.set("errors", str(len(suite.findall(".//error"))))
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(ARTIFACTS / "junit.xml", encoding="unicode", xml_declaration=True)


def collect():
    manifest = json.loads(MANIFEST.read_text())
    cloud = Cloud(manifest, ARTIFACTS)
    errors = cloud.restore_evidence()
    for fixture in manifest["functions"]:
        try:
            cloud.refresh(fixture)
        except Exception as error:
            errors.append(str(error))
    executions = {e["executionArn"]: e["marker"] for e in cloud.events.values()}
    for path in sorted((ARTIFACTS / "invocations").glob("*.json")):
        try:
            invocation = json.loads(path.read_text())
            if invocation.get("runId") != manifest["runId"]:
                continue
            arn = invocation.get("executionArn") or invocation.get("headers", {}).get("DurableExecutionArn")
            if not arn and invocation.get("state") in {"STARTED", "REQUEST_FAILED", "RETURNED"}:
                arn = cloud.reconcile_invocation(invocation)
            if arn:
                executions[arn] = invocation["marker"]
        except Exception as error:
            errors.append(f"{path.name}: {error}")
    for arn, marker in executions.items():
        try:
            cloud.history({"arn": arn, "marker": marker})
            save(ARTIFACTS / "executions" / (marker + ".json"), aws("lambda", "get-durable-execution", {"DurableExecutionArn": arn}))
        except Exception as error:
            errors.append(str(error))
    cloud.pool.shutdown()
    try:
        revalidate_collected_cases(cloud)
    except Exception as error:
        errors.append(str(error))
    save(ARTIFACTS / "collection-errors.json", errors)
    require(not errors, "Evidence collection failed: " + "; ".join(errors))



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["deploy", "test", "collect"])
    parser.add_argument("--run-id")
    parser.add_argument("--stack-name", default=DEFAULT_STACK)
    parser.add_argument("--cloud-enabled", action="store_true")
    parser.add_argument("--invocation-timeout", type=int, default=60, choices=range(45, 91))
    args = parser.parse_args()
    try:
        if args.command == "deploy":
            deploy(args.run_id, args.invocation_timeout, args.stack_name)
        elif args.command == "test":
            if not args.cloud_enabled:
                parser.error("Real cloud tests require --cloud-enabled")
            run_tests()
        else:
            collect()
    except Exception as error:
        save(ARTIFACTS / (args.command + "-error.json"), {"category": args.command, "error": str(error)})
        raise


if __name__ == "__main__":
    main()
