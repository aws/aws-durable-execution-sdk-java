# LMI lifecycle cloud tests

This suite tests the SDK commit being built on real Lambda Managed
Instances (LMI). It asserts the desired behavior in [#726](https://github.com/aws/aws-durable-execution-sdk-java/issues/726)
and implements the cloud coverage requested in [#727](https://github.com/aws/aws-durable-execution-sdk-java/issues/727).
The affected SDK is expected to fail. Do not invert assertions, skip regressions,
or accept a retry that happens to pass after a lifecycle violation.

## Test design

* Each fixture is a published durable function with LMI invocation concurrency
  1, 2, or 8. Java 25 / arm64 is the initial matrix. Java 17 is not supported by
  LMI. Deployment and readback are the region/architecture capability check:
  unsupported combinations fail setup; there is no ordinary-Lambda fallback.
* One CI job builds once, deploys all five fixture functions, runs all 13 cases,
  then collects evidence and deletes all test resources. One CloudFormation stack
  owns all five functions and log groups; all functions remain deployed throughout
  the test phase. Each uses 2 GiB / 1 vCPU and a single
  `$LATEST.PUBLISHED` version; code is not republished during the run, and the
  digest is verified against the built artifact.
* The template declares `FunctionScalingConfig` with both
  `MinExecutionEnvironments` and `MaxExecutionEnvironments` set to 1 on every
  function. CloudFormation applies those limits as part of resource creation,
  so setup does not first stabilize functions with the default three-environment
  floor and then lower it. The driver reads back the applied limits and ACTIVE
  version state before testing. The provider's 12-vCPU limit applies to EC2
  instance capacity, including placement and instance overhead; the suite never
  changes that limit. Invocation concurrency remains 1, 2, or 8 per environment,
  and same-JVM overlap must still be proven.
* A stream wrapper observes the actual SDK entry and return. Invocation-local
  root/task `finally` markers and a JVM-wide sequence establish ordering. The
  plugin end hook is deliberately not used as a completion signal.
* A bounded same-JVM root barrier establishes the fixed-pool reproduction.
  Other cases hold steps with private S3 control objects. The driver requires
  distinct request IDs active in the same JVM. Placement has its own deadline
  and failure category. Environment replacement is never worker recovery.
* Timeout victims start before healthy peers, leaving the peers time to hold
  the other runtime slots during recovery. Diagnostics capture the real context
  deadline; no fake clock, context, checkpoint backend, or time-skipping runner
  participates. Service timeout evidence, task interruption/exit, wrapper exit,
  and restored admission are independent assertions. Durable execution status
  is collected separately from the runtime invocation's outcome.
* Successful and failed checkpointed steps precede a real durable wait. The
  attempt ledger records entry into user bodies, while real service history
  proves checkpoint identity and replay. Fixture business work returns an idempotent marker; BODY events form the
  append-only attempt ledger keyed by execution and operation. Interrupted, uncheckpointed work may be retried.
* Diagnostics (including target-JVM admission and barriers) are test-only
  instrumentation. They never choose operation names or business branches.
  Deliberately blocked tasks have finite escape timers. Escape diagnostics fail
  lifecycle assertions; they cannot turn the reproduced bug into a pass.
* CloudWatch collection polls for causal evidence and deduplicates JVM sequence
  numbers. Test reports distinguish setup, placement, assertion, collection,
  and teardown failures. Raw histories, configuration, and diagnostic logs are
  retained even when a scenario fails.

## Ownership

`CAPACITY_PROVIDER_ARN` identifies an existing **dedicated test** capacity
provider. The suite never creates, updates, or deletes it. Its owner must bound
its maximum vCPUs and provide working Lambda/S3/CloudWatch connectivity. The
workflow uses `TEST_ROLE_ARN`, `TEST_ACCOUNT_ID`, and
`TEST_LAMBDA_EXECUTION_ROLE_ARN`, as the ordinary E2E workflow does.

Each run owns one tagged CloudFormation stack containing all five functions and
log groups, plus one private staging/control bucket with one-day object expiry.
Normal teardown deletes that stack, then empties and deletes the bucket. A scheduled janitor removes
only expired resources bearing this suite's ownership tags, including runs
cancelled before normal teardown. Logs and durable histories retain one day in
AWS; GitHub artifacts retain seven days. The capacity provider remains owned by
the test-account operator, including any idle instance cost.

## Running

See the workflow `lmi-e2e-tests.yml` for the complete commands and budgets. The
cloud driver requires Python 3.9+, AWS CLI v2 with LMI/Durable API support, and
credentials for the dedicated test account. There are no new Python packages.
The Java fixture uses the repository SDK and existing dependencies only. One local
contract test runs the real AWS CLI against an unsigned localhost HTTP endpoint
to validate synchronous/asynchronous invocation arguments and payload bytes. It
does not call AWS or provide cloud coverage.

```sh
mvn -B -pl lmi-tests -am package -DskipTests
python3 -m unittest discover -s lmi-tests/tests -v
export CAPACITY_PROVIDER_ARN=arn:aws:lambda:REGION:ACCOUNT:capacity-provider:NAME
export TEST_LAMBDA_EXECUTION_ROLE_ARN=arn:aws:iam::ACCOUNT:role/ROLE
export AWS_REGION=us-west-2
python3 lmi-tests/cloud_suite.py deploy --run-id local-unique
python3 lmi-tests/cloud_suite.py test --cloud-enabled
python3 lmi-tests/cloud_suite.py collect
python3 lmi-tests/cloud_suite.py cleanup
```

The deploy command creates and retains `default1`, `default2`, `default8`,
`fixed2`, and `nested2` together. The test command requires all five and runs the
full suite. CI publishes one combined artifact, named `lmi-e2e-RUN-ATTEMPT`, with
the single `template.json` and per-function configuration snapshots. The
CloudFormation execution role needs permission to manage the function scaling
configuration; the driver only calls `lambda:GetFunctionScalingConfig` to verify
it after deployment.

Deployment records `lmi-tests/artifacts/manifest.json`, including the stack, functions, commit, jar
digest, qualified function ARNs, runtime, architecture, concurrency and provider
association. Never publish control URLs: they are temporary credentials. The
artifact writer redacts them from histories and logs.

Cloud tests are disabled unless `test --cloud-enabled` is explicitly requested.
Local assertion tests verify that missing evidence, mismatched environments,
early responses, late tasks and stalled executors cannot be reported as passes.
Cloud regressions run on every push to `main`, including every merged change,
with no changed-path filters. Manual dispatch and same-repository PR opt-in
are also supported. The daily schedule runs only the cleanup janitor.

The opt-in local regressions assert the same three contracts against the SDK's
mock backend (they do not substitute for cloud coverage):

```sh
mvn -pl sdk test -Dtest=LmiLifecycleRegressionTest -Dtest.lmi.regressions.enabled=true
```

For a same-repository PR, add the `run-lmi-e2e` label to opt into cloud execution.
The workflow never uses a privileged `pull_request_target` checkout. Provisioning
has a 35-minute step budget, with a shared 30-minute deadline for creating the
stack and verifying all five functions. Each scaling wait is capped at 5 minutes and at the
remaining deployment budget. Scenarios have 30 minutes, final collection 5 minutes,
and stack teardown 10 minutes. Individual admission attempts are bounded (four batches,
25 seconds), fixed-pool progress has 8 seconds, and task escape timers are capped
at 120 seconds. The normal invocation timeout is 60 seconds; the durable execution
timeout is 240 seconds. Cleanup is required by the invocation deadline plus
5 seconds. Probe admission has an 8-second tolerance. Collection latency does
not extend these assertions, which compare timestamps captured inside the JVM.

`timeouts/*.json` distinguishes server timeout logs from an SDK deadline
cancellation that returns early with an invocation error. A client HTTP timeout
is a collection error. Deadline victims use asynchronous service invocation so a
longer durable retry does not consume the driver HTTP timeout; invocation
outcomes come from request-correlated runtime logs. Invocation request failures
are saved in `invocations/*.json` with the function ARN, scenario, start time,
request state, elapsed time, and CLI exit code/error when available. The driver
checks the invocation Future during evidence polling so an API/CLI failure is
reported immediately. Missing runtime-entry evidence is a collection error;
lifecycle assertions apply after the wrapper entry has been observed. Healthy/probe scheduling
uses the actual runtime deadline, excluding cold-start and request-queue delay.
A successful durable retry cannot erase an old invocation
that exceeds the cleanup budget. No virtual-thread executor variant is deployed
until its executor contract is defined; default cached and shared fixed pools
are covered separately.

CloudFormation's native [FunctionScalingConfig](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-lambda-function-functionscalingconfig.html)
sets the limits in the resource declaration. LMI provisioning and version behavior
are documented in the AWS guides for
[scaling](https://docs.aws.amazon.com/lambda/latest/dg/lambda-managed-instances-scaling.html)
and [$LATEST.PUBLISHED](https://docs.aws.amazon.com/lambda/latest/dg/lambda-managed-instances-version-publishing.html).
