# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Native Java OTel acceptance over independent runtime and raw-span evidence."""

import re

from cloud_support import CollectionError, require, selected


def header(value):
    if not isinstance(value, str):
        return None
    fields = dict(part.strip().split("=", 1) for part in value.split(";") if "=" in part)
    root, parent, sampling = (fields.get(key) for key in ("Root", "Parent", "Sampled"))
    if not root or not re.fullmatch(r"1-[0-9a-f]{8}-[0-9a-f]{24}", root):
        return None
    if not parent or not re.fullmatch(r"[0-9a-f]{16}", parent) or sampling not in {"0", "1"}:
        return None
    return {"trace": root[2:].replace("-", ""), "parent": parent, "sampled": sampling == "1"}


def validate_invocations(events, settings):
    entries = selected(events, "OTEL_ENTER")
    if not entries:
        raise CollectionError("Missing OTel runtime entry/carrier evidence")
    instances = []
    for entry in entries:
        local = [event for event in events
                 if event["requestId"] == entry["requestId"] and event["environment"] == entry["environment"]]
        ends = selected(local, "OTEL_END")
        factories = selected(local, "OTEL_FACTORY")
        if len(ends) != 1 or len(factories) != 1:
            raise CollectionError("Missing OTel factory or owner-End evidence")
        end, factory = ends[0], factories[0]
        require(end["contextRestored"] and end["mdcRestored"], "OTel/MDC scope leaked after End")
        require(end["ownerThread"] == end["endThread"], "End changed handler-owner thread")
        require(end["exporterShutdowns"] == 0, "Invocation shut down a shared exporter")
        require(factory["pluginExecution"] == entry["executionArn"], "Factory received another execution")
        require(entry["view"] == settings["view"], "Wrong OTel view was deployed")
        observed = header(entry.get("carrier"))
        if not observed:
            raise CollectionError("Runtime did not provide the required valid X-Ray header")
        require(factory["capturedHeader"] == entry["carrier"], "Runtime caller snapshot changed before dispatch")
        instances.append(factory["pluginInstance"])
        exports = selected(local, "OTEL_EXPORT")
        require(not selected(local, "OTEL_EXPORT_ERROR"), "Evidence exporter failed unexpectedly")
        if not observed["sampled"]:
            require(not exports, "Sampled=0 exported spans")
            continue
        roots = [event["span"] for event in exports if event["span"]["name"] == "Invocation"]
        if not roots:
            raise CollectionError("Sampled invocation span has not been collected")
        require(len(roots) == 1, "Duplicate Invocation spans")
        require(roots[0]["parentId"] == observed["parent"], "Runtime X-Ray Parent was lost")
        for event in exports:
            span = event["span"]
            expected = "failure" if settings["mode"] == "failure" else "success"
            require(event["outcome"] == expected, "Unexpected exporter outcome")
            require(span["attributes"].get("durable.execution.arn") == entry["executionArn"],
                    "Span belongs to another execution")
            require(span["traceId"] == observed["trace"] and span["sampled"], "Runtime trace/sampling was lost")
            require(span["startNanos"] <= span["endNanos"], "Negative span duration")
    require(len(instances) == len(set(instances)), "Plugin instance reused across invocations")
    initial = {event["requestId"] for event in selected(events, "OTEL_FACTORY")
               if event.get("firstInvocation")}
    candidates = [entry for entry in entries if entry["requestId"] in initial] or entries
    first = min(candidates, key=lambda event: event.get("epochMillis", event["sequence"]))
    sent, observed = header(settings["traceHeader"]), header(first["carrier"])
    require(sent and observed["trace"] == sent["trace"] and observed["sampled"] == sent["sampled"],
            "Instrumented Invoke did not preserve the caller's Root/Sampled")
    terminals = [event for event in selected(events, "OTEL_END") if event.get("status") in {"SUCCEEDED", "FAILED"}]
    if terminals:
        terminal = max(terminals, key=lambda event: event["sequence"])
        terminal_entry = next(event for event in entries if event["requestId"] == terminal["requestId"])
        if header(terminal_entry["carrier"])["sampled"]:
            workflows = [event["span"] for event in selected(events, "OTEL_EXPORT")
                         if event["span"]["name"] == "Workflow"]
            if not workflows:
                raise CollectionError("Terminal Workflow span has not been collected")
            require(len(workflows) == 1, "Workflow repeated across resume/retry")
            expected = "ERROR" if terminal["status"] == "FAILED" else "OK"
            require(workflows[0]["status"] == expected, "Workflow status changed")
