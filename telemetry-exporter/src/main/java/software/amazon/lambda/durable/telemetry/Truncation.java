// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntPredicate;

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
 *
 * <p>Clearing a result replaces {@code "result":<json>} with {@code "truncated":true} on the operation. When the result
 * JSON is 7 bytes or shorter, that makes the array form bigger. A by-name rendering writes no per-operation marker, so
 * there clearing any result makes the record smaller. So when small results exist, step 2 renders the record once with
 * every small result cleared. If that render is smaller, small results are cleared like any other result. Otherwise
 * they stay in the record and their operations are not marked.
 *
 * <p>Steps 2 and 3 binary search how many results, then how many operations, to drop. So the pass renders the record
 * about {@code 2 * log2(n)} times for {@code n} operations, with any {@code render}. The search assumes that dropping
 * more never makes the record larger. That is not always true. So the pass can drop more results or operations than a
 * one-at-a-time pass. Two known cases: in the {@code operationsByName} rendering, dropping one of two operations with
 * the same name can add the remaining operation's result and error to the summary. In a rendering that escapes the
 * record into a string, such as the OpenTelemetry body, the 7-byte rule and the probe render can misjudge small
 * results. The chosen record is always one that was measured to fit. So the output never goes over the limit, as long
 * as dropping everything droppable makes it fit. When even that does not fit, the output can be over the limit.
 */
public final class Truncation {

    private Truncation() {}

    public static DurableExecutionTelemetryRecord truncateRecord(
            DurableExecutionTelemetryRecord record,
            Integer maxBytes,
            Function<DurableExecutionTelemetryRecord, Object> render) {
        return truncate(record, maxBytes, r -> Json.byteSize(render.apply(r)));
    }

    /**
     * {@link #truncateRecord} for a rendering whose size {@code size} measures directly, such as
     * {@link software.amazon.lambda.durable.telemetry.exporters.LambdaLogExporter#renderedSize}, so the pass does not
     * build the rendering.
     */
    static DurableExecutionTelemetryRecord truncateMeasured(
            DurableExecutionTelemetryRecord record,
            Integer maxBytes,
            Function<DurableExecutionTelemetryRecord, Integer> size) {
        return truncate(record, maxBytes, size);
    }

    private static DurableExecutionTelemetryRecord truncate(
            DurableExecutionTelemetryRecord record,
            Integer maxBytes,
            Function<DurableExecutionTelemetryRecord, Integer> size) {
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
        int[] order = oldestFirstOrder(ops);
        int incomingDroppedOperations = record.droppedOperations != null ? record.droppedOperations : 0;
        boolean incomingDroppedInput = Boolean.TRUE.equals(record.droppedInput);
        boolean incomingDroppedOutput = Boolean.TRUE.equals(record.droppedOutput);
        // Whether the record fits with these operations, the first k operations in oldest-first order dropped.
        BiPredicate<List<OperationRecord>, Integer> fitsWith = (shapedOps, k) -> fits(
                size,
                maxBytes,
                candidate(
                        record0,
                        shapedOps,
                        keptAfterDropping(order, k),
                        incomingDroppedOperations + k,
                        incomingDroppedInput,
                        incomingDroppedOutput));

        // Size of the record with these operations and no operation dropped.
        Function<List<OperationRecord>, Integer> sizeWith = shapedOps -> size.apply(candidate(
                record0,
                shapedOps,
                keptAfterDropping(order, 0),
                incomingDroppedOperations,
                incomingDroppedInput,
                incomingDroppedOutput));

        // Step 2: drop operation results, oldest first (kept + marked truncated).
        int[] withResult =
                Arrays.stream(order).filter(i -> ops.get(i).result() != null).toArray();
        // j = 0 keeps every result. Without dropped causes that record is the input with markers added, which was
        // measured not to fit, so its fit is checked only when causes were dropped.
        Integer keptSize = causesDropped ? sizeWith.apply(ops) : null;
        int results;
        if (keptSize != null && keptSize <= maxBytes) {
            results = 0;
        } else {
            // Clearing a result replaces "result":<json> with "truncated":true on the operation. When the result JSON
            // is 7 bytes or shorter, that makes the array form bigger, so it cannot help the record fit. A by-name
            // rendering writes no per-operation marker, so there clearing any result makes the record smaller. One
            // render with every small result cleared tells which case this rendering is: if it is smaller than the
            // record with every result kept, small results stay candidates; otherwise only larger results are.
            int[] small = Arrays.stream(withResult)
                    .filter(i -> !shrinksWhenCleared(ops.get(i).result()))
                    .toArray();
            if (small.length > 0) {
                if (keptSize == null) {
                    keptSize = sizeWith.apply(ops);
                }
                Integer smallClearedSize = sizeWith.apply(withoutResults(ops, small, small.length));
                boolean clearingSmallShrinks =
                        keptSize != null && smallClearedSize != null && smallClearedSize < keptSize;
                if (!clearingSmallShrinks) {
                    withResult = Arrays.stream(withResult)
                            .filter(i -> shrinksWhenCleared(ops.get(i).result()))
                            .toArray();
                }
            }
            int[] candidates = withResult;
            results = smallestFitting(1, candidates.length, j -> fitsWith.test(withoutResults(ops, candidates, j), 0));
        }
        boolean fitting = results >= 0;
        int resultsDropped = fitting ? results : withResult.length;
        List<OperationRecord> shaped = withoutResults(ops, withResult, resultsDropped);

        // Step 3: drop whole operations, oldest first.
        int dropped = 0;
        if (!fitting) {
            dropped = smallestFitting(1, ops.size(), k -> fitsWith.test(shaped, k));
            fitting = dropped >= 0;
            dropped = fitting ? dropped : ops.size();
        }
        boolean[] kept = keptAfterDropping(order, dropped);
        int droppedOperations = incomingDroppedOperations + dropped;
        boolean droppedInput = incomingDroppedInput;
        boolean droppedOutput = incomingDroppedOutput;

        // Step 4 (last resort): drop execution input, then output.
        if (!fitting && record.input != null) {
            droppedInput = true;
            fitting = fits(
                    size, maxBytes, candidate(record0, shaped, kept, droppedOperations, droppedInput, droppedOutput));
        }
        if (!fitting && record.output != null) {
            droppedOutput = true;
        }

        if (!causesDropped
                && resultsDropped == 0
                && dropped == 0
                && incomingDroppedInput == droppedInput
                && incomingDroppedOutput == droppedOutput) {
            return record;
        }
        return candidate(record, shaped, kept, droppedOperations, droppedInput, droppedOutput);
    }

    private static final Integer CLEARED_RESULT_SIZE = Json.byteSize(Map.of("truncated", Boolean.TRUE));

    /** Whether {@code "result":<result>} is larger than {@code "truncated":true}: the result JSON is over 7 bytes. */
    private static boolean shrinksWhenCleared(Object result) {
        Integer size = Json.byteSize(Map.of("result", result));
        return size == null || size > CLEARED_RESULT_SIZE;
    }

    private static ErrorInfo withoutCause(ErrorInfo error) {
        if (error == null || error.cause() == null) {
            return error;
        }
        return new ErrorInfo(error.name(), error.message());
    }

    /**
     * The smallest value in {@code lo..hi} for which {@code fits} is true, found by binary search, or -1 when the
     * search finds none. The search assumes that {@code fits} stays true for larger values. When it does not, the
     * result is still a value for which {@code fits} returned true, but maybe not the smallest.
     */
    private static int smallestFitting(int lo, int hi, IntPredicate fits) {
        int none = hi + 1;
        int high = none;
        while (lo < high) {
            int mid = (lo + high) >>> 1;
            if (fits.test(mid)) {
                high = mid;
            } else {
                lo = mid + 1;
            }
        }
        return high == none ? -1 : high;
    }

    private static boolean fits(
            Function<DurableExecutionTelemetryRecord, Integer> size,
            int maxBytes,
            DurableExecutionTelemetryRecord candidate) {
        Integer measured = size.apply(candidate);
        return measured != null && measured <= maxBytes;
    }

    /** {@code ops} with the results of the operations {@code withResult[0..count)} dropped and marked truncated. */
    private static List<OperationRecord> withoutResults(List<OperationRecord> ops, int[] withResult, int count) {
        List<OperationRecord> out = new ArrayList<>(ops);
        for (int i = 0; i < count; i++) {
            out.set(withResult[i], ops.get(withResult[i]).copy().result(null).truncated(true));
        }
        return out;
    }

    /** Which operations are kept after dropping the first {@code count} operations in {@code order}. */
    private static boolean[] keptAfterDropping(int[] order, int count) {
        boolean[] kept = new boolean[order.length];
        Arrays.fill(kept, true);
        for (int i = 0; i < count; i++) {
            kept[order[i]] = false;
        }
        return kept;
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
