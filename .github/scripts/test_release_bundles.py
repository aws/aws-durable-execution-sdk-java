#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import contextlib
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile

from package_release_bundles import (GROUP_ID, MODULES, NAMESPACE, RELEASE_VERSION, ROOT,
                                     main, package_bundle, publication_names)


VERSION = "3.0.1"
SCRIPT = Path(__file__).with_name("package_release_bundles.py")


class ReleaseBundlesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "bundles"
        self.output.mkdir()
        for module, artifact in MODULES.items():
            target = self.root / module / "target"
            target.mkdir(parents=True)
            (target / f"{artifact}-{VERSION}.pom").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<groupId>{GROUP_ID}</groupId><artifactId>{artifact}</artifactId>"
                f"<version>{VERSION}</version></project>\n")
            for classifier in ("", "-sources", "-javadoc"):
                with zipfile.ZipFile(target / f"{artifact}-{VERSION}{classifier}.jar", "w") as jar:
                    jar.writestr("fixture", (artifact + classifier).encode())
            # The publishing profile signs every file above, so each has a detached signature.
            for published in sorted(target.iterdir()):
                published.with_name(published.name + ".asc").write_text(
                    f"-----BEGIN PGP SIGNATURE-----\n{published.name}\n-----END PGP SIGNATURE-----\n")

    def test_preserves_every_signed_maven_artifact_and_records_checksums(self):
        for module, artifact in MODULES.items():
            with self.subTest(module=module):
                output = package_bundle(self.root, module, artifact, VERSION, self.output)
                published = publication_names(artifact, VERSION)
                with zipfile.ZipFile(output) as bundle:
                    manifest = json.loads(bundle.read("manifest.json"))
                    self.assertEqual((manifest["groupId"], manifest["artifactId"], manifest["version"]),
                                     (GROUP_ID, artifact, VERSION))
                    self.assertEqual(bundle.namelist(), sorted(published + ["manifest.json"]))
                    self.assertEqual(sorted(manifest["sha256"]), sorted(published))
                    for filename, digest in manifest["sha256"].items():
                        original = (self.root / module / "target" / filename).read_bytes()
                        self.assertEqual(bundle.read(filename), original)
                        self.assertEqual(hashlib.sha256(original).hexdigest(), digest)

    def test_normalizes_every_zip_entry(self):
        module, artifact = next(iter(MODULES.items()))
        path = package_bundle(self.root, module, artifact, VERSION, self.output)
        with zipfile.ZipFile(path) as bundle:
            for entry in bundle.infolist():
                with self.subTest(entry=entry.filename):
                    self.assertEqual(entry.date_time, (1980, 1, 1, 0, 0, 0))
                    self.assertEqual(entry.create_system, 3)
                    self.assertEqual(entry.compress_type, zipfile.ZIP_DEFLATED)
                    self.assertEqual(entry.external_attr >> 16, 0o100644)

    def test_bundle_is_reproducible(self):
        module, artifact = next(iter(MODULES.items()))
        with mock.patch("zipfile.time.localtime", return_value=(2001, 1, 1, 0, 0, 0, 0, 1, -1)):
            path = package_bundle(self.root, module, artifact, VERSION, self.output)
        first = path.read_bytes()
        with mock.patch("zipfile.time.localtime", return_value=(2031, 1, 1, 0, 0, 0, 0, 1, -1)):
            path = package_bundle(self.root, module, artifact, VERSION, self.output)
        self.assertEqual(first, path.read_bytes())

    def test_rejects_wrong_coordinate(self):
        module, artifact = next(iter(MODULES.items()))
        pom = self.root / module / "target" / f"{artifact}-{VERSION}.pom"
        original = pom.read_text()
        for field, expected in (("groupId", GROUP_ID), ("artifactId", artifact), ("version", VERSION)):
            with self.subTest(field=field):
                pom.write_text(original.replace(f"<{field}>{expected}</{field}>",
                                                f"<{field}>wrong</{field}>"))
                try:
                    with self.assertRaises(ValueError):
                        package_bundle(self.root, module, artifact, VERSION, self.output)
                finally:
                    pom.write_text(original)

    def test_any_missing_file_fails_before_writing_bundle(self):
        module, artifact = next(iter(MODULES.items()))
        target = self.root / module / "target"
        for name in publication_names(artifact, VERSION):
            with self.subTest(missing=name):
                missing = target / name
                data = missing.read_bytes()
                missing.unlink()
                try:
                    with self.assertRaises(FileNotFoundError):
                        package_bundle(self.root, module, artifact, VERSION, self.output)
                    self.assertEqual(list(self.output.iterdir()), [])
                finally:
                    missing.write_bytes(data)

    def test_modules_match_the_modules_maven_publishes(self):
        published = re.findall(r"-pl (\S+)", (ROOT / ".github/scripts/maven_publish.sh").read_text())
        self.assertEqual(sorted(MODULES), sorted(published))
        for module, artifact in MODULES.items():
            with self.subTest(module=module):
                pom = ET.parse(ROOT / module / "pom.xml")
                self.assertEqual(pom.findtext("m:artifactId", namespaces=NAMESPACE), artifact)
                self.assertEqual(pom.findtext("m:parent/m:groupId", namespaces=NAMESPACE), GROUP_ID)

    def test_release_version_accepts_releases_and_prereleases_only(self):
        for version in ("3.0.1", "3.0.1-rc1", "10.20.30-beta.2"):
            self.assertTrue(RELEASE_VERSION.fullmatch(version), version)
        for version in ("3.0.2-SNAPSHOT", "../3.0.1", "3.0", "3.0.1-", "3.0.1 "):
            self.assertFalse(RELEASE_VERSION.fullmatch(version), version)

    def test_command_generates_every_bundle_and_clears_stale_output(self):
        stale = self.output / f"{next(iter(MODULES.values()))}-2.0.0-bundle.zip"
        stale.write_bytes(b"left over from an earlier version")
        with mock.patch.object(sys, "argv", [SCRIPT.name, VERSION, str(self.output)]), \
                contextlib.redirect_stdout(io.StringIO()):
            main(self.root)
        self.assertEqual(sorted(path.name for path in self.output.iterdir()),
                         sorted(f"{artifact}-{VERSION}-bundle.zip" for artifact in MODULES.values()))

    def test_command_rejects_a_version_that_is_not_a_release(self):
        for version in ("../3.0.1", "3.0.2-SNAPSHOT"):
            with self.subTest(version=version):
                result = subprocess.run([sys.executable, str(SCRIPT), version, str(self.output)],
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("version must be a release version", result.stderr)
        self.assertEqual(list(self.output.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
