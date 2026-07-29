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
 * SerDes for {@link DagResult} that (de)serializes the aggregate to the single cross-language DAG container envelope
 * ({@link SerializedDagResult}): {@code type}, counts, {@code completionReason}, {@code startedTaskNames},
 * {@code failedTaskNames}, and an optional {@code tasks} list. The inline case carries {@code tasks}; the offloaded
 * case drops {@code tasks} (see {@link #offloadPayloads}) so the console still renders the aggregate summary while the
 * per-task detail lives in the retained child operations.
 *
 * <p>On restore, {@code batch} results rehydrate to {@link MapResult}, nested {@code dag} results recurse, and
 * {@code plain} results rehydrate to their task's <em>declared</em> type recovered by name from {@link DagResultTypes}
 * — never from a class name persisted in the checkpoint (so there is no {@code Class.forName} on untrusted input, and
 * generic element types survive). An unknown/undeclared task falls back to a generic JSON tree.
 */
public final class DagResultSerDes implements SerDes {

    private final SerDes delegate;
    private final DagResultTypes types;

    public DagResultSerDes(SerDes delegate) {
        this(delegate, DagResultTypes.empty());
    }

    public DagResultSerDes(SerDes delegate, DagResultTypes types) {
        this.delegate = delegate;
        this.types = types;
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof DagResult dr) {
            return delegate.serialize(toEnvelope(dr, true, true));
        }
        // Not a DAG aggregate. The DAG runs inside a child context whose result SerDes is this instance, so the same
        // SerDes is also asked to serialize a Throwable when that child context fails (e.g. a throwing runIf surfacing
        // as DagPredicateException). Delegate verbatim rather than casting to DagResult — the DAG's aggregate shaping
        // must never corrupt error serialization.
        return delegate.serialize(value);
    }

    /**
     * The ordered degradation ladder for a DAG aggregate that does not fit as the full inline envelope, largest first.
     * Both candidates drop {@code tasks} (the per-task detail is preserved in the retained child operations via
     * {@code ReplayChildren}); the second additionally drops {@code failedTaskNames}. Counts, {@code completionReason}
     * and {@code startedTaskNames} are never dropped, so a DAG can never fail to checkpoint because its own summary did
     * not fit. See {@code ChildContextOperation}'s size branch, which selects the first candidate that fits.
     */
    public List<String> offloadPayloads(DagResult dr) {
        return List.of(
                delegate.serialize(toEnvelope(dr, false, true)), delegate.serialize(toEnvelope(dr, false, false)));
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        // Only the DAG aggregate goes through the envelope shape; anything else (notably a Throwable being
        // reconstructed for a failed child context) delegates so the typed exception survives the round-trip.
        boolean isDagResult = typeToken.getType() instanceof Class<?> c && DagResult.class.isAssignableFrom(c);
        if (!isDagResult) {
            return delegate.deserialize(data, typeToken);
        }
        var s = delegate.deserialize(data, TypeToken.get(SerializedDagResult.class));
        @SuppressWarnings("unchecked")
        T result = (T) fromEnvelope(s, types);
        return result;
    }

    private SerializedDagResult toEnvelope(DagResult dr, boolean includeTasks, boolean includeFailedTaskNames) {
        List<String> failedTaskNames = includeFailedTaskNames
                ? dr.failed().stream().map(TaskExecution::name).toList()
                : null;
        List<SerializedTaskExecution> tasks = null;
        if (includeTasks) {
            tasks = new ArrayList<>();
            for (var te : dr.results().values()) {
                tasks.add(toSerializedTask(te));
            }
        }
        return new SerializedDagResult(
                SerializedDagResult.TYPE,
                dr.totalCount(),
                dr.successCount(),
                dr.failureCount(),
                dr.skippedCount(),
                dr.completionReason(),
                List.copyOf(dr.startedTaskNames()),
                failedTaskNames,
                tasks);
    }

    private SerializedTaskExecution toSerializedTask(TaskExecution<?> te) {
        Object resultObj = te.result().orElse(null);
        SerializedResultKind kind;
        Object serResult;
        if (resultObj instanceof MapResult<?>) {
            kind = SerializedResultKind.BATCH;
            serResult = resultObj;
        } else if (resultObj instanceof DagResult nested) {
            kind = SerializedResultKind.DAG;
            serResult = toEnvelope(nested, true, true);
        } else {
            kind = SerializedResultKind.PLAIN;
            serResult = resultObj;
        }
        // resultKind describes how to interpret `result`, so it is null when there is no
        // result to interpret: a FAILED or SKIPPED task carries null for both. All four
        // SDKs agree on this (envelope contract rule 1, explicit nulls).
        if (te.status() != TaskStatus.SUCCEEDED) {
            kind = null;
        }
        return new SerializedTaskExecution(
                te.name(),
                te.status(),
                te.skipReason().orElse(null),
                kind,
                serResult,
                te.error().orElse(null),
                te.startedAt().map(Instant::toString).orElse(null),
                te.completedAt().map(Instant::toString).orElse(null));
    }

    private DagResultImpl fromEnvelope(SerializedDagResult s, DagResultTypes scope) {
        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        // NOTE ON REACHABILITY: `tasks() == null` is the offloaded-envelope case (see `offloadPayloads`), which pairs
        // with `ReplayChildren=true` on the checkpoint. `ChildContextOperation.replay()` always re-executes the child
        // body (re-deriving a fully-populated DagResultImpl in-memory) BEFORE `get()` could ever be called when
        // `replayChildren` is true, so `get()`'s stored-envelope fallback — the only real caller of `deserialize()`,
        // and therefore of this method — is currently unreachable for `tasks() == null` via the production replay
        // path. This branch is kept anyway: it is exercised directly by `DagEnvelopeConvergenceTest`/`DagResultTest`
        // to pin the offloaded envelope's shape and contract rule 1 (see below), and it keeps this method correct and
        // symmetric with `toEnvelope`'s two candidate shapes should a future caller ever deserialize a stored
        // offloaded envelope directly (e.g. tooling, migration, or a change to the replay strategy above).
        List<SerializedTaskExecution> tasks = s.tasks() == null ? List.of() : s.tasks();
        for (var ste : tasks) {
            Optional<Object> result = Optional.empty();
            if (ste.status() == TaskStatus.SUCCEEDED) {
                result = Optional.ofNullable(rehydrate(ste.resultKind(), ste.result(), ste.name(), scope));
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
        List<String> startedTaskNames = s.startedTaskNames() == null ? List.of() : s.startedTaskNames();
        // Preserve the aggregate counts carried by the envelope rather than re-deriving them from `results`. In the
        // inline case the two agree (the map is fully populated). In the offloaded case `tasks` was dropped so
        // `results`
        // is empty, but the envelope still carries the counts — and contract rule 1 requires that restoring a
        // tasks-less envelope preserve totalCount, the three counts and completionReason. Deriving from the (empty) map
        // would fabricate zeroed counts and could report a failed DAG as having zero failures.
        return new DagResultImpl(
                results,
                s.completionReason(),
                s.totalCount(),
                startedTaskNames,
                s.successCount(),
                s.failureCount(),
                s.skippedCount());
    }

    private Object rehydrate(SerializedResultKind kind, Object raw, String taskName, DagResultTypes scope) {
        if (raw == null) {
            return null;
        }
        return switch (kind) {
            case PLAIN -> rehydratePlain(raw, taskName, scope);
            case BATCH -> delegate.deserialize(delegate.serialize(raw), TypeToken.get(MapResult.class));
            case DAG ->
                fromEnvelope(
                        delegate.deserialize(delegate.serialize(raw), TypeToken.get(SerializedDagResult.class)),
                        scope.nestedScope(taskName));
        };
    }

    /**
     * Rehydrates a PLAIN result to the task's declared type when that task is known in this scope, so POJO / record /
     * collection results survive replay of a small completed DAG rather than degrading to a generic JSON tree. The type
     * is recovered from the registered graph by task name (never from a checkpoint-stored class name); an unknown task,
     * or any (de)serialization failure, falls back to the raw parsed tree.
     */
    private Object rehydratePlain(Object raw, String taskName, DagResultTypes scope) {
        var declared = scope.plainType(taskName);
        if (declared.isEmpty()) {
            return raw;
        }
        try {
            return delegate.deserialize(delegate.serialize(raw), declared.get());
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
