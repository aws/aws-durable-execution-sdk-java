#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Bundle the exact signed Maven publication outputs for archive-based consumers.

Run after maven_publish.sh, which deploys with the publishing profile and so leaves
every file submitted to Sonatype in each module's target directory: the flattened
POM, the three JARs, and a detached GPG signature for each of them.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
GROUP_ID = "software.amazon.lambda.durable"
MODULES = {
    "sdk": "aws-durable-execution-sdk-java",
    "sdk-testing": "aws-durable-execution-sdk-java-testing",
    "otel-plugin": "aws-durable-execution-sdk-java-plugin-otel",
    "telemetry-exporter": "aws-durable-execution-sdk-java-plugin-telemetry-exporter",
}
SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
# A published release version: semantic, optionally a prerelease, never a snapshot.
RELEASE_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-(?!SNAPSHOT\Z)[0-9A-Za-z][0-9A-Za-z.-]*)?")


def publication_names(artifact, version):
    """Return every file Maven publishes for a module, then their detached signatures."""
    names = [f"{artifact}-{version}{suffix}" for suffix in SUFFIXES]
    return names + [f"{name}.asc" for name in names]


def package_bundle(root, module, artifact, version, output):
    target = root / module / "target"
    files = {name: (target / name).read_bytes()
             for name in publication_names(artifact, version)}

    project = ET.fromstring(files[f"{artifact}-{version}.pom"])
    coordinates = tuple(project.findtext("m:" + key, namespaces=NAMESPACE)
                        for key in ("groupId", "artifactId", "version"))
    if coordinates != (GROUP_ID, artifact, version):
        raise ValueError(f"Unexpected Maven coordinates in {module}: {coordinates}")

    manifest = {
        "groupId": GROUP_ID,
        "artifactId": artifact,
        "version": version,
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
    }
    files["manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()

    destination = output / f"{artifact}-{version}-bundle.zip"
    with zipfile.ZipFile(destination, "w") as bundle:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, data)
    return destination


def main(root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not RELEASE_VERSION.fullmatch(args.version):
        parser.error("version must be a release version such as 3.0.1")
    args.output.mkdir(parents=True, exist_ok=True)
    for stale in args.output.glob("*-bundle.zip"):
        stale.unlink()
    for module, artifact in MODULES.items():
        print(package_bundle(root, module, artifact, args.version, args.output))


if __name__ == "__main__":
    main()
