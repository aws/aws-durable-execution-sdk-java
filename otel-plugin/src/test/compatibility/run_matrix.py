#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Check released/current core/plugin artifacts with a separate layer loader and legacy-compiled caller.

No downloads or AWS calls. The supplied new plugin is checked against the current lifecycle/root/header contract;
the released plugin retains its tracing/carrier contract. Capability is read from each loaded core, not its label.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile


def result_status(result, view, contract):
    if result.returncode:
        raise RuntimeError(f"probe failed:\n{result.stdout}{result.stderr}")
    lines = [line for line in result.stdout.splitlines() if line.startswith("COMPATIBILITY_RESULT ")]
    if len(lines) != 1:
        raise RuntimeError("missing or duplicate probe result")
    fields = dict(part.split("=", 1) for part in lines[0].split()[1:])
    if fields.get("view") != view or fields.get("contract") != contract or fields.get("lifecycle") not in ("true", "false"):
        raise RuntimeError("invalid probe result identity")
    expected = "EXPECTED_UNSUPPORTED" if contract == "current" and fields["lifecycle"] == "false" else "SUPPORTED"
    if fields.get("status") != expected:
        raise RuntimeError("unexpected capability outcome")
    return expected


def check_runner_controls():
    line = "COMPATIBILITY_RESULT status=SUPPORTED view=otel-invocation contract=current lifecycle=true"
    if result_status(subprocess.CompletedProcess([], 0, line, ""), "otel-invocation", "current") != "SUPPORTED":
        raise AssertionError("supported core control")
    for code, output in ((1, line), (0, ""), (0, line + "\n" + line), (0, line.replace("lifecycle=true", "lifecycle=false"))):
        try:
            result_status(subprocess.CompletedProcess([], code, output, "control"), "otel-invocation", "current")
        except RuntimeError:
            continue
        raise AssertionError("runner accepted an invalid probe result")
    print("PASS runner-controls nonzero missing duplicate unexpected-success", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("old-core", "old-plugin", "new-core", "new-plugin", "dependencies-classpath"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    check_runner_controls()
    known = {p.resolve() for p in (args.old_core, args.old_plugin, args.new_core, args.new_plugin)}
    entries = args.dependencies_classpath.read_text().strip().split(os.pathsep)
    deps = [p for p in entries if Path(p).resolve() not in known
            and not Path(p).name.startswith("aws-durable-execution-sdk-java")]
    source = Path(__file__).with_name("PluginLayerCompatibilityProbe.java")
    target = Path.cwd() / "target"
    target.mkdir(exist_ok=True)
    failures = []
    outcomes = []
    with tempfile.TemporaryDirectory(prefix="layer-compat-", dir=target) as output:
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "17", "-cp",
                        os.pathsep.join([str(args.old_core), *deps]), "-d", output, str(source)], check=True, timeout=60)
        caller_hash = hashlib.sha256((Path(output) / "PluginLayerCompatibilityProbe.class").read_bytes()).hexdigest()
        print(f"CALLER compiled_against={args.old_core.resolve()} sha256={caller_hash}", flush=True)
        subprocess.run([str(args.java_home / "bin/java"), "-cp", os.pathsep.join([output, str(args.old_core), *deps]),
                        "PluginLayerCompatibilityProbe", "--self-test"], check=True, timeout=30)
        for label, core, plugin, contract in [("old/old", args.old_core, args.old_plugin, "released"),
                                             ("old/new", args.old_core, args.new_plugin, "current"),
                                             ("new/old", args.new_core, args.old_plugin, "released"),
                                             ("new/new", args.new_core, args.new_plugin, "current")]:
            for view in ("otel-invocation", "otel-execution"):
                try:
                    result = subprocess.run([str(args.java_home / "bin/java"), "-cp",
                        os.pathsep.join([output, str(core), *deps]), "PluginLayerCompatibilityProbe",
                        str(core), str(plugin), view, contract], capture_output=True, text=True, timeout=30)
                    outcomes.append(result_status(result, view, contract))
                    print(label, result.stdout.strip(), flush=True)
                except (RuntimeError, subprocess.TimeoutExpired, ValueError) as failure:
                    failures.append(f"{label} {view}: {failure}")
        if failures:
            raise RuntimeError("Compatibility failures:\n" + "\n".join(failures))
    print(f"PASS {len(outcomes)} combinations: {outcomes.count('SUPPORTED')} supported, "
          f"{outcomes.count('EXPECTED_UNSUPPORTED')} expected capability rejections", flush=True)


if __name__ == "__main__":
    main()
