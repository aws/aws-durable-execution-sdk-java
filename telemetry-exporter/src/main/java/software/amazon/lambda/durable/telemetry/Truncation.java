// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Best-effort size limiter. When the record is over the limit it drops, in order:
 *
 * <ol>
 *   <li>every error {@code cause} (on the execution {@code error} and every operation {@code error}, and so on every
 *       {@code operationsByName} error), setting {@code droppedCauses: true};
 *   <li>operation {@code result} fields, oldest operation first;
 *   <li>whole operations, oldest first;
 *   <li>last resort — execution {@code input}, then {@code output}.
 * </ol>
 *
 * <p>Identity/timeline fields are never dropped. When anything is dropped the returned record has {@code truncated:
 * true}; each operation whose result was dropped is itself marked {@code truncated: true}, and {@code droppedCauses} /
 * {@code droppedOperations} / {@code droppedInput} / {@code droppedOutput} markers are set as applicable. Markers
 * already on the incoming record are kept, and {@code droppedOperations} adds to an incoming count, so truncating an
 * already-truncated record never under-reports. The input record is never mutated. {@code render} maps the record to
 * the exact value the exporter serializes so the size check measures what is actually emitted. This is one pass; the
 * execution {@code error} and identity fields are never dropped, so a record can still be over a limit smaller than
 * those fields.
 */
@Experimental
public final class Truncation {

    private Truncation() {}

    public static DurableExecutionTelemetryRecord truncateRecord(
            DurableExecutionTelemetryRecord record,
            Integer maxBytes,
            Function<DurableExecutionTelemetryRecord, Object> render) {
        if (maxBytes == null || maxBytes <= 0) {
            return record;
        }
        Integer initialSize = Json.byteSize(render.apply(record));
        if (initialSize == null || initialSize <= maxBytes) {
            return record;
        }

        // Step 1: drop every error cause. operationsByName errors are derived from the operation errors.
        boolean causesDropped = record.error != null && record.error.cause() != null;
        List<OperationRecord> ops = new ArrayList<>(record.operations().size());
        for (OperationRecord op : record.operations()) {
            OperationRecord copy = op.copy();
            if (copy.error() != null && copy.error().cause() != null) {
                copy.error(withoutCause(copy.error()));
                causesDropped = true;
            }
            ops.add(copy);
        }
        if (causesDropped) {
            DurableExecutionTelemetryRecord base = record.copy();
            base.error = withoutCause(record.error);
            base.droppedCauses = Boolean.TRUE;
            record = base;
        }
        boolean[] kept = new boolean[ops.size()];
        java.util.Arrays.fill(kept, true);
        int[] order = oldestFirstOrder(ops);

        boolean[] anyResultDropped = {false};
        int[] droppedOperations = {record.droppedOperations != null ? record.droppedOperations : 0};
        boolean[] droppedInput = {Boolean.TRUE.equals(record.droppedInput)};
        boolean[] droppedOutput = {Boolean.TRUE.equals(record.droppedOutput)};
        int incomingDroppedOperations = droppedOperations[0];

        // Step 2: drop operation results, oldest first (kept + marked truncated).
        for (int idx : order) {
            if (fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)) {
                break;
            }
            if (kept[idx] && ops.get(idx).result() != null) {
                ops.set(idx, ops.get(idx).copy().result(null).truncated(true));
                anyResultDropped[0] = true;
            }
        }

        // Step 3: drop whole operations, oldest first.
        for (int idx : order) {
            if (fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)) {
                break;
            }
            if (kept[idx]) {
                kept[idx] = false;
                droppedOperations[0]++;
            }
        }

        // Step 4 (last resort): drop execution input, then output.
        if (!fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)
                && record.input != null) {
            droppedInput[0] = true;
        }
        if (!fits(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0], render, maxBytes)
                && record.output != null) {
            droppedOutput[0] = true;
        }

        if (!causesDropped
                && !anyResultDropped[0]
                && droppedOperations[0] == incomingDroppedOperations
                && Boolean.TRUE.equals(record.droppedInput) == droppedInput[0]
                && Boolean.TRUE.equals(record.droppedOutput) == droppedOutput[0]) {
            return record;
        }
        return candidate(record, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0]);
    }

    private static ErrorInfo withoutCause(ErrorInfo error) {
        if (error == null || error.cause() == null) {
            return error;
        }
        return new ErrorInfo(error.name(), error.message());
    }

    private static boolean fits(
            DurableExecutionTelemetryRecord record,
            List<OperationRecord> ops,
            boolean[] kept,
            int droppedOperations,
            boolean droppedInput,
            boolean droppedOutput,
            Function<DurableExecutionTelemetryRecord, Object> render,
            int maxBytes) {
        Integer size = Json.byteSize(
                render.apply(candidate(record, ops, kept, droppedOperations, droppedInput, droppedOutput)));
        return size != null && size <= maxBytes;
    }

    private static DurableExecutionTelemetryRecord candidate(
            DurableExecutionTelemetryRecord record,
            List<OperationRecord> ops,
            boolean[] kept,
            int droppedOperations,
            boolean droppedInput,
            boolean droppedOutput) {
        DurableExecutionTelemetryRecord out = record.copy();
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
