# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Opt-in real-service LMI tests. See README.md for design and ownership."""
import argparse
import base64
import concurrent.futures
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

from cloud_support import (Cloud, CollectionError, PreconditionError, assert_fixed,
                           assert_lifecycle, assert_overlap, assert_replay, aws,
                           require, save, selected)

ROOT = Path(__file__).resolve().parent
ARTIFACTS = ROOT / "artifacts"
MANIFEST = ARTIFACTS / "manifest.json"
OWNER = "java-sdk-lmi-e2e"
FIXTURES = {"default1": (1, "default"), "default2": (2, "default"),
            "default8": (8, "default"), "fixed2": (2, "fixed"), "nested2": (2, "fixed")}


def template(manifest):
    resources, outputs = {}, {}
    for key, (concurrency, executor) in FIXTURES.items():
        name = manifest["stack"] + "-" + key
        log_id, fn_id, version_id = key + "Logs", key + "Function", key + "Version"
        resources[log_id] = {"Type": "AWS::Logs::LogGroup", "Properties": {
            "LogGroupName": "/aws/lambda/" + name, "RetentionInDays": 1}}
        resources[fn_id] = {"Type": "AWS::Lambda::Function", "Properties": {
            "FunctionName": name, "Runtime": "java25", "Architectures": ["x86_64"],
            "Role": manifest["role"], "Handler": "software.amazon.lambda.durable.lmi.LifecycleHandler",
            "Code": {"S3Bucket": manifest["bucket"], "S3Key": "lmi-fixtures.jar"},
            "Timeout": manifest["invocationTimeout"],
            "DurableConfig": {"ExecutionTimeout": 240, "RetentionPeriodInDays": 1},
            "CapacityProviderConfig": {"LambdaManagedInstancesCapacityProviderConfig": {
                "CapacityProviderArn": manifest["provider"],
                "PerExecutionEnvironmentMaxConcurrency": concurrency,
                "ExecutionEnvironmentMemoryGiBPerVCpu": 2}},
            "Environment": {"Variables": {"LMI_EXECUTOR": executor, "LMI_COMMIT": manifest["commit"]}},
            "LoggingConfig": {"LogFormat": "JSON", "ApplicationLogLevel": "INFO",
                              "SystemLogLevel": "INFO", "LogGroup": {"Ref": log_id}}}}
        resources[version_id] = {"Type": "AWS::Lambda::Version", "Properties": {
            "FunctionName": {"Ref": fn_id}, "CodeSha256": manifest["codeSha256"],
            "Description": manifest["commit"]}}
        outputs[key] = {"Value": {"Ref": version_id}}
    return {"AWSTemplateFormatVersion": "2010-09-09", "Resources": resources, "Outputs": outputs}


def deploy(run_id, invocation_timeout):
    if not re.fullmatch(r"[a-z0-9-]{1,24}", run_id):
        raise PreconditionError("run-id must be 1-24 lowercase letters, digits, or hyphens")
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
    manifest = {"runId": run_id, "stack": "java-lmi-e2e-" + run_id,
                "bucket": f"java-lmi-e2e-{account}-{run_id}", "region": region,
                "role": os.environ["TEST_LAMBDA_EXECUTION_ROLE_ARN"], "provider": provider,
                "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "codeSha256": base64.b64encode(hashlib.sha256(jar.read_bytes()).digest()).decode(),
                "invocationTimeout": invocation_timeout, "created": int(time.time()), "functions": {}}
    save(MANIFEST, manifest)  # Persist ownership before any mutation, including partial setup.
    request = {"Bucket": manifest["bucket"]}
    if region != "us-east-1":
        request["CreateBucketConfiguration"] = {"LocationConstraint": region}
    aws("s3api", "create-bucket", request)
    tags = [{"Key": "Suite", "Value": OWNER}, {"Key": "Created", "Value": str(manifest["created"])}]
    aws("s3api", "put-bucket-tagging", {"Bucket": manifest["bucket"], "Tagging": {"TagSet": tags}})
    aws("s3api", "put-public-access-block", {"Bucket": manifest["bucket"], "PublicAccessBlockConfiguration": {
        "BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True}})
    aws("s3api", "put-bucket-lifecycle-configuration", {"Bucket": manifest["bucket"],
        "LifecycleConfiguration": {"Rules": [{"ID": "expire", "Status": "Enabled", "Filter": {"Prefix": ""},
                                              "Expiration": {"Days": 1}, "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}}]}})
    aws("s3api", "put-object", {"Bucket": manifest["bucket"], "Key": "lmi-fixtures.jar"}, extra=["--body", str(jar)])
    spec = template(manifest)
    save(ARTIFACTS / "template.json", spec)
    aws("cloudformation", "create-stack", {"StackName": manifest["stack"], "TemplateBody": json.dumps(spec),
        "Tags": tags, "TimeoutInMinutes": 15})
    wait_stack(manifest["stack"], "CREATE_COMPLETE", 960)
    stack = aws("cloudformation", "describe-stacks", {"StackName": manifest["stack"]})["Stacks"][0]
    for output in stack["Outputs"]:
        key, arn = output["OutputKey"], output["OutputValue"]
        config = aws("lambda", "get-function-configuration", {"FunctionName": arn})
        save(ARTIFACTS / "configuration" / (key + ".json"), config)
        actual = config.get("CapacityProviderConfig", {}).get("LambdaManagedInstancesCapacityProviderConfig", {})
        require(actual.get("CapacityProviderArn") == provider, "Deployment is not associated with the requested LMI provider")
        require(actual.get("PerExecutionEnvironmentMaxConcurrency") == FIXTURES[key][0], "Concurrency readback mismatch")
        require(config["Runtime"] == "java25" and config["Architectures"] == ["x86_64"], "Unsupported runtime/architecture")
        require(config.get("DurableConfig", {}).get("ExecutionTimeout") == 240, "Function is not durable")
        require(config["Version"].isdigit() and config["CodeSha256"] == manifest["codeSha256"], "Artifact/version mismatch")
        manifest["functions"][key] = {"arn": arn, "logGroup": config["LoggingConfig"]["LogGroup"], "concurrency": FIXTURES[key][0]}
        save(MANIFEST, manifest)
    manifest["logStartMillis"] = int(time.time() * 1000)
    save(MANIFEST, manifest)


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
    cloud.poll(fixture, lambda events: markers <= {e["marker"] for e in selected(events, "WRAPPER_RETURN")}, seconds)


def healthy_peers(cloud, fixture, target, count, prefix):
    """Bounded placement only; never retry an established lifecycle assertion."""
    gate_name = prefix + "-gate"
    gate = cloud.gate(gate_name)
    admitted, attempts = [], []
    deadline = time.monotonic() + 25
    for attempt in range(4):
        if len(admitted) >= count or time.monotonic() >= deadline:
            break
        batch = [cloud.launch(fixture, "hold", f"{prefix}-{attempt}-{i}", target=target, gate=gate)
                 for i in range(count - len(admitted))]
        attempts.extend(batch)
        markers = {i["marker"] for i in batch}
        cloud.poll(fixture, lambda events: markers <= {e["marker"] for e in events
                   if e["kind"] in {"HEARTBEAT", "PLACEMENT_MISS"}}, seconds=8, category=PreconditionError)
        admitted += [i for i in batch if selected(cloud.events_for(i), "HEARTBEAT")]
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
        evidence = cloud.poll(fixture, lambda events: selected(events, "HEARTBEAT", anchor["marker"]), category=PreconditionError)
        target = evidence[0]["environment"]
    else:
        target = None
    victim = None
    for attempt in range(4):
        item = cloud.launch(fixture, scenario, f"{prefix}-victim-{attempt}", target=target)
        cloud.poll(fixture, lambda events: selected(events, "WRAPPER_RETURN", item["marker"]), seconds=30)
        if not selected(cloud.events_for(item), "PLACEMENT_MISS"):
            victim = item
            break
    if victim is None:
        raise PreconditionError("No replay victim admitted to the anchor JVM")
    cloud.finish(victim)
    cloud.poll(fixture, lambda events: any(e.get("status") == "SUCCEEDED" for e in selected(events, "WRAPPER_RETURN", victim["marker"])))
    if anchor:
        cloud.gate(gate_name, release=True)
        cloud.finish(anchor)
        wait_returns(cloud, fixture, [anchor])
        # Root/task interval overlap is required, including the first victim invocation.
        assert_overlap(list(cloud.events.values()), {anchor["marker"], victim["marker"]}, 2, target)
    history = cloud.history(victim)
    events = cloud.events_for(victim)
    assert_replay(events, history, victim["marker"])
    if scenario == "suspend":
        if anchor:
            cleanup = selected(events, "CLEANUP_ENTER")[0]
            exits = selected(events, "CLEANUP_EXIT")
            require(any(e["environment"] == target and cleanup["nanos"] <= e["nanos"] <= exits[0]["nanos"]
                        for e in selected(cloud.events_for(anchor), "HEARTBEAT")),
                    "Healthy invocation did not progress during root cleanup")
        assert_lifecycle(events)


def overlap_case(cloud, fixture, target=None):
    count = FIXTURES[fixture][0]
    prefix = uuid.uuid4().hex[:12]
    gate_name = prefix + "-anchor"
    anchor = cloud.launch(fixture, "hold", prefix + "-anchor", target=target, gate=cloud.gate(gate_name))
    heartbeat = cloud.poll(fixture, lambda events: selected(events, "HEARTBEAT", anchor["marker"]), category=PreconditionError)[0]
    peers, peer_gate = healthy_peers(cloud, fixture, heartbeat["environment"], count - 1, prefix + "-peer")
    items = [anchor] + peers
    assert_overlap(list(cloud.events.values()), {i["marker"] for i in items}, count, heartbeat["environment"])
    cloud.gate(gate_name, release=True)
    cloud.gate(peer_gate, release=True)
    for item in items:
        cloud.finish(item)
        cloud.history(item)
    wait_returns(cloud, fixture, items)
    for item in items:
        assert_lifecycle(cloud.events_for(item))


def fixed_case(cloud, fixture, scenario):
    for attempt in range(3):
        prefix = uuid.uuid4().hex[:12]
        items = [cloud.launch(fixture, scenario, prefix + f"-{i}", cohort=prefix, peers=2) for i in range(2)]
        wait_returns(cloud, fixture, items, seconds=35)
        events = [e for i in items for e in cloud.events_for(i)]
        if len(selected(events, "BARRIER_PASSED")) == 2:
            # A failure after admission is final, even if the escape grows the pool.
            for item in items:
                cloud.finish(item)
                cloud.history(item)
            assert_fixed(events, {i["marker"] for i in items})
            assert_lifecycle(events)
            return
        if selected(events, "BARRIER_PASSED") or selected(events, "ESCAPE"):
            raise AssertionError("Partial fixed-pool admission or escape; refusing to rerun the assertion")
    raise PreconditionError("Two fixed-executor roots could not meet in one JVM")


def invocation_deadline(trace):
    entry = selected(trace, "WRAPPER_ENTER")[0]
    return entry["nanos"] + entry["remainingMillis"] * 1_000_000


def timeout_case(cloud, fixture, stubborn=False):
    prefix = uuid.uuid4().hex[:12]
    timeout = cloud.manifest["invocationTimeout"]
    scenario = "stubborn" if stubborn else "timeout"
    victim = cloud.launch(fixture, scenario, prefix + "-victim", hold_ms=(timeout + 20) * 1000)
    entry = cloud.poll(fixture, lambda events: selected(events, "TASK_ENTER", victim["marker"]), category=PreconditionError)[0]
    target = entry["environment"]
    # Stagger admission: healthy invocations must outlive the victim's real deadline.
    cloud.poll(fixture, lambda events: time.time() - victim["started"] >= timeout / 2, seconds=timeout)
    peers, gate = healthy_peers(cloud, fixture, target, FIXTURES[fixture][0] - 1, prefix + "-peer")
    assert_overlap(list(cloud.events.values()), {victim["marker"], *(p["marker"] for p in peers)}, FIXTURES[fixture][0], target)
    deadline = invocation_deadline(cloud.events_for(victim))
    # Launch probes while all other established slots remain occupied. Retry placement only.
    cloud.poll(fixture, lambda events: time.time() - victim["started"] >= timeout - 5, seconds=timeout)
    probe_gate_name = prefix + "-probe-gate"
    probe_gate = cloud.gate(probe_gate_name)
    probes = [cloud.launch(fixture, "probe", prefix + f"-probe-{i}", target=target, gate=probe_gate) for i in range(4)]
    cloud.poll(fixture, lambda events: time.time() - victim["started"] >= timeout + 8, seconds=20)
    cloud.gate(gate, release=True)
    cloud.gate(probe_gate_name, release=True)
    for peer in peers:
        cloud.finish(peer)
    # Residual Java code is bounded, but arbitrary code cannot be forcibly killed.
    wait_returns(cloud, fixture, [victim], seconds=timeout + 30)
    cloud.finish(victim, expected=None)
    history = cloud.history(victim)
    trace = cloud.events_for(victim)
    returned = selected(trace, "WRAPPER_RETURN")[0]
    recovered = [e for p in probes for e in selected(cloud.events_for(p), "TASK_ENTER")
                 if e["environment"] == target and e["nanos"] <= deadline + 8_000_000_000]
    report = {"requestId": entry["requestId"], "environment": target, "deadlineNanos": deadline,
              "wrapperReturnNanos": returned["nanos"], "taskExits": selected(trace, "TASK_EXIT"),
              "interrupts": selected(trace, "INTERRUPTED") + selected(trace, "IGNORED_INTERRUPT"),
              "recoveryAdmissions": recovered, "durableHistoryEvents": len(history)}
    report["serverTimeoutLogs"] = [e for e in cloud.raw_logs.values() if entry["requestId"] in e["message"]
                                   and ("timeout" in e["message"].lower() or "timed out" in e["message"].lower())]
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
        heartbeats = selected(cloud.events_for(peer), "HEARTBEAT")
        check(any(e["nanos"] >= deadline for e in heartbeats), "Healthy invocation stopped progressing at victim deadline")
        assert_lifecycle(cloud.events_for(peer))
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
    # All lanes, not just one replacement invocation, must be available again.
    overlap_case(cloud, fixture, target=target)


def inflight_case(cloud, fixture, scenario):
    prefix = uuid.uuid4().hex[:12]
    gate_name = prefix + "-gate"
    anchor = cloud.launch(fixture, "hold", prefix + "-healthy", gate=cloud.gate(gate_name))
    target = cloud.poll(fixture, lambda events: selected(events, "HEARTBEAT", anchor["marker"]), category=PreconditionError)[0]["environment"]
    victim = cloud.launch(fixture, scenario, prefix + "-victim", target=target, hold_ms=1500)
    wait_returns(cloud, fixture, [victim])
    cloud.gate(gate_name, release=True)
    cloud.finish(anchor)
    if selected(cloud.events_for(victim), "PLACEMENT_MISS"):
        raise PreconditionError("In-flight cleanup victim placed in another environment")
    cloud.finish(victim, expected="FAILED" if scenario == "failure-inflight" else "SUCCEEDED")
    cloud.history(victim)
    assert_overlap(list(cloud.events.values()), {anchor["marker"], victim["marker"]}, 2, target)
    assert_lifecycle(cloud.events_for(victim))


def warm_case(cloud, fixture):
    target, snapshots = None, []
    for batch in range(3):
        for scenario in ["success", "failure", "replay"]:
            marker = uuid.uuid4().hex[:12]
            item = cloud.launch(fixture, scenario, marker, target=target)
            wait_returns(cloud, fixture, [item])
            if selected(cloud.events_for(item), "PLACEMENT_MISS"):
                raise PreconditionError("Warm-environment fixture replaced; cannot claim no accumulation")
            cloud.finish(item, expected="FAILED" if scenario == "failure" else "SUCCEEDED")
            trace = cloud.events_for(item)
            if target is None:
                target = trace[0]["environment"]
            snapshots.extend(e for e in selected(trace, "SNAPSHOT") if e["environment"] == target)
            if scenario == "replay":
                assert_replay(trace, cloud.history(item), marker)
            assert_lifecycle(trace)
    # Default cached pools retain idle threads: measure live invocation-owned work/queue instead.
    require(snapshots, "Missing warm task snapshots")
    require(all(e["liveTasks"] == 0 and e["liveRoots"] == 0 and e["queued"] == 0 for e in snapshots),
            "Invocation-owned tasks, roots or queued work accumulated across warm batches")
    require(snapshots[-1]["threads"] <= snapshots[0]["threads"] + 16,
            "Thread growth exceeded bounded cache tolerance (+16)")


def run_tests():
    manifest = json.loads(MANIFEST.read_text())
    cloud = Cloud(manifest, ARTIFACTS)
    suite = ET.Element("testsuite", name="LMI cloud lifecycle")
    cases = [("baseline-concurrency1", lambda: replay_case(cloud, "default1", "baseline"))]
    for fixture in ["default2", "default8"]:
        cases += [(fixture + "-isolation", lambda f=fixture: overlap_case(cloud, f)),
                  (fixture + "-suspend-cleanup-replay", lambda f=fixture: replay_case(cloud, f, "suspend")),
                  (fixture + "-timeout-recovery", lambda f=fixture: timeout_case(cloud, f))]
    cases += [("fixed-two-roots", lambda: fixed_case(cloud, "fixed2", "fixed")),
              ("fixed-nested-map-parallel", lambda: fixed_case(cloud, "nested2", "nested")),
              ("non-cooperative-child", lambda: timeout_case(cloud, "default2", True)),
              ("return-with-inflight-step", lambda: inflight_case(cloud, "default2", "return-inflight")),
              ("failure-with-inflight-step", lambda: inflight_case(cloud, "default2", "failure-inflight")),
              ("warm-repeated-batches", lambda: warm_case(cloud, "default2"))]
    try:
        for name, case in cases:
            started = time.monotonic()
            node = ET.SubElement(suite, "testcase", classname="LmiCloudLifecycle", name=name)
            try:
                case()
                print("PASS", name, flush=True)
            except Exception as error:
                kind = "failure" if isinstance(error, AssertionError) else "error"
                ET.SubElement(node, kind, type=type(error).__name__, message=str(error)).text = traceback.format_exc()
                print(kind.upper(), name, str(error), flush=True)
            finally:
                try:
                    cloud.release_all()
                    for fixture in manifest["functions"]:
                        cloud.refresh(fixture)
                except Exception as error:
                    ET.SubElement(node, "error", type="CollectionError", message=str(error))
                node.set("time", str(round(time.monotonic() - started, 3)))
                write_junit(suite)
    finally:
        cloud.close()
        write_junit(suite)
    require(not suite.findall(".//failure") and not suite.findall(".//error"), "LMI cloud cases failed; see JUnit and artifacts")


def write_junit(suite):
    suite.set("tests", str(len(suite.findall("testcase"))))
    suite.set("failures", str(len(suite.findall(".//failure"))))
    suite.set("errors", str(len(suite.findall(".//error"))))
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(ARTIFACTS / "junit.xml", encoding="unicode", xml_declaration=True)


def collect():
    manifest = json.loads(MANIFEST.read_text())
    cloud = Cloud(manifest, ARTIFACTS)
    errors = []
    for fixture in manifest["functions"]:
        try:
            cloud.refresh(fixture)
        except Exception as error:
            errors.append(str(error))
    executions = {e["executionArn"]: e["marker"] for e in cloud.events.values()}
    for arn, marker in executions.items():
        try:
            cloud.history({"arn": arn, "marker": marker})
            save(ARTIFACTS / "executions" / (marker + ".json"), aws("lambda", "get-durable-execution", {"DurableExecutionArn": arn}))
        except Exception as error:
            errors.append(str(error))
    cloud.pool.shutdown()
    save(ARTIFACTS / "collection-errors.json", errors)
    require(not errors, "Evidence collection failed: " + "; ".join(errors))


def delete_owned(stack, bucket):
    failures = []
    if stack:
        try:
            aws("cloudformation", "delete-stack", {"StackName": stack})
            deadline = time.monotonic() + 480
            while time.monotonic() < deadline:
                try:
                    result = aws("cloudformation", "describe-stacks", {"StackName": stack})
                except RuntimeError as error:
                    if "does not exist" in str(error):
                        break
                    raise
                require(result["Stacks"][0]["StackStatus"] != "DELETE_FAILED", "Stack deletion failed")
                time.sleep(5)
            else:
                raise RuntimeError("Teardown stack deadline exceeded")
        except Exception as error:
            failures.append(str(error))
    if bucket:
        try:
            aws("s3", "rm", extra=["s3://" + bucket, "--recursive"], raw=True, timeout=60)
            aws("s3api", "delete-bucket", {"Bucket": bucket})
        except Exception as error:
            if "NoSuchBucket" not in str(error):
                failures.append(str(error))
    require(not failures, "; ".join(failures))


def cleanup():
    if not MANIFEST.exists():
        return
    manifest = json.loads(MANIFEST.read_text())
    delete_owned(manifest["stack"], manifest["bucket"])


def janitor():
    cutoff = time.time() - 6 * 3600
    stacks = aws("cloudformation", "describe-stacks").get("Stacks", [])
    for stack in stacks:
        tags = {t["Key"]: t["Value"] for t in stack.get("Tags", [])}
        if tags.get("Suite") == OWNER and int(tags.get("Created", "0")) < cutoff:
            delete_owned(stack["StackName"], None)
    for bucket in aws("s3api", "list-buckets").get("Buckets", []):
        name = bucket["Name"]
        if not name.startswith("java-lmi-e2e-"):
            continue
        try:
            tags = {t["Key"]: t["Value"] for t in aws("s3api", "get-bucket-tagging", {"Bucket": name})["TagSet"]}
        except RuntimeError as error:
            if "NoSuchTagSet" in str(error):
                continue
            raise
        if tags.get("Suite") == OWNER and int(tags.get("Created", "0")) < cutoff:
            delete_owned(None, name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["deploy", "test", "collect", "cleanup", "janitor"])
    parser.add_argument("--run-id")
    parser.add_argument("--cloud-enabled", action="store_true")
    parser.add_argument("--invocation-timeout", type=int, default=60, choices=range(45, 91))
    args = parser.parse_args()
    try:
        if args.command == "deploy":
            deploy(args.run_id, args.invocation_timeout)
        elif args.command == "test":
            if not args.cloud_enabled:
                parser.error("Real cloud tests require --cloud-enabled")
            run_tests()
        else:
            {"collect": collect, "cleanup": cleanup, "janitor": janitor}[args.command]()
    except Exception as error:
        save(ARTIFACTS / (args.command + "-error.json"), {"category": args.command, "error": str(error)})
        raise


if __name__ == "__main__":
    main()
