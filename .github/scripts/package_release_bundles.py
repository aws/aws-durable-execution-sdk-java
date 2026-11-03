#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Bundle the exact Maven publication outputs for archive-based consumers."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


GROUP_ID = "software.amazon.lambda.durable"
MODULES = {
    "sdk": "aws-durable-execution-sdk-java",
    "sdk-testing": "aws-durable-execution-sdk-java-testing",
    "otel-plugin": "aws-durable-execution-sdk-java-plugin-otel",
    "telemetry-exporter": "aws-durable-execution-sdk-java-plugin-telemetry-exporter",
}
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def package_bundle(root, module, artifact, version, output):
    pom = (root / module / ".flattened-pom.xml").read_bytes()
    project = ET.fromstring(pom)
    coordinates = tuple(project.findtext("m:" + key, namespaces=NAMESPACE)
                        for key in ("groupId", "artifactId", "version"))
    if coordinates != (GROUP_ID, artifact, version):
        raise ValueError(f"Unexpected Maven coordinates in {module}: {coordinates}")

    files = {f"{artifact}-{version}.pom": pom}
    for classifier in ("", "-sources", "-javadoc"):
        name = f"{artifact}-{version}{classifier}.jar"
        files[name] = (root / module / "target" / name).read_bytes()
    manifest = {
        "groupId": GROUP_ID,
        "artifactId": artifact,
        "version": version,
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
    }
    files["manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()

    destination = output / f"{artifact}-{version}-bundle.zip"
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, data)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?", args.version):
        parser.error("version must be a release version such as 3.0.1")
    args.output.mkdir(parents=True, exist_ok=True)
    for module, artifact in MODULES.items():
        print(package_bundle(Path.cwd(), module, artifact, args.version, args.output))


if __name__ == "__main__":
    main()
