// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A per-operation-name summary emitted (as an {@code operationsByName} map) by point-access exporters (CloudWatch
 * Logs).
 *
 * <p>Mirrors the JS {@code OperationSummary} interface. Metric fields aggregate across all occurrences of the name;
 * {@code type}, {@code subType}, {@code status} reflect the most recently seen occurrence; {@code result}/{@code error}
 * are included only when the name occurs exactly once.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class OperationSummary {
    String type;
    String subType;
    int count;
    Long minDurationMs;
    Long maxDurationMs;
    Long totalDurationMs;
    int failedCount;
    Integer maxAttempt;
    String status;
    Object result;
    ErrorInfo error;

    /** Serializes to the camelCase wire map, omitting absent (null) fields, in the JS field order. */
    public Map<String, Object> toWireMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (type != null) {
            data.put("type", type);
        }
        if (subType != null) {
            data.put("subType", subType);
        }
        data.put("count", count);
        if (minDurationMs != null) {
            data.put("minDurationMs", minDurationMs);
        }
        if (maxDurationMs != null) {
            data.put("maxDurationMs", maxDurationMs);
        }
        if (totalDurationMs != null) {
            data.put("totalDurationMs", totalDurationMs);
        }
        data.put("failedCount", failedCount);
        if (maxAttempt != null) {
            data.put("maxAttempt", maxAttempt);
        }
        if (status != null) {
            data.put("status", status);
        }
        if (result != null) {
            data.put("result", result);
        }
        if (error != null) {
            data.put("error", error.toWireMap());
        }
        return data;
    }
}
