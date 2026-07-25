// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.dag.DagCyclicDependencyException;
import software.amazon.lambda.durable.dag.DagDuplicateTaskException;
import software.amazon.lambda.durable.dag.DagException;
import software.amazon.lambda.durable.dag.DagExecutionException;
import software.amazon.lambda.durable.dag.DagInvalidDependencyException;
import software.amazon.lambda.durable.dag.DagInvalidTaskNameException;
import software.amazon.lambda.durable.dag.DagPredicateException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/**
 * Pins how {@link ChildContextOperation#handleChildContextFailure} propagates an exception thrown inside a DAG /
 * {@code runInChildContext} body across the child-context boundary.
 *
 * <p>Background: a DAG runs as a {@code runInChildContext} node. When a task body throws, the exception is serialized
 * into the container's checkpoint {@code error} and reconstructed on the {@code dag(...)} caller side. The
 * {@code handleChildContextFailure} guard decides <b>which</b> exceptions get serialized as a plain throwable so their
 * concrete type survives, versus which fall back to a null checkpoint error (degrading to a generic
 * {@link ChildContextFailedException}).
 *
 * <p>The guard is scoped to the <b>DAG family</b> ({@code instanceof DagException}) rather than the single
 * {@link DagPredicateException} leaf. Every {@link DagException} subtype is, by construction, a
 * {@link DurableOperationException} carrying neither an {@code Operation} nor an {@code ErrorObject} (super(null, null,
 * ...)); the family base is the narrowest predicate that keeps <b>every</b> DAG-family exception's type retrievable at
 * the caller while restoring the prior (pre-DAG) degrade behaviour for non-DAG exceptions that also happen to carry a
 * null operation and null error. These tests are the regression guard for that decision — narrowing to the
 * {@code DagPredicateException} leaf would re-erase the registration and {@code throwIfError} cases pinned here.
 */
class ChildContextDagExceptionPropagationTest {

    private DurableContextImpl durableContext;
    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContextImpl.class);
        executionManager = mock(ExecutionManager.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .withDeserializeAfterSerialization(true)
                        .build());
    }

    /**
     * Runs {@code fn} as the body of a (virtual) DAG child context and returns whatever the {@code dag(...)} caller
     * observes from {@code get()}. A virtual child completes synchronously through the same serialize → checkpoint →
     * reconstruct path a real child uses, so {@code get()} throws the reconstructed exception.
     */
    private Throwable propagate(Function<DurableContext, String> fn) throws Exception {
        var op = new ChildContextOperation<>(
                OperationIdentifier.of("1", "test-context", OperationSubType.DAG),
                fn,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder()
                        .serDes(new JacksonSerDes())
                        .isVirtual(true)
                        .build(),
                durableContext);
        op.execute();
        Thread.sleep(150);
        return assertThrows(Throwable.class, op::get);
    }

    // ── DAG family: concrete type MUST survive the boundary ────────────────────

    @Test
    void predicateExceptionKeepsTypeTaskNameAndCause() throws Exception {
        var observed = propagate(ctx -> {
            throw new DagPredicateException("gate", new IllegalStateException("boom"));
        });
        var ex = assertInstanceOf(DagPredicateException.class, observed);
        assertEquals("gate", ex.taskName());
        assertTrue(ex.getMessage().contains("gate"));
        assertNotNull(ex.getCause(), "predicate cause must survive the boundary");
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    void cyclicDependencyExceptionKeepsType() throws Exception {
        var observed = propagate(ctx -> {
            throw new DagCyclicDependencyException("cycle a->b->a");
        });
        assertInstanceOf(DagCyclicDependencyException.class, observed);
        assertTrue(observed.getMessage().contains("cycle a->b->a"));
    }

    @Test
    void duplicateTaskExceptionKeepsType() throws Exception {
        var observed = propagate(ctx -> {
            throw new DagDuplicateTaskException("dup 't'");
        });
        assertInstanceOf(DagDuplicateTaskException.class, observed);
        assertTrue(observed.getMessage().contains("dup 't'"));
    }

    @Test
    void invalidTaskNameExceptionKeepsType() throws Exception {
        var observed = propagate(ctx -> {
            throw new DagInvalidTaskNameException("bad name");
        });
        assertInstanceOf(DagInvalidTaskNameException.class, observed);
        assertTrue(observed.getMessage().contains("bad name"));
    }

    @Test
    void invalidDependencyExceptionKeepsType() throws Exception {
        var observed = propagate(ctx -> {
            throw new DagInvalidDependencyException("unknown dep");
        });
        assertInstanceOf(DagInvalidDependencyException.class, observed);
        assertTrue(observed.getMessage().contains("unknown dep"));
    }

    @Test
    void executionExceptionKeepsTypeAndCause() throws Exception {
        // throwIfError() may be called inside a nested DAG task body, so DagExecutionException can cross the boundary.
        // Its cause-carrying form only reconstructs because of its @JsonCreator (mirroring DagPredicateException);
        // without it the concrete type would erase to ChildContextFailedException even though the guard serializes it.
        var observed = propagate(ctx -> {
            throw new DagExecutionException("DAG had 1 failed task", new RuntimeException("task failed"));
        });
        var ex = assertInstanceOf(DagExecutionException.class, observed);
        assertTrue(ex.getMessage().contains("DAG had 1 failed task"));
        assertNotNull(ex.getCause(), "throwIfError cause must survive the boundary");
        assertEquals("task failed", ex.getCause().getMessage());
    }

    @Test
    void executionExceptionWithoutCauseKeepsType() throws Exception {
        var observed = propagate(ctx -> {
            throw new DagExecutionException("DAG had 1 failed task");
        });
        assertInstanceOf(DagExecutionException.class, observed);
        assertTrue(observed.getMessage().contains("DAG had 1 failed task"));
    }

    @Test
    void everyDagFamilyExceptionCarriesNullOperationAndNullError() {
        // The invariant the family-scoped guard relies on: DagException(super(null, null, ...)) means the whole family
        // carries neither an Operation nor an ErrorObject, so instanceof DagException is exactly equivalent to the
        // old getOperation()==null test for DAG types — but without also capturing non-DAG null-op exceptions.
        DagException[] family = {
            new DagPredicateException("t", new RuntimeException()),
            new DagCyclicDependencyException("c"),
            new DagDuplicateTaskException("d"),
            new DagInvalidTaskNameException("n"),
            new DagInvalidDependencyException("p"),
            new DagExecutionException("e"),
        };
        for (DurableOperationException ex : family) {
            assertEquals(null, ex.getOperation(), ex.getClass().getSimpleName() + " must carry a null operation");
            assertEquals(null, ex.getErrorObject(), ex.getClass().getSimpleName() + " must carry a null errorObject");
        }
    }

    // ── Non-DAG: prior degrade behaviour MUST be restored ──────────────────────

    @Test
    void nonDagDurableOperationExceptionWithNullOpDegradesToChildContextFailed() throws Exception {
        // A non-DAG DurableOperationException that also carries a null operation and null error — e.g.
        // WaitForConditionFailedException(String). Before the narrowing this was serialized by the broad
        // getOperation()==null guard; the family-scoped guard restores its prior degrade-to-ChildContextFailed
        // behaviour. This is the anti-over-capture pin.
        var observed = propagate(ctx -> {
            throw new WaitForConditionFailedException("condition failed");
        });
        assertInstanceOf(ChildContextFailedException.class, observed);
    }

    @Test
    void plainRuntimeExceptionStillRoundTrips() throws Exception {
        // Non-DurableOperationException path is unchanged: always serialized as a plain throwable.
        var observed = propagate(ctx -> {
            throw new RuntimeException("plain boom");
        });
        assertInstanceOf(RuntimeException.class, observed);
        assertEquals("plain boom", observed.getMessage());
    }
}
