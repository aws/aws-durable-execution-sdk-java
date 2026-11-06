#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Exercise real OTel autoconfiguration and the plugin SPI with isolated sampler environment settings.

No downloads, listeners, or AWS calls. Supply built SDK/plugin artifacts, their test dependencies,
and an OTel autoconfigure JAR matching the plugin's OTel SDK version. This external acceptance
harness adds no Maven or runtime dependency to the plugin.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def cases():
    for policy in (
        "always_on", "always_off", "traceidratio", "parentbased_always_on",
        "parentbased_always_off", "parentbased_traceidratio",
    ):
        yield policy, "0.5", "auto", policy, "0.5"
    yield None, None, "auto", "parentbased_always_on", "1"
    for policy in ("traceidratio", "parentbased_traceidratio"):
        yield policy, None, "auto", policy, "1"
        yield policy, "0", "auto", policy, "0"
        yield policy, "1", "auto", policy, "1"
        for argument in ("not-a-number", "-0.1", "1.1"):
            yield policy, argument, "invalid", "always_on", "1"
    yield "unknown-sampler", None, "invalid", "always_on", "1"
    # The provider ignores the ratio argument for policies that do not consume it.
    yield "always_on", "not-a-number", "auto", "always_on", "1"
    # An explicit provider builder never reads the autoconfiguration environment.
    yield "always_off", "0", "builder", "always_on", "1"
    yield "always_on", "1", "builder", "always_off", "1"
    yield "unknown-sampler", "not-a-number", "builder", "parentbased_always_on", "1"
    # Another auto-configuration customizer can replace the environment-resolved sampler.
    yield "always_on", None, "customizer", "always_off", "1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("core", "plugin", "dependencies-classpath", "autoconfigure", "java-home"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    deps = [entry for entry in args.dependencies_classpath.read_text().strip().split(os.pathsep)
            if not Path(entry).name.startswith("aws-durable-execution-sdk-java")]
    classpath = os.pathsep.join([str(args.core.resolve()), str(args.plugin.resolve()),
                                str(args.autoconfigure.resolve()), *deps])
    # Keep unrelated machine settings from changing the configuration under test.
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith("OTEL_") and key not in
                   ("_X_AMZN_TRACE_ID", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    environment.update(OTEL_TRACES_EXPORTER="none", OTEL_METRICS_EXPORTER="none",
                       OTEL_LOGS_EXPORTER="none")
    source = Path(__file__).with_name("ConfiguredSamplerProbe.java")
    with tempfile.TemporaryDirectory(prefix="sampler-config-") as output:
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "17", "-cp", classpath,
                        "-d", output, str(source)], check=True, env=environment)
        failures = []
        total = 0
        for policy, argument, mode, expected, ratio in cases():
            total += 1
            case_env = environment.copy()
            if policy is not None:
                case_env["OTEL_TRACES_SAMPLER"] = policy
            if argument is not None:
                case_env["OTEL_TRACES_SAMPLER_ARG"] = argument
            result = subprocess.run(
                [str(args.java_home / "bin/java"), "-cp", os.pathsep.join([output, classpath]),
                 "software.amazon.lambda.durable.otel.ConfiguredSamplerProbe", mode, expected, ratio],
                capture_output=True, text=True, env=case_env, timeout=60)
            label = f"sampler={policy!r} arg={argument!r} mode={mode}"
            if result.returncode:
                failures.append(label)
                print(f"FAIL {label}\n{result.stdout}{result.stderr}", flush=True)
            else:
                summary = " ".join(line for line in result.stdout.splitlines() if line.startswith("PASS "))
                print(f"{label}: {summary}", flush=True)
        print(f"{total - len(failures)}/{total} configuration processes passed")
        if failures:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
