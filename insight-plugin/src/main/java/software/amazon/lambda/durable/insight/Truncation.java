// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Best-effort size limiter, porting the JS {@code truncateRecord} drop order:
 *
 * <ol>
 *   <li>operation {@code result} fields, oldest operation first;
 *   <li>whole operations, oldest first;
 *   <li>last resort — execution {@code input}, then {@code output}.
 * </ol>
 *
 * <p>Identity/timeline fields are never dropped. When anything is dropped the returned record has {@code truncated:
 * true}; each operation whose result was dropped is itself marked {@code truncated: true}, and
 * {@code droppedOperations} / {@code droppedInput} / {@code droppedOutput} markers are set as applicable. The input
 * record is never mutated. {@code render} maps the record to the exact shape the exporter serializes so the size check
 * measures what is actually emitted.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class Truncation {

    private Truncation() {}

    public static WorkflowInsightRecord truncateRecord(
            WorkflowInsightRecord record, Integer maxBytes, Function<WorkflowInsightRecord, Object> render) {
        if (maxBytes == null || maxBytes <= 0) {
            return record;
        }
        Integer initialSize = Json.byteSize(render.apply(record));
        if (initialSize == null || initialSize <= maxBytes) {
            return record;
        }

        List<OperationRecord> ops = new ArrayList<>(record.operations().size());
        for (OperationRecord op : record.operations()) {
            ops.add(op.copy());
        }
        boolean[] kept = new boolean[ops.size()];
        java.util.Arrays.fill(kept, true);
        int[] order = oldestFirstOrder(ops);

        boolean[] anyResultDropped = {false};
        int[] droppedOperations = {0};
        boolean[] droppedInput = {false};
        boolean[] droppedOutput = {false};

        // Phase 1: drop operation results, oldest first (kept + marked truncated).
        for (int idx : order) {
            if (fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)) {
                break;
            }
            if (kept[idx] && ops.get(idx).result() != null) {
                ops.set(idx, ops.get(idx).copy().result(null).truncated(true));
                anyResultDropped[0] = true;
            }
        }

        // Phase 2: drop whole operations, oldest first.
        for (int idx : order) {
            if (fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)) {
                break;
            }
            if (kept[idx]) {
                kept[idx] = false;
                droppedOperations[0]++;
            }
        }

        // Phase 3 (last resort): drop execution input, then output.
        if (!fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)
                && record.input != null) {
            droppedInput[0] = true;
        }
        if (!fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)
                && record.output != null) {
            droppedOutput[0] = true;
        }

        if (!anyResultDropped[0] && droppedOperations[0] == 0 && !droppedInput[0] && !droppedOutput[0]) {
            return record;
        }
        return candidate(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0]);
    }

    private static boolean fits(
            WorkflowInsightRecord record,
            List<OperationRecord> ops,
            boolean[] kept,
            int droppedOperations,
            boolean droppedInput,
            boolean droppedOutput,
            Function<WorkflowInsightRecord, Object> render,
            int maxBytes) {
        Integer size = Json.byteSize(
                render.apply(candidate(record, ops, kept, droppedOperations, droppedInput, droppedOutput)));
        return size != null && size <= maxBytes;
    }

    private static WorkflowInsightRecord candidate(
            WorkflowInsightRecord record,
            List<OperationRecord> ops,
            boolean[] kept,
            int droppedOperations,
            boolean droppedInput,
            boolean droppedOutput) {
        WorkflowInsightRecord out = record.copy();
        List<OperationRecord> retained = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (kept[i]) {
                retained.add(ops.get(i));
            }
        }
        out.operations = retained;
        out.truncated = Boolean.TRUE;
        if (droppedOperations > 0) {
            out.droppedOperations = droppedOperations;
        }
        if (droppedInput) {
            out.input = null;
            out.droppedInput = Boolean.TRUE;
        }
        if (droppedOutput) {
            out.output = null;
            out.droppedOutput = Boolean.TRUE;
        }
        return out;
    }

    /** Oldest-first by startTime ascending; operations without a parseable startTime are treated as newest. */
    private static int[] oldestFirstOrder(List<OperationRecord> operations) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            indices.add(i);
        }
        indices.sort(Comparator.comparingDouble((Integer i) -> startKey(operations.get(i)))
                .thenComparingInt(i -> i));
        int[] order = new int[indices.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = indices.get(i);
        }
        return order;
    }

    private static double startKey(OperationRecord op) {
        if (op.startTime() == null) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            return java.time.Instant.parse(op.startTime()).toEpochMilli();
        } catch (RuntimeException e) {
            return Double.POSITIVE_INFINITY;
        }
    }
}
