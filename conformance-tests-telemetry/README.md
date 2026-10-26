# Durable Execution Java SDK — Durable Execution Telemetry Conformance Tests

This module answers one question. Does the telemetry exporter (`aws-durable-execution-sdk-java-plugin-telemetry-exporter`),
with its `CloudWatchLogsExporter`, deliver a complete `DurableExecutionTelemetry` record to CloudWatch Logs for a real
Lambda execution?

Unit tests can assert that the plugin builds a record. They cannot assert that the record reaches the configured log
group on a real invocation or that it carries the right identity fields for the deployed function. Those are properties
of a deployed function. So the scenario here is a Lambda function. The runner deploys it with the plugin configured,
invokes it once, fetches the record from CloudWatch Logs, and checks it against the requirement.

The requirement and the runner live in
[`aws/aws-durable-execution-conformance-tests`](https://github.com/aws/aws-durable-execution-conformance-tests), package
`aws-durable-execution-conformance-tests-telemetry`. This module holds the SDK-specific part: the handler and the SAM
template that maps it to the requirement ID.

## Coverage

| Suite       | Requirement   | What it pins down                                                                                                                              |
| ----------- | ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `telemetry` | `telemetry-1` | One successful single-step execution emits one `SUCCEEDED` record with `input`, `output`, ARN-derived identity fields, and `operationsByName`. |

## Layout

```
pom.xml                                   # standalone Maven project (not a reactor module)
template.yaml                             # telemetry suite; declares TelemetryLogGroupName and creates the group
src/main/java/software/amazon/lambda/durable/conformance/telemetry/
  Telemetry1Success.java                  # single step "increment"; plugin + CloudWatchLogsExporter
```

The handler uses the real SDK API only. It constructs no record and calls no exporter method. A missing or wrong record
is the plugin's defect. That defect is the signal the suite measures.

The handler configuration is: one `CloudWatchLogsExporter`, emit mode `ON_COMPLETE`, sampling rate 1, operation detail
`TOP_LEVEL`, and content input and output enabled.

## Template contract

The runner passes `TelemetryLogGroupName`. Extensions have no pre-deploy hook, so the template creates the group with an
`AWS::Logs::LogGroup` resource, and the function depends on it. The group name reaches the handler through
`TELEMETRY_LOG_GROUP_NAME`. The handler fails at class initialization when that variable is unset.

The `LambdaExecutionRoleArn` role must allow `logs:CreateLogStream` and `logs:PutLogEvents` on the group.
`AWSLambdaBasicDurableExecutionRolePolicy` grants both on `*`.

## Building locally

Prerequisites: JDK 17 or later, Maven, and the AWS SAM CLI.

```bash
# from the repository root: install the SDK and the telemetry exporter to the local Maven repository
mvn -B -q -Dmaven.test.skip=true install

# tell this module which SDK version to build against (the root pom version)
export JAVA_SDK_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)

cd conformance-tests-telemetry
sam build
```

The pom reads the SDK version from `JAVA_SDK_VERSION` through the `durable.sdk.version` property. The exporter declares
`cloudwatchlogs` as optional, so this module declares it directly.

Cloud run, from a checkout of the conformance repository with the telemetry package installed:

```bash
durable-execution-conformance \
  --template <this-repo>/conformance-tests-telemetry/template.yaml \
  --language java --region us-west-2 --suite telemetry \
  --report console json \
  --parameter-overrides LambdaExecutionRoleArn=<role-arn>
```

## CI

[`.github/workflows/telemetry-conformance-tests.yml:1–118`](../.github/workflows/telemetry-conformance-tests.yml#L1-L118)
runs on pull requests and pushes to `main` that touch the SDK, telemetry
exporter, this module, or the root `pom.xml`. It also supports manual dispatch.

It calls the conformance repository's reusable telemetry workflow at a pinned
SHA. `sdk_ref` selects the PR head commit, and `examples_dir` points to this
module. CI installs the SDK and exporter, compiles the handlers, then deploys
the template and validates the `telemetry` suite. Reports and execution
histories are uploaded as artifacts.

CI assumes AWS credentials through OIDC using the repository's existing test
secrets. Fork and Dependabot pull requests skip the cloud job.
