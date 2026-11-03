#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

from package_release_bundles import GROUP_ID, MODULES, package_bundle


class ReleaseBundlesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "bundles"
        self.output.mkdir()
        for module, artifact in MODULES.items():
            directory = self.root / module
            (directory / "target").mkdir(parents=True)
            (directory / ".flattened-pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<groupId>{GROUP_ID}</groupId><artifactId>{artifact}</artifactId>"
                "<version>3.0.1</version></project>\n")
            for classifier in ("", "-sources", "-javadoc"):
                with zipfile.ZipFile(directory / "target" / f"{artifact}-3.0.1{classifier}.jar", "w") as jar:
                    jar.writestr("fixture", (artifact + classifier).encode())

    def test_preserves_every_maven_artifact_and_records_checksums(self):
        for module, artifact in MODULES.items():
            with self.subTest(module=module):
                output = package_bundle(self.root, module, artifact, "3.0.1", self.output)
                with zipfile.ZipFile(output) as bundle:
                    manifest = json.loads(bundle.read("manifest.json"))
                    self.assertEqual((manifest["groupId"], manifest["artifactId"], manifest["version"]),
                                     (GROUP_ID, artifact, "3.0.1"))
                    self.assertEqual(len(bundle.namelist()), 5)
                    for filename, digest in manifest["sha256"].items():
                        original = (self.root / module / ".flattened-pom.xml" if filename.endswith(".pom")
                                    else self.root / module / "target" / filename)
                        self.assertEqual(bundle.read(filename), original.read_bytes())
                        self.assertEqual(hashlib.sha256(bundle.read(filename)).hexdigest(), digest)

    def test_bundle_is_reproducible(self):
        module, artifact = next(iter(MODULES.items()))
        with mock.patch("zipfile.time.localtime", return_value=(2001, 1, 1, 0, 0, 0, 0, 1, -1)):
            path = package_bundle(self.root, module, artifact, "3.0.1", self.output)
        first = path.read_bytes()
        with mock.patch("zipfile.time.localtime", return_value=(2031, 1, 1, 0, 0, 0, 0, 1, -1)):
            path = package_bundle(self.root, module, artifact, "3.0.1", self.output)
        self.assertEqual(first, path.read_bytes())

    def test_rejects_wrong_coordinate(self):
        module, artifact = next(iter(MODULES.items()))
        for field, expected in (("groupId", GROUP_ID), ("artifactId", artifact), ("version", "3.0.1")):
            with self.subTest(field=field):
                pom = self.root / module / ".flattened-pom.xml"
                original = pom.read_text()
                pom.write_text(original.replace(f"<{field}>{expected}</{field}>", f"<{field}>wrong</{field}>"))
                with self.assertRaises(ValueError):
                    package_bundle(self.root, module, artifact, "3.0.1", self.output)
                pom.write_text(original)

    def test_missing_artifact_fails_before_writing_bundle(self):
        module, artifact = next(iter(MODULES.items()))
        (self.root / module / "target" / f"{artifact}-3.0.1-sources.jar").unlink()
        with self.assertRaises(FileNotFoundError):
            package_bundle(self.root, module, artifact, "3.0.1", self.output)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_command_generates_all_four_bundles_and_rejects_invalid_version(self):
        script = Path(__file__).with_name("package_release_bundles.py")
        valid = subprocess.run([sys.executable, str(script), "3.0.1", str(self.output)],
                               cwd=self.root, capture_output=True, text=True)
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assertEqual(len(list(self.output.glob("*-bundle.zip"))), 4)
        invalid = subprocess.run([sys.executable, str(script), "../3.0.1", str(self.output)],
                                 cwd=self.root, capture_output=True, text=True)
        self.assertNotEqual(invalid.returncode, 0)
        self.assertIn("version must be a release version", invalid.stderr)


if __name__ == "__main__":
    unittest.main()
