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
 * <p>Note: {@code PLAIN} results rehydrate as generic JSON trees (type erasure). For precise typed reconstruction of
 * arbitrary POJO results, the DAG's native child-context re-execution path (used for large aggregates) re-runs the
 * scheduler so every task returns its own correctly-typed checkpointed result via the per-task fast path.
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
            if (resultObj instanceof MapResult<?>) {
                kind = SerializedResultKind.MAP;
                serResult = resultObj;
            } else if (resultObj instanceof DagResult nested) {
                kind = SerializedResultKind.DAG;
                serResult = toSerialized(nested);
            } else {
                kind = SerializedResultKind.PLAIN;
                serResult = resultObj;
            }
            tasks.add(new SerializedTaskExecution(
                    te.name(),
                    te.status(),
                    te.skipReason().orElse(null),
                    kind,
                    serResult,
                    te.error().orElse(null),
                    te.startedAt().map(Instant::toString).orElse(null),
                    te.completedAt().map(Instant::toString).orElse(null)));
        }
        return new SerializedDagResult(tasks, dr.completionReason());
    }

    private DagResultImpl fromSerialized(SerializedDagResult s) {
        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        for (var ste : s.tasks()) {
            Optional<Object> result = Optional.empty();
            if (ste.status() == TaskStatus.SUCCEEDED) {
                result = Optional.ofNullable(rehydrate(ste.resultKind(), ste.result()));
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
        return new DagResultImpl(results, s.completionReason());
    }

    private Object rehydrate(SerializedResultKind kind, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (kind) {
            case PLAIN -> raw;
            case MAP -> delegate.deserialize(delegate.serialize(raw), TypeToken.get(MapResult.class));
            case DAG ->
                fromSerialized(delegate.deserialize(delegate.serialize(raw), TypeToken.get(SerializedDagResult.class)));
        };
    }
}
