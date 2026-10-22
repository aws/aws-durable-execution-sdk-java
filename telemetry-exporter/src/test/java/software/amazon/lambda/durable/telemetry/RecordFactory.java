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

    /** {@code record} with the given execution {@code input}. */
    public static DurableExecutionTelemetryRecord withInput(DurableExecutionTelemetryRecord record, Object input) {
        record.input = input;
        return record;
    }

    /** {@link #sample()} with a failed status and an execution {@code error} carrying one cause. */
    public static DurableExecutionTelemetryRecord sampleWithError(String message, String causeMessage) {
        DurableExecutionTelemetryRecord r = sample();
        r.status = "FAILED";
        r.error = new ErrorInfo("PaymentError", message, new ErrorInfo("IOException", causeMessage));
        return r;
    }

    /**
     * {@link #sample()} with {@code count} failed operations named {@code op-<i>}, started one second apart, each with
     * an error whose message is {@code message} and that has one cause.
     */
    public static DurableExecutionTelemetryRecord sampleWithFailedOperations(int count, String message) {
        DurableExecutionTelemetryRecord r = sample();
        r.operations.clear();
        java.time.Instant start = java.time.Instant.parse("2026-07-15T11:00:00Z");
        for (int i = 0; i < count; i++) {
            r.addOperation(new OperationRecord()
                    .id("id-" + i)
                    .name("op-" + i)
                    .type("STEP")
                    .status("FAILED")
                    .startTime(start.plusSeconds(i).toString())
                    .error(new ErrorInfo("java.lang.RuntimeException", message, new ErrorInfo("Throwable", "c"))));
        }
        return r;
    }

    /** {@code record} with {@code operation} appended. */
    public static DurableExecutionTelemetryRecord withOperation(
            DurableExecutionTelemetryRecord record, OperationRecord operation) {
        record.addOperation(operation);
        return record;
    }
}
