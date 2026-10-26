// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
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
        return truncate(record, maxBytes, r -> Json.byteSize(render.apply(r)), false);
    }

    /**
     * {@link #truncateRecord} for a rendering whose size {@code size} measures directly, such as
     * {@link software.amazon.lambda.durable.telemetry.exporters.LambdaLogExporter#renderedSize}. The rendering must be
     * the record's JSON, or that JSON embedded as one JSON string value, so that dropping one result or operation
     * shrinks it by at most that operation's escaped JSON size plus a small constant. Size checks that cannot succeed
     * under that bound are skipped; the outcome is the same as checking after every step.
     */
    static DurableExecutionTelemetryRecord truncateMeasured(
            DurableExecutionTelemetryRecord record,
            Integer maxBytes,
            Function<DurableExecutionTelemetryRecord, Integer> size) {
        return truncate(record, maxBytes, size, true);
    }

    /**
     * Slack added to each operation's escaped JSON size when bounding how much dropping its result, or the whole
     * operation, can shrink the rendering. It covers the {@code operationsByName} summary fields that are not in the
     * operation's own JSON ({@code count}, {@code failedCount} and the duration aggregates) and separators.
     */
    private static final long SHRINK_SLACK_BYTES = 1_024;

    private static DurableExecutionTelemetryRecord truncate(
            DurableExecutionTelemetryRecord record,
            Integer maxBytes,
            Function<DurableExecutionTelemetryRecord, Integer> size,
            boolean bounded) {
        if (maxBytes == null || maxBytes <= 0) {
            return record;
        }
        Integer initialSize = size.apply(record);
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
        DurableExecutionTelemetryRecord record0 = record;
        boolean[] kept = new boolean[ops.size()];
        java.util.Arrays.fill(kept, true);
        int[] order = oldestFirstOrder(ops);
        long[] shrinkBound = bounded ? shrinkBounds(ops) : null;
        Meter meter = new Meter(size, maxBytes, bounded);

        boolean[] anyResultDropped = {false};
        int[] droppedOperations = {record.droppedOperations != null ? record.droppedOperations : 0};
        boolean[] droppedInput = {Boolean.TRUE.equals(record.droppedInput)};
        boolean[] droppedOutput = {Boolean.TRUE.equals(record.droppedOutput)};
        int incomingDroppedOperations = droppedOperations[0];

        // Step 2: drop operation results, oldest first (kept + marked truncated).
        for (int idx : order) {
            if (meter.fits(
                    () -> candidate(record0, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0]))) {
                break;
            }
            if (kept[idx] && ops.get(idx).result() != null) {
                ops.set(idx, ops.get(idx).copy().result(null).truncated(true));
                anyResultDropped[0] = true;
                meter.shrunkBy(bounded ? shrinkBound[idx] : 0);
            }
        }

        // Step 3: drop whole operations, oldest first.
        for (int idx : order) {
            if (meter.fits(
                    () -> candidate(record0, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0]))) {
                break;
            }
            if (kept[idx]) {
                kept[idx] = false;
                droppedOperations[0]++;
                meter.shrunkBy(bounded ? shrinkBound[idx] : 0);
            }
        }

        // Step 4 (last resort): drop execution input, then output.
        if (!meter.fits(() -> candidate(record0, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0]))
                && record.input != null) {
            droppedInput[0] = true;
            meter.forget(); // Input can be any size: measure again.
        }
        if (!meter.fits(() -> candidate(record0, ops, kept, droppedOperations[0], droppedInput[0], droppedOutput[0]))
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

    /**
     * Upper bound, per operation, on how much dropping its result or the whole operation can shrink the rendering: the
     * operation's JSON and its name, sized as if escaped again inside a JSON string, plus {@link #SHRINK_SLACK_BYTES}.
     * Each operation is serialized once.
     */
    private static long[] shrinkBounds(List<OperationRecord> ops) {
        long[] bounds = new long[ops.size()];
        for (int i = 0; i < bounds.length; i++) {
            OperationRecord op = ops.get(i);
            long bound = SHRINK_SLACK_BYTES;
            try {
                bound += Json.escapedUtf8Size(Json.stringify(op.toWireMap()));
                if (op.name() != null) {
                    bound += Json.escapedUtf8Size(Json.stringify(op.name()));
                }
            } catch (IllegalStateException e) {
                bound = Long.MAX_VALUE / 4; // Unknown: never skip a check after dropping this operation.
            }
            bounds[i] = bound;
        }
        return bounds;
    }

    /**
     * Runs the size checks. With a bound, after an exact measurement it skips checks while the measured size minus the
     * most the drops since then could have removed is still over the limit; such a check could not succeed, so the
     * outcome is the same as measuring every time.
     */
    static final class Meter {
        private final Function<DurableExecutionTelemetryRecord, Integer> size;
        private final int maxBytes;
        private final boolean bounded;
        private long lastSize = -1; // -1: no usable measurement
        private long shrinkSinceLast;

        Meter(Function<DurableExecutionTelemetryRecord, Integer> size, int maxBytes, boolean bounded) {
            this.size = size;
            this.maxBytes = maxBytes;
            this.bounded = bounded;
        }

        boolean fits(Supplier<DurableExecutionTelemetryRecord> candidate) {
            if (bounded && lastSize >= 0 && lastSize - shrinkSinceLast > maxBytes) {
                return false;
            }
            Integer measured = size.apply(candidate.get());
            lastSize = measured != null ? measured : -1;
            shrinkSinceLast = 0;
            return measured != null && measured <= maxBytes;
        }

        void shrunkBy(long bound) {
            shrinkSinceLast += bound;
        }

        /** The next check measures, whatever was dropped since the last measurement. */
        void forget() {
            lastSize = -1;
        }
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
