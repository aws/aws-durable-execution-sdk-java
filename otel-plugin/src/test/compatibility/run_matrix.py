#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Run 2.x-only installed core/plugin compatibility with an actual separate layer loader and a legacy-compiled caller.

No downloads or AWS calls. Supply released core/plugin jars, built current artifacts/directories, and an existing
classpath file containing their common dependencies. No SDK production dependencies are added for this check.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
for name in ("old-core", "old-plugin", "new-core", "new-plugin", "dependencies-classpath"):
    parser.add_argument("--" + name, required=True, type=Path)
parser.add_argument("--java-home", required=True, type=Path)
args = parser.parse_args()
def artifact_version(path):
    if path.is_dir():
        version_file = path / "version.prop"
        if version_file.exists():
            properties = version_file.read_text()
        else:
            pom = path.parent.parent / "pom.xml"
            if not pom.exists():
                parser.error(f"Cannot verify SDK version for {path}; supply a Maven artifact")
            root = ET.parse(pom).getroot()
            ns = {"m": "http://maven.apache.org/POM/4.0.0"}
            version = root.find("m:version", ns)
            if version is None:
                version = root.find("m:parent/m:version", ns)
            return version.text if version is not None else None
    else:
        with zipfile.ZipFile(path) as jar:
            names = [name for name in jar.namelist()
                     if name.startswith("META-INF/maven/software.amazon.lambda.durable/")
                     and name.endswith("/pom.properties")]
            if not names:
                parser.error(f"Cannot verify SDK version for {path}")
            properties = jar.read(names[0]).decode()
    for line in properties.splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    return None

for artifact in (args.old_core, args.old_plugin, args.new_core, args.new_plugin):
    version = artifact_version(artifact)
    if not version or not version.startswith("2."):
        parser.error(f"This legacy ABI harness accepts only 2.x artifacts; {artifact} has version {version!r}. "
                     "Use the factory/provider migration tests for 3.x.")

known = {p.resolve() for p in (args.old_core, args.old_plugin, args.new_core, args.new_plugin)}
entries = args.dependencies_classpath.read_text().strip().split(os.pathsep)
deps = [p for p in entries if Path(p).resolve() not in known
        and not Path(p).name.startswith("aws-durable-execution-sdk-java")]
source = Path(__file__).with_name("PluginLayerCompatibilityProbe.java")
target = Path.cwd() / "target"
target.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="layer-compat-", dir=target) as output:
    subprocess.run([str(args.java_home / "bin/javac"), "--release", "17", "-cp",
                    os.pathsep.join([str(args.old_core), *deps]), "-d", output, str(source)], check=True)
    for label, core, plugin in [("old/old", args.old_core, args.old_plugin),
                                 ("old/new", args.old_core, args.new_plugin),
                                 ("new/old", args.new_core, args.old_plugin),
                                 ("new/new", args.new_core, args.new_plugin)]:
        for view in ("otel-invocation", "otel-execution"):
            result = subprocess.run([str(args.java_home / "bin/java"), "-cp",
                os.pathsep.join([output, str(core), *deps]), "PluginLayerCompatibilityProbe", str(plugin), view],
                capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(f"{label} {view} failed:\n{result.stdout}{result.stderr}")
            print(label, result.stdout.strip())
