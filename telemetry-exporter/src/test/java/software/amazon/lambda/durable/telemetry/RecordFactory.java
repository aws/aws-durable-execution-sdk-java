// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

/** Builds a minimal, fully populated record for exporter tests outside this package. */
public final class RecordFactory {
    private RecordFactory() {}

    public static DurableExecutionTelemetryRecord sample() {
        DurableExecutionTelemetryRecord r = new DurableExecutionTelemetryRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
        r.executionName = "exec-1";
        r.functionName = "fn";
        r.status = "SUCCEEDED";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        return r;
    }

    /** {@link #sample()} with the given execution {@code input}. */
    public static DurableExecutionTelemetryRecord sampleWithInput(Object input) {
        DurableExecutionTelemetryRecord r = sample();
        r.input = input;
        return r;
    }
}
