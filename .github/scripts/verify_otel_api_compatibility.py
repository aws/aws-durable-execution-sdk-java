#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Exercise the current 3.x core, plugin, and testing artifacts with two visible OTel APIs.

Dependency resolution and every probe are required: a network, compilation, or case
failure returns nonzero. No production dependency versions are modified.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

API_VERSIONS = ("1.49.0", "1.66.0")
RELEASED_VERSION = "2.2.1"
CORE = "aws-durable-execution-sdk-java"
PLUGIN = "aws-durable-execution-sdk-java-plugin-otel"
PROBE = "software.amazon.lambda.durable.otel.InstalledApiProbe"


def execute(command: list[str], log: Path, *, env: dict[str, str] | None = None, timeout: int = 300) -> None:
    with log.open("w") as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, env=env,
                                    check=False, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f"Command timed out after {timeout}s; log={log}") from error
    if result.returncode:
        tail = "\n".join(log.read_text(errors="replace").splitlines()[-35:])
        raise RuntimeError(f"Command failed ({result.returncode}); log={log}\n{tail}")


def artifact(entries: list[Path], name: str, version: str) -> Path:
    matches = [p for p in entries if p.name == f"{name}-{version}.jar"]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one {name}:{version}, got {matches}")
    if not matches[0].is_file():
        raise RuntimeError(f"Resolved artifact is absent: {matches[0]}")
    return matches[0].resolve()


def jar_facts(path: Path) -> dict[str, str]:
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def snapshot_candidate(path: Path, output: Path) -> Path:
    expected = jar_facts(path)["sha256"]
    directory = output / "candidate-artifacts"
    directory.mkdir(exist_ok=True)
    target = directory / path.name
    if path.resolve() != target.resolve():
        shutil.copyfile(path, target)
    if jar_facts(target)["sha256"] != expected or jar_facts(path)["sha256"] != expected:
        raise RuntimeError(f"Candidate changed while being snapshotted: {path}")
    return target.resolve()


def candidate_jar(root: Path, module: str, name: str) -> Path:
    pom = ET.parse(root / "pom.xml")
    version = pom.findtext("{http://maven.apache.org/POM/4.0.0}version")
    if not version:
        raise RuntimeError("Cannot resolve the reactor version from pom.xml")
    path = root / module / "target" / f"{name}-{version}.jar"
    if not path.is_file():
        raise RuntimeError(f"Build the candidate first; artifact missing: {path}")
    return path.resolve()


def resolve_classpaths(fixture: Path, output: Path, maven: str) -> dict[str, list[Path]]:
    classpaths: dict[str, list[Path]] = {}
    for version in API_VERSIONS:
        target = output / f"dependencies-{version}.txt"
        execute([
            maven, "-B", "-f", str(fixture / "pom.xml"),
            "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath",
            f"-Dotel.api.version={version}", f"-Dmdep.outputFile={target}",
        ], output / f"resolve-{version}.log")
        classpaths[version] = [Path(p).resolve() for p in target.read_text().strip().split(os.pathsep)]
        artifact(classpaths[version], "opentelemetry-api", version)
        artifact(classpaths[version], "opentelemetry-context", version)
    return classpaths


def probe_environment(view: str) -> dict[str, str]:
    env = os.environ.copy()
    # The fixture sets its own plugin registration/global provider. Do not inherit
    # Lambda-hosted CI tracing or a developer's auto-agent/plugin configuration.
    for key in ("_X_AMZN_TRACE_ID", "DURABLE_EXECUTION_PLUGINS", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "OTEL_JAVAAGENT_EXTENSIONS", "AWS_LAMBDA_EXEC_WRAPPER"):
        env.pop(key, None)
    env["DURABLE_EXECUTION_PLUGINS"] = f"{view},compat-healthy"
    return env


def run_matrix(args: argparse.Namespace) -> int:
    root = args.root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    fixture = root / "otel-plugin/src/test/compatibility/b1"
    cp = resolve_classpaths(fixture, output, args.maven)
    testing_name = CORE + "-testing"
    inputs = {
        "core": args.new_core.resolve() if args.new_core else candidate_jar(root, "sdk", CORE),
        "plugin": args.new_plugin.resolve() if args.new_plugin else candidate_jar(root, "otel-plugin", PLUGIN),
        "testing": args.new_testing.resolve() if args.new_testing else candidate_jar(root, "sdk-testing", testing_name),
    }
    candidate_inputs = {name: jar_facts(path) for name, path in inputs.items()}
    selected = {name: snapshot_candidate(path, output) for name, path in inputs.items()}
    excluded = {f"{name}-{RELEASED_VERSION}.jar" for name in (CORE, PLUGIN, testing_name)}
    dependencies = {version: [path for path in paths if path.name not in excluded]
                    for version, paths in cp.items()}
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    compile_cp = [*selected.values(), *dependencies["1.66.0"]]
    execute([args.javac, "--release", "17", "-classpath", os.pathsep.join(map(str, compile_cp)),
             "-d", str(classes), str(fixture / "InstalledApiProbe.java")], output / "compile.log")
    services = classes / "META-INF/services"
    services.mkdir(parents=True, exist_ok=True)
    (services / "software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider").write_text(
        PROBE + "$HealthyProvider\n")
    report: dict[str, object] = {
        "contract": "Current 3.x factory API; cross-major core/plugin mixtures are unsupported.",
        "candidate_inputs": candidate_inputs,
        "candidate_snapshots": {name: jar_facts(path) for name, path in selected.items()},
        "cases": [], "agent_coverage": "This matrix is visible-API skew, not a deployed Java-agent test.",
    }
    cases: list[dict[str, object]] = report["cases"]  # type: ignore[assignment]
    failures = 0
    for version in API_VERSIONS:
        api = artifact(cp[version], "opentelemetry-api", version)
        context = artifact(cp[version], "opentelemetry-context", version)
        for view in ("otel-invocation", "otel-execution"):
            name = f"current3x-api{version}-{view}"
            case: dict[str, object] = {"name": name, "api": jar_facts(api), "context": jar_facts(context)}
            command = [args.java, "-cp", os.pathsep.join(map(str, [classes, *selected.values(), *dependencies[version]])),
                       PROBE, str(selected["core"]), str(selected["plugin"]), str(api), str(context), view,
                       str(version == "1.66.0").lower(), str(selected["testing"])]
            try:
                log = output / f"{name}.log"
                execute(command, log, env=probe_environment(view), timeout=90)
                if "COMPAT_PASS " not in log.read_text(errors="replace"):
                    raise RuntimeError("Probe did not report successful completion")
                case["passed"] = True
            except RuntimeError as error:
                failures += 1
                case.update(passed=False, error=str(error))
            cases.append(case)
            (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
            print(f"{'PASS' if case['passed'] else 'FAIL'} {name}", flush=True)
    report["passed"] = failures == 0
    report["failure_count"] = failures
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Current 3.x artifact matrix: {len(cases) - failures}/{len(cases)} passed; {output / 'results.json'}")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, default=Path("target/otel-api-compatibility"))
    parser.add_argument("--new-core", type=Path)
    parser.add_argument("--new-plugin", type=Path)
    parser.add_argument("--new-testing", type=Path)
    parser.add_argument("--maven", default=shutil.which("mvn") or "mvn")
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--javac", default=shutil.which("javac") or "javac")
    try:
        return run_matrix(parser.parse_args())
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Compatibility harness failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
