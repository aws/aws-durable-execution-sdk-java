# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Additional native-runtime observability cases; original lifecycle assertions remain."""

from contextlib import contextmanager
import uuid

from cloud_support import CollectionError, PreconditionError, require, selected
from otel_evidence import validate_invocations


@contextmanager
def observing(cloud, view, sampled="1", export="success"):
    previous = getattr(cloud, "observability", None)
    cloud.observability = {"view": view, "sampled": sampled, "export": export}
    start = len(cloud.invocations)
    try:
        yield
        for item in cloud.invocations[start:]:
            wait_evidence(cloud, item)
    finally:
        cloud.observability = previous


def wait_evidence(cloud, item):
    def complete(events):
        local = [event for event in events if event["marker"] == item["marker"]]
        try:
            validate_invocations(local, item["otel"])
        except CollectionError:
            return False
        return True
    cloud.poll(item["fixture"], complete, seconds=30, category=CollectionError, items=[item])


def single(cloud, fixture, scenario="success"):
    marker = "otel-" + uuid.uuid4().hex[:10]
    item = cloud.launch(fixture, scenario, marker)
    cloud.finish(item, "FAILED" if scenario == "failure" else "SUCCEEDED")
    wait_evidence(cloud, item)
    return item


def warm(cloud, fixture):
    environments, providers = set(), set()
    for scenario in ["success", "failure", "replay"] * 2:
        item = single(cloud, fixture, scenario)
        for event in selected(cloud.events_for(item), "OTEL_ENTER"):
            environments.add(event["environment"])
            providers.add(event["provider"])
        if scenario == "replay":
            item["replayHistory"] = cloud.history(item)
    if len(environments) != 1:
        raise PreconditionError("Warm observability cases did not reuse one JVM")
    require(len(providers) == 1, "Provider was rebuilt for every invocation")


def held_export(cloud, fixture):
    item = cloud.launch(fixture, "success", "otel-held-" + uuid.uuid4().hex[:10])
    def returned(events):
        local = [event for event in events if event["marker"] == item["marker"]]
        starts = selected(local, "OTEL_EXPORT_HELD")
        ends = selected(local, "WRAPPER_RETURN")
        if starts and ends:
            require(ends[0]["epochMillis"] - starts[0]["epochMillis"] <= 10000,
                    "The five-second flush wait did not bound finalization")
            require(not selected(local, "OTEL_EXPORT"), "Held exporter released before the assertion")
            return True
        return False
    cloud.poll(fixture, returned, seconds=40, category=CollectionError, items=[item])
    cloud.gate(item["exportGate"], release=True)
    cloud.finish(item)
    wait_evidence(cloud, item)


def additional_cases(cloud, lifecycle):
    cases = []
    for fixture in ("default1", "default2"):
        for view in ("invocation", "execution"):
            def add(label, work, sampled="1", export="success", fixture=fixture, view=view):
                def run():
                    with observing(cloud, view, sampled, export):
                        work()
                cases.append((fixture, f"otel-{fixture}-{view}-{label}", run))
            for sampled in ("0", "1"):
                add("sampling-" + sampled, lambda fixture=fixture: single(cloud, fixture), sampled)
            add("warm-replay", lambda fixture=fixture: warm(cloud, fixture))
            add("export-failure", lambda fixture=fixture: single(cloud, fixture), export="failure")
            add("flush-budget", lambda fixture=fixture: held_export(cloud, fixture), export="held")
            if fixture == "default2":
                add("overlap", lambda: lifecycle.overlap_case(cloud, "default2"))
                add("suspend-cleanup", lambda: lifecycle.replay_case(cloud, "default2", "suspend"))
                add("deadline", lambda: lifecycle.timeout_case(cloud, "default2"))
                add("non-cooperative", lambda: lifecycle.timeout_case(cloud, "default2", True))
                for scenario in ("return-inflight", "failure-inflight"):
                    add(scenario, lambda scenario=scenario: lifecycle.inflight_case(cloud, "default2", scenario))
    return cases
