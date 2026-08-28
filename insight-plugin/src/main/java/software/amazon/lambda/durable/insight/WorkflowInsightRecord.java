// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The curated execution record emitted to destinations.
 *
 * <p>Mirrors the JS {@code WorkflowInsightRecord} interface field-for-field so the emitted wire JSON is identical
 * (camelCase names, absent fields omitted). Exactly one of {@code operations} (array) or the {@code operationsByName}
 * rendering is emitted, depending on the exporter's {@code render}.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class WorkflowInsightRecord {
    String recordType = "WorkflowInsight";
    String schemaVersion = "1.0";
    String emittedAt;
    String executionArn;
    String executionName;
    String functionName;
    String functionQualifier;
    String region;
    String accountId;
    String status;
    String startTime;
    String endTime;
    Long durationMs;
    Object input;
    Object output;
    ErrorInfo error;
    List<OperationRecord> operations = new ArrayList<>();
    Boolean truncated;
    Integer droppedOperations;
    Boolean droppedInput;
    Boolean droppedOutput;

    public List<OperationRecord> operations() {
        return Collections.unmodifiableList(operations);
    }

    /** Package-private mutator to append an operation during record construction (public accessor stays immutable). */
    void addOperation(OperationRecord operation) {
        operations.add(operation);
    }

    public String executionArn() {
        return executionArn;
    }

    public String executionName() {
        return executionName;
    }

    public String functionName() {
        return functionName;
    }

    public String startTime() {
        return startTime;
    }

    public String status() {
        return status;
    }

    /** Shallow copy with a fresh operations list — used by the size limiter so the shared record is never mutated. */
    public WorkflowInsightRecord copy() {
        WorkflowInsightRecord c = new WorkflowInsightRecord();
        c.recordType = recordType;
        c.schemaVersion = schemaVersion;
        c.emittedAt = emittedAt;
        c.executionArn = executionArn;
        c.executionName = executionName;
        c.functionName = functionName;
        c.functionQualifier = functionQualifier;
        c.region = region;
        c.accountId = accountId;
        c.status = status;
        c.startTime = startTime;
        c.endTime = endTime;
        c.durationMs = durationMs;
        c.input = input;
        c.output = output;
        c.error = error;
        c.operations = new ArrayList<>(operations);
        c.truncated = truncated;
        c.droppedOperations = droppedOperations;
        c.droppedInput = droppedInput;
        c.droppedOutput = droppedOutput;
        return c;
    }

    /**
     * Deep copy for per-exporter isolation. Each operation record is deep-copied and the execution
     * {@code input}/{@code output} payloads have their mutable container structure rebuilt, so a custom exporter that
     * clears, redacts, or mutates operations or nested content cannot corrupt any exporter that runs after it. Because
     * truncation returns the original record when it already fits, this copy is taken per exporter before shaping.
     * {@code error} is an immutable {@link ErrorInfo} and is shared safely.
     */
    public WorkflowInsightRecord deepCopy() {
        WorkflowInsightRecord c = copy();
        List<OperationRecord> deepOps = new ArrayList<>(operations.size());
        for (OperationRecord op : operations) {
            deepOps.add(op.deepCopy());
        }
        c.operations = deepOps;
        c.input = Json.deepCopyContent(input);
        c.output = Json.deepCopyContent(output);
        return c;
    }

    /** Common scalar fields shared by the array and by-name renderings, in JS field order (absent fields omitted). */
    private Map<String, Object> baseWireMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordType", recordType);
        data.put("schemaVersion", schemaVersion);
        if (emittedAt != null) {
            data.put("emittedAt", emittedAt);
        }
        if (executionArn != null) {
            data.put("executionArn", executionArn);
        }
        if (executionName != null) {
            data.put("executionName", executionName);
        }
        if (functionName != null) {
            data.put("functionName", functionName);
        }
        if (functionQualifier != null) {
            data.put("functionQualifier", functionQualifier);
        }
        if (region != null) {
            data.put("region", region);
        }
        if (accountId != null) {
            data.put("accountId", accountId);
        }
        if (status != null) {
            data.put("status", status);
        }
        if (startTime != null) {
            data.put("startTime", startTime);
        }
        if (endTime != null) {
            data.put("endTime", endTime);
        }
        if (durationMs != null) {
            data.put("durationMs", durationMs);
        }
        if (input != null) {
            data.put("input", input);
        }
        if (output != null) {
            data.put("output", output);
        }
        if (error != null) {
            data.put("error", error.toWireMap());
        }
        return data;
    }

    private void putTruncationMarkers(Map<String, Object> data) {
        if (truncated != null) {
            data.put("truncated", truncated);
        }
        if (droppedOperations != null) {
            data.put("droppedOperations", droppedOperations);
        }
        if (droppedInput != null) {
            data.put("droppedInput", droppedInput);
        }
        if (droppedOutput != null) {
            data.put("droppedOutput", droppedOutput);
        }
    }

    /** Canonical {@code operations}-array wire map (the shape S3Exporter serializes). */
    public Map<String, Object> toWireMap() {
        Map<String, Object> data = baseWireMap();
        List<Map<String, Object>> ops = new ArrayList<>(operations.size());
        for (OperationRecord op : operations) {
            ops.add(op.toWireMap());
        }
        data.put("operations", ops);
        putTruncationMarkers(data);
        return data;
    }

    /** {@code operationsByName} wire map (the shape LambdaLogExporter / CloudWatchLogsExporter serialize). */
    public Map<String, Object> toByNameWireMap() {
        Map<String, Object> data = baseWireMap();
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Map.Entry<String, OperationSummary> e :
                OperationsIndex.buildOperationsByName(operations).entrySet()) {
            byName.put(e.getKey(), e.getValue().toWireMap());
        }
        data.put("operationsByName", byName);
        putTruncationMarkers(data);
        return data;
    }
}
