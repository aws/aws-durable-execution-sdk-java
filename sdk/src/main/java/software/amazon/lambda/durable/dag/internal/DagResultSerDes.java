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

    public DagResultSerDes(SerDes delegate) {
        this.delegate = delegate;
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        return delegate.serialize(toSerialized((DagResult) value));
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
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
     */
    private Object rehydratePlain(Object raw, String resultType) {
        if (resultType == null) {
            return raw;
        }
        try {
            Class<?> cls = Class.forName(resultType);
            return delegate.deserialize(delegate.serialize(raw), TypeToken.get(cls));
        } catch (ClassNotFoundException | RuntimeException e) {
            return raw;
        }
    }
}
