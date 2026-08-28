// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a name-keyed index of operation summaries from the canonical operations array.
 *
 * <p>Ports the JS {@code buildOperationsByName} logic exactly: on the first occurrence of a name insert a summary
 * (including its {@code result}/{@code error}); on a repeated name aggregate the metrics and drop {@code result} and
 * {@code error} (no single representative value). Scalar fields ({@code type}, {@code subType}, {@code status}) reflect
 * the most recently seen occurrence. Unnamed operations are skipped.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class OperationsIndex {

    private OperationsIndex() {}

    public static Map<String, OperationSummary> buildOperationsByName(List<OperationRecord> operations) {
        // Insertion-ordered so the emitted map mirrors first-seen order (matches the JS Map iteration order).
        Map<String, OperationSummary> groups = new LinkedHashMap<>();

        for (OperationRecord op : operations) {
            if (op.name() == null) {
                continue;
            }
            Long duration = op.durationMs();
            Integer attempt = op.attempt();
            int failed = "FAILED".equals(op.status()) ? 1 : 0;

            OperationSummary existing = groups.get(op.name());
            if (existing == null) {
                OperationSummary s = new OperationSummary();
                s.type = op.type();
                s.count = 1;
                s.failedCount = failed;
                s.status = op.status();
                if (op.subType() != null) {
                    s.subType = op.subType();
                }
                if (duration != null) {
                    s.minDurationMs = duration;
                    s.maxDurationMs = duration;
                    s.totalDurationMs = duration;
                }
                if (attempt != null) {
                    s.maxAttempt = attempt;
                }
                if (op.result() != null) {
                    s.result = op.result();
                }
                if (op.error() != null) {
                    s.error = op.error();
                }
                groups.put(op.name(), s);
                continue;
            }

            // Repeated name: aggregate and drop the per-occurrence result/error.
            existing.count += 1;
            existing.failedCount += failed;
            existing.type = op.type();
            existing.status = op.status();
            if (op.subType() != null) {
                existing.subType = op.subType();
            } else {
                existing.subType = null;
            }
            if (duration != null) {
                existing.minDurationMs =
                        existing.minDurationMs == null ? duration : Math.min(existing.minDurationMs, duration);
                existing.maxDurationMs =
                        existing.maxDurationMs == null ? duration : Math.max(existing.maxDurationMs, duration);
                existing.totalDurationMs =
                        (existing.totalDurationMs == null ? 0L : existing.totalDurationMs) + duration;
            }
            if (attempt != null) {
                existing.maxAttempt = existing.maxAttempt == null ? attempt : Math.max(existing.maxAttempt, attempt);
            }
            existing.result = null;
            existing.error = null;
        }
        return groups;
    }
}
