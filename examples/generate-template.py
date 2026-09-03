#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


EXAMPLES_DIR = Path(__file__).resolve().parent
SOURCE_ROOT = EXAMPLES_DIR / "src/main/java"
EXAMPLE_PACKAGE_ROOT = SOURCE_ROOT / "software/amazon/lambda/durable/examples"
DEFAULT_OUTPUT = EXAMPLES_DIR / "template.yaml"
TEMPLATE_ANNOTATION = "ExampleTemplate"


@dataclass(frozen=True)
class ExampleFunction:
    class_name: str
    package_name: str
    suffix: str
    condition: str | None
    file_system: bool

    @property
    def logical_id(self) -> str:
        return f"{self.class_name}Function"

    @property
    def log_group_logical_id(self) -> str:
        return f"{self.logical_id}LogGroup"

    @property
    def handler(self) -> str:
        return f"{self.package_name}.{self.class_name}"

    @property
    def description(self) -> str:
        words = re.sub(r"(?<!^)(?=[A-Z])", " ", self.class_name)
        return f"{words} Function ARN"


def kebab_case(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "-", name).lower()


def read_package(source: str, path: Path) -> str:
    match = re.search(r"^package\s+([\w.]+);", source, flags=re.MULTILINE)
    if not match:
        raise ValueError(f"Missing package declaration: {path}")
    return match.group(1)


def is_top_level_durable_handler(source: str, class_name: str) -> bool:
    match = re.search(rf"public\s+(?:final\s+)?class\s+{class_name}\b(?P<header>[^\{{]*)\{{", source, re.DOTALL)
    return bool(match and "extends DurableHandler" in match.group("header"))


def read_template_metadata(source: str, class_name: str) -> tuple[str | None, bool]:
    class_match = re.search(rf"public\s+(?:final\s+)?class\s+{class_name}\b", source)
    if not class_match:
        return None, False

    prefix = source[: class_match.start()]
    matches = list(
        re.finditer(rf"@(?:[A-Za-z_][\w.]*\.)?{TEMPLATE_ANNOTATION}\s*(?:\((?P<body>.*?)\))?", prefix, re.DOTALL)
    )
    if not matches:
        return None, False

    body = matches[-1].group("body") or ""
    condition_match = re.search(r'condition\s*=\s*"([^"]+)"', body)
    condition = condition_match.group(1) if condition_match else None
    file_system = bool(re.search(r"\bfileSystem\s*=\s*true\b", body))
    return condition, file_system


def discover_examples() -> list[ExampleFunction]:
    examples = []
    for path in sorted(EXAMPLE_PACKAGE_ROOT.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        class_name = path.stem
        if not is_top_level_durable_handler(source, class_name):
            continue

        condition, file_system = read_template_metadata(source, class_name)
        package_name = read_package(source, path)
        examples.append(
            ExampleFunction(
                class_name=class_name,
                package_name=package_name,
                suffix=kebab_case(class_name),
                condition=condition,
                file_system=file_system,
            )
        )
    return examples


def emit_function(lines: list[str], example: ExampleFunction) -> None:
    lines.extend(
        [
            f"  {example.logical_id}:",
            "    Type: AWS::Serverless::Function",
        ]
    )
    if example.condition:
        lines.append(f"    Condition: {example.condition}")
    if example.file_system:
        lines.extend(["    DependsOn:", f"      - {example.log_group_logical_id}"])
    else:
        lines.append(f"    DependsOn: {example.log_group_logical_id}")
    lines.extend(
        [
            "    Properties:",
            f'      FunctionName: !Sub "${{FunctionNamePrefix}}{example.suffix}"',
            f'      Handler: "{example.handler}"',
            "      Role: !Ref RoleArn",
        ]
    )
    if example.file_system:
        lines.extend(
            [
                "      VpcConfig:",
                "        SecurityGroupIds:",
                "          - Fn::ImportValue:",
                '              Fn::Sub: "${FileSystemInfrastructureStackName}-LambdaSecurityGroupId"',
                "        SubnetIds:",
                "          - Fn::ImportValue:",
                '              Fn::Sub: "${FileSystemInfrastructureStackName}-SubnetId"',
                "      FileSystemConfigs:",
                "        - Arn:",
                "            Fn::ImportValue:",
                '              Fn::Sub: "${FileSystemInfrastructureStackName}-AccessPointArn"',
                "          LocalMountPath: /mnt/efs",
                "      Environment:",
                "        Variables:",
                "          FILESYSTEM_PAYLOAD_PATH: /mnt/efs",
            ]
        )
    lines.append("")


def emit_log_group(lines: list[str], example: ExampleFunction) -> None:
    lines.extend(
        [
            f"  {example.log_group_logical_id}:",
            "    Type: AWS::Logs::LogGroup",
        ]
    )
    if example.condition:
        lines.append(f"    Condition: {example.condition}")
    lines.extend(
        [
            "    Properties:",
            f'      LogGroupName: !Sub "/aws/lambda/${{FunctionNamePrefix}}{example.suffix}"',
            "      RetentionInDays: 7",
            "",
        ]
    )


def emit_file_system_resources(lines: list[str]) -> None:
    lines.extend(
        [
            "  FileSystemVpc:",
            "    Type: AWS::EC2::VPC",
            "    Properties:",
            "      CidrBlock: 10.0.0.0/24",
            "      EnableDnsHostnames: true",
            "      EnableDnsSupport: true",
            "",
            "  FileSystemSubnet:",
            "    Type: AWS::EC2::Subnet",
            "    Properties:",
            "      CidrBlock: 10.0.0.0/26",
            "      VpcId: !Ref FileSystemVpc",
            "",
            "  FileSystemLambdaSecurityGroup:",
            "    Type: AWS::EC2::SecurityGroup",
            "    Properties:",
            "      GroupDescription: Lambda access to EFS and the Lambda API endpoint",
            "      VpcId: !Ref FileSystemVpc",
            "",
            "  FileSystemMountSecurityGroup:",
            "    Type: AWS::EC2::SecurityGroup",
            "    Properties:",
            "      GroupDescription: EFS mount access from Lambda",
            "      VpcId: !Ref FileSystemVpc",
            "      SecurityGroupIngress:",
            "        - IpProtocol: tcp",
            "          FromPort: 2049",
            "          ToPort: 2049",
            "          SourceSecurityGroupId: !Ref FileSystemLambdaSecurityGroup",
            "",
            "  FileSystemEndpointSecurityGroup:",
            "    Type: AWS::EC2::SecurityGroup",
            "    Properties:",
            "      GroupDescription: Lambda API endpoint access from Lambda",
            "      VpcId: !Ref FileSystemVpc",
            "      SecurityGroupIngress:",
            "        - IpProtocol: tcp",
            "          FromPort: 443",
            "          ToPort: 443",
            "          SourceSecurityGroupId: !Ref FileSystemLambdaSecurityGroup",
            "",
            "  FileSystemLambdaEndpoint:",
            "    Type: AWS::EC2::VPCEndpoint",
            "    Properties:",
            "      PrivateDnsEnabled: true",
            "      SecurityGroupIds:",
            "        - !Ref FileSystemEndpointSecurityGroup",
            '      ServiceName: !Sub "com.amazonaws.${AWS::Region}.lambda"',
            "      SubnetIds:",
            "        - !Ref FileSystemSubnet",
            "      VpcEndpointType: Interface",
            "      VpcId: !Ref FileSystemVpc",
            "",
            "  FileSystem:",
            "    Type: AWS::EFS::FileSystem",
            "    Properties:",
            "      Encrypted: true",
            "      PerformanceMode: generalPurpose",
            "      ThroughputMode: bursting",
            "",
            "  FileSystemMountTarget:",
            "    Type: AWS::EFS::MountTarget",
            "    Properties:",
            "      FileSystemId: !Ref FileSystem",
            "      SecurityGroups:",
            "        - !Ref FileSystemMountSecurityGroup",
            "      SubnetId: !Ref FileSystemSubnet",
            "",
            "  FileSystemAccessPoint:",
            "    Type: AWS::EFS::AccessPoint",
            "    Properties:",
            "      FileSystemId: !Ref FileSystem",
            "      PosixUser:",
            '        Gid: "1000"',
            '        Uid: "1000"',
            "      RootDirectory:",
            "        CreationInfo:",
            '          OwnerGid: "1000"',
            '          OwnerUid: "1000"',
            '          Permissions: "0777"',
            "        Path: /durable-payloads",
            "",
        ]
    )


def emit_file_system_outputs(lines: list[str]) -> None:
    lines.extend(
        [
            "Outputs:",
            "  SubnetId:",
            "    Value: !Ref FileSystemSubnet",
            "    Export:",
            '      Name: !Sub "${AWS::StackName}-SubnetId"',
            "",
            "  LambdaSecurityGroupId:",
            "    Value: !Ref FileSystemLambdaSecurityGroup",
            "    Export:",
            '      Name: !Sub "${AWS::StackName}-LambdaSecurityGroupId"',
            "",
            "  AccessPointArn:",
            "    Value: !GetAtt FileSystemAccessPoint.Arn",
            "    Export:",
            '      Name: !Sub "${AWS::StackName}-AccessPointArn"',
        ]
    )


def render_file_system_infrastructure_template() -> str:
    lines = [
        "# This file is generated by examples/generate-template.py. Do not edit it by hand.",
        'AWSTemplateFormatVersion: "2010-09-09"',
        "Description: Persistent shared EFS infrastructure for filesystem payload offloader E2E tests",
        "",
        "Resources:",
    ]
    emit_file_system_resources(lines)
    emit_file_system_outputs(lines)
    return "\n".join(lines) + "\n"


def render_template(examples: list[ExampleFunction]) -> str:
    lines = [
        "# This file is generated by examples/generate-template.py. Do not edit it by hand.",
        'AWSTemplateFormatVersion: "2010-09-09"',
        "Transform: AWS::Serverless-2016-10-31",
        "Description: AWS Lambda Durable Execution SDK Examples",
        "",
        "Parameters:",
        "  Architecture:",
        "    Type: String",
        "    Default: arm64",
        "    Description: Lambda Function Architecture",
        "    AllowedValues:",
        "      - x86_64",
        "      - arm64",
        "  JavaVersion:",
        "    Type: String",
        "    Default: 'java17'",
        "    Description: Java runtime version",
        "  FunctionNamePrefix:",
        "    Type: String",
        "    Default: ''",
        "    Description: Optional prefix for Lambda function names",
        "  RoleArn:",
        "    Type: String",
        "    Description: IAM Role ARN for Lambda function execution",
    ]
    if any(example.file_system for example in examples):
        lines.extend(
            [
                "  FileSystemInfrastructureStackName:",
                "    Type: String",
                "    Description: Name of the shared persistent filesystem infrastructure stack",
            ]
        )
    lines.extend(
        [
        "",
        "Conditions:",
        "  IsJava21OrLater:",
        "    !Or",
        "      - !Equals [!Ref JavaVersion, 'java21']",
        "      - !Equals [!Ref JavaVersion, 'java25']",
        "",
        "Globals:",
        "  Function:",
        "    Timeout: 900",
        "    MemorySize: 512",
        "    Architectures:",
        "      - !Ref Architecture",
        "    DurableConfig:",
        "      ExecutionTimeout: 300",
        "      RetentionPeriodInDays: 7",
        "    Runtime: !Ref JavaVersion",
        "    Environment:",
        "      Variables:",
        "        FUNCTION_NAME_PREFIX: !Ref FunctionNamePrefix",
        "",
        "Resources:",
        ]
    )

    for example in examples:
        emit_log_group(lines, example)
        emit_function(lines, example)

    lines.append("Outputs:")
    for index, example in enumerate(examples):
        if index:
            lines.append("")
        lines.extend(
            [
                f"  {example.logical_id}:",
                f"    Description: {example.description}",
            ]
        )
        if example.condition:
            lines.append(f"    Condition: {example.condition}")
        lines.append(f"    Value: !GetAtt {example.logical_id}.Arn")

    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the examples SAM template from Java example handlers.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Path to write the generated template.")
    template_selection = parser.add_mutually_exclusive_group()
    template_selection.add_argument("--file-system-only", action="store_true")
    template_selection.add_argument("--file-system-infrastructure-only", action="store_true")
    args = parser.parse_args()

    if args.file_system_infrastructure_only:
        args.output.write_text(render_file_system_infrastructure_template(), encoding="utf-8")
        print(f"Generated persistent filesystem infrastructure template at {args.output}.")
        return

    examples = discover_examples()
    examples = [example for example in examples if example.file_system == args.file_system_only]
    if not examples:
        raise RuntimeError("No DurableHandler examples found")

    args.output.write_text(render_template(examples), encoding="utf-8")
    print(f"Generated {args.output} with {len(examples)} Lambda functions.")


if __name__ == "__main__":
    main()
