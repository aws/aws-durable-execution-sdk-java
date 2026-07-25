// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * SerDes for {@link DagResult} that serializes the aggregate to a {@code resultKind}-tagged JSON-safe shape
 * ({@link SerializedDagResult}) so heterogeneous, method-bearing task results survive the round-trip. On restore,
 * {@code MAP} results rehydrate to {@link MapResult} and nested {@code DAG} results recursively rehydrate to
 * {@link DagResult} instances.
 *
 * <p>{@code PLAIN} results persist their concrete runtime class name and rehydrate to that type, so POJO/record results
 * survive a small-DAG replay round-trip (top-level generic element types still erase — e.g. a {@code List<Pojo>}
 * rehydrates as {@code List} of JSON trees). If the class cannot be resolved, the result falls back to a generic JSON
 * tree. The DAG's native child-context re-execution path (used for large aggregates, §8.1) re-runs the scheduler so
 * every task returns its own correctly-typed checkpointed result via the per-task fast path.
 */
public final class DagResultSerDes implements SerDes {

    private final SerDes delegate;

    /**
     * Fully-qualified class names that {@code PLAIN} results are allowed to rehydrate into on replay — the declared
     * result types of the DAG's registered tasks (see {@code DagContextImpl.collectAllowedResultTypes()}). A checkpoint
     * is untrusted input on replay; restricting {@link #rehydratePlain} to this set means a stored type name that the
     * DAG could not legitimately have produced (unknown/tampered/gadget class) is never resolved via
     * {@code Class.forName}, and instead falls back to the generic JSON tree. Empty means "no PLAIN reconstruction"
     * (safe default for trusted/in-process construction).
     */
    private final java.util.Set<String> allowedResultTypes;

    public DagResultSerDes(SerDes delegate) {
        this(delegate, java.util.Set.of());
    }

    public DagResultSerDes(SerDes delegate, java.util.Set<String> allowedResultTypes) {
        this.delegate = delegate;
        this.allowedResultTypes = java.util.Set.copyOf(allowedResultTypes);
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof DagResult dr) {
            return delegate.serialize(toSerialized(dr));
        }
        // Not a DAG aggregate. The DAG runs inside a child context whose result SerDes is this instance, so the same
        // SerDes is also asked to serialize a Throwable when that child context fails (e.g. a throwing runIf surfacing
        // as DagPredicateException). Delegate verbatim rather than casting to DagResult — the DAG's aggregate shaping
        // must never corrupt error serialization.
        return delegate.serialize(value);
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        // Only the DAG aggregate goes through the resultKind-tagged shape; anything else (notably a Throwable being
        // reconstructed for a failed child context) delegates so the typed exception survives the round-trip.
        boolean isDagResult = typeToken.getType() instanceof Class<?> c && DagResult.class.isAssignableFrom(c);
        if (!isDagResult) {
            return delegate.deserialize(data, typeToken);
        }
        var s = delegate.deserialize(data, TypeToken.get(SerializedDagResult.class));
        @SuppressWarnings("unchecked")
        T result = (T) fromSerialized(s);
        return result;
    }

    private SerializedDagResult toSerialized(DagResult dr) {
        List<SerializedTaskExecution> tasks = new ArrayList<>();
        for (var te : dr.results().values()) {
            Object resultObj = te.result().orElse(null);
            SerializedResultKind kind;
            Object serResult;
            String resultType = null;
            if (resultObj instanceof MapResult<?>) {
                kind = SerializedResultKind.MAP;
                serResult = resultObj;
            } else if (resultObj instanceof DagResult nested) {
                kind = SerializedResultKind.DAG;
                serResult = toSerialized(nested);
            } else {
                kind = SerializedResultKind.PLAIN;
                serResult = resultObj;
                if (resultObj != null) {
                    // Persist the concrete runtime type so PLAIN POJO/record/collection results rehydrate to
                    // their declared type on replay of a small (<256KB) completed DAG, rather than degrading to a
                    // generic LinkedHashMap JSON tree (type erasure). Top-level generic element types still erase.
                    resultType = resultObj.getClass().getName();
                }
            }
            tasks.add(new SerializedTaskExecution(
                    te.name(),
                    te.status(),
                    te.skipReason().orElse(null),
                    kind,
                    serResult,
                    resultType,
                    te.error().orElse(null),
                    te.startedAt().map(Instant::toString).orElse(null),
                    te.completedAt().map(Instant::toString).orElse(null)));
        }
        return new SerializedDagResult(tasks, dr.completionReason(), dr.totalCount());
    }

    private DagResultImpl fromSerialized(SerializedDagResult s) {
        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        for (var ste : s.tasks()) {
            Optional<Object> result = Optional.empty();
            if (ste.status() == TaskStatus.SUCCEEDED) {
                result = Optional.ofNullable(rehydrate(ste.resultKind(), ste.result(), ste.resultType()));
            }
            results.put(
                    ste.name(),
                    new TaskExecution<>(
                            ste.name(),
                            ste.status(),
                            Optional.ofNullable(ste.skipReason()),
                            result,
                            Optional.ofNullable(ste.error()),
                            Optional.ofNullable(ste.startedAt()).map(Instant::parse),
                            Optional.ofNullable(ste.completedAt()).map(Instant::parse)));
        }
        return new DagResultImpl(results, s.completionReason(), s.totalCount());
    }

    private Object rehydrate(SerializedResultKind kind, Object raw, String resultType) {
        if (raw == null) {
            return null;
        }
        return switch (kind) {
            case PLAIN -> rehydratePlain(raw, resultType);
            case MAP -> delegate.deserialize(delegate.serialize(raw), TypeToken.get(MapResult.class));
            case DAG ->
                fromSerialized(delegate.deserialize(delegate.serialize(raw), TypeToken.get(SerializedDagResult.class)));
        };
    }

    /**
     * Rehydrates a PLAIN result to its persisted concrete type when known, so POJO/record/collection results survive
     * replay of a small completed DAG rather than degrading to a generic JSON tree. Falls back to the raw parsed tree
     * if the type is absent or cannot be resolved (e.g. class not on the classpath).
     *
     * <p><b>Trust boundary (H3):</b> {@code resultType} is read from a checkpoint, which is untrusted input on replay.
     * We only rehydrate into a class the DAG could legitimately have produced — one of the registered tasks' declared
     * result types ({@link #allowedResultTypes}). Any other name (unknown, tampered, or a polymorphic subtype we did
     * not record) is <em>never</em> passed to {@code Class.forName}; it falls back to the generic tree. Resolution also
     * uses {@code initialize=false} so merely resolving a class name cannot trigger an arbitrary static initializer.
     */
    private Object rehydratePlain(Object raw, String resultType) {
        if (resultType == null) {
            return raw;
        }
        if (!allowedResultTypes.contains(resultType)) {
            // Not a type this DAG could have produced: do not load it; degrade to the generic JSON tree.
            return raw;
        }
        try {
            Class<?> cls = Class.forName(resultType, false, DagResultSerDes.class.getClassLoader());
            return delegate.deserialize(delegate.serialize(raw), TypeToken.get(cls));
        } catch (ClassNotFoundException | RuntimeException e) {
            return raw;
        }
    }
}
