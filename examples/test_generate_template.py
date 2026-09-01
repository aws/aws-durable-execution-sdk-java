#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


GENERATOR_PATH = Path(__file__).with_name("generate-template.py")
SPEC = importlib.util.spec_from_file_location("generate_template", GENERATOR_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {GENERATOR_PATH}")
generate_template = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = generate_template
SPEC.loader.exec_module(generate_template)


class GenerateTemplateTest(unittest.TestCase):
    def test_default_template_does_not_include_file_system_infrastructure(self) -> None:
        examples = [example for example in generate_template.discover_examples() if not example.file_system]

        template = generate_template.render_template(examples)

        self.assertNotIn("FileSystemInfrastructureStackName", template)
        self.assertNotIn("AWS::EFS::FileSystem", template)

    def test_file_system_lambda_template_imports_persistent_infrastructure(self) -> None:
        examples = [example for example in generate_template.discover_examples() if example.file_system]

        template = generate_template.render_template(examples)

        self.assertIn("FileSystemInfrastructureStackName:", template)
        self.assertIn("${FileSystemInfrastructureStackName}-SubnetId", template)
        self.assertIn("${FileSystemInfrastructureStackName}-LambdaSecurityGroupId", template)
        self.assertIn("${FileSystemInfrastructureStackName}-AccessPointArn", template)
        self.assertIn("FILESYSTEM_SERDES_PATH: /mnt/efs", template)
        self.assertNotIn("AWS::EFS::FileSystem", template)
        self.assertNotIn("FileSystemMountTarget", template)

    def test_file_system_infrastructure_template_exports_shared_resources(self) -> None:
        template = generate_template.render_file_system_infrastructure_template()

        self.assertIn("AWS::EFS::FileSystem", template)
        self.assertIn("AWS::EFS::MountTarget", template)
        self.assertIn("${AWS::StackName}-SubnetId", template)
        self.assertIn("${AWS::StackName}-LambdaSecurityGroupId", template)
        self.assertIn("${AWS::StackName}-AccessPointArn", template)
        self.assertNotIn("AWS::Serverless::Function", template)


if __name__ == "__main__":
    unittest.main()
