// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** A checkpoint call that fails with a client-side error ends the invocation as retryable. */
class CheckpointClientErrorIntegrationTest {
    private static final String EXECUTION_OP_ID = "01234567-0123-0123-0123-012345678901";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/" + EXECUTION_OP_ID;

    private final AtomicInteger retryDecisions = new AtomicInteger();
    private final AtomicReference<InvocationEndInfo> endInfo = new AtomicReference<>();

    @Test
    void dnsFailureOnStepSucceedCheckpointRetriesTheInvocation() {
        var client = new FailingClient(OperationType.STEP, OperationAction.SUCCEED);
        BiFunction<String, DurableContext, String> handler =
                (input, ctx) -> ctx.step("work", String.class, stepCtx -> "done", stepConfig(RetryDecision.fail()));

        assertInvocationRetries(client, handler);
        assertEquals(0, retryDecisions.get());
        assertNoUpdateAfterFailedCheckpoint(client);

        var output = DurableExecutor.execute(
                input(client.getAllOperations()), null, TypeToken.get(String.class), handler, config(client));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(InvocationStatus.SUCCEEDED, endInfo.get().invocationStatus());
        assertEquals(0, retryDecisions.get());
    }

    @Test
    void dnsFailureOnStepRetryCheckpointRetriesTheInvocation() {
        var client = new FailingClient(OperationType.STEP, OperationAction.RETRY);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> ctx.step(
                "work",
                String.class,
                stepCtx -> {
                    throw new IllegalStateException("step failed");
                },
                stepConfig(RetryDecision.retry(Duration.ofSeconds(1))));

        assertInvocationRetries(client, handler);
        assertEquals(1, retryDecisions.get());
        assertNoUpdateAfterFailedCheckpoint(client);
    }

    @Test
    void dnsFailureOnStepFailCheckpointRetriesTheInvocation() {
        var client = new FailingClient(OperationType.STEP, OperationAction.FAIL);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> ctx.step(
                "work",
                String.class,
                stepCtx -> {
                    throw new IllegalStateException("step failed");
                },
                stepConfig(RetryDecision.fail()));

        assertInvocationRetries(client, handler);
        assertEquals(1, retryDecisions.get());
        assertNoUpdateAfterFailedCheckpoint(client);
    }

    @Test
    void dnsFailureOnChildContextFailCheckpointRetriesTheInvocation() {
        var client = new FailingClient(OperationType.CONTEXT, OperationAction.FAIL);
        BiFunction<String, DurableContext, String> handler =
                (input, ctx) -> ctx.runInChildContext("child", String.class, child -> {
                    throw new IllegalStateException("child failed");
                });

        assertInvocationRetries(client, handler);
        assertNoUpdateAfterFailedCheckpoint(client);
    }

    private void assertInvocationRetries(FailingClient client, BiFunction<String, DurableContext, String> handler) {
        var failure = assertThrows(
                UnrecoverableDurableExecutionException.class,
                () -> DurableExecutor.execute(
                        input(List.of()), null, TypeToken.get(String.class), handler, config(client)));

        assertTrue(failure.isRetryable());
        assertInstanceOf(SdkClientException.class, failure.getCause());
        assertTrue(client.failed.get());
        assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
    }

    /** Every update the client accepted was sent before the failed one, so nothing reacted to the failure. */
    private static void assertNoUpdateAfterFailedCheckpoint(FailingClient client) {
        assertTrue(client.getOperationUpdates().stream()
                .noneMatch(u -> u.type() == client.type && u.action() == client.action));
        assertEquals(
                client.updatesBeforeFailure.get(), client.getOperationUpdates().size());
    }

    private StepConfig stepConfig(RetryDecision decision) {
        RetryStrategy strategy = (error, attempt) -> {
            retryDecisions.incrementAndGet();
            return decision;
        };
        return StepConfig.builder().retryStrategy(strategy).build();
    }

    private DurableConfig config(LocalMemoryExecutionClient client) {
        return DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo end) {
                        endInfo.set(end);
                    }
                })
                .build();
    }

    private static DurableExecutionInput input(List<Operation> recorded) {
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build());
        recorded.stream().filter(op -> op.type() != OperationType.EXECUTION).forEach(operations::add);
        return new DurableExecutionInput(
                EXECUTION_ARN,
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operations).build());
    }

    /** Throws a DNS failure once, on the first checkpoint that carries the given update. */
    private static final class FailingClient extends LocalMemoryExecutionClient {
        private final OperationType type;
        private final OperationAction action;
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicInteger updatesBeforeFailure = new AtomicInteger(-1);

        FailingClient(OperationType type, OperationAction action) {
            this.type = type;
            this.action = action;
        }

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            var matches = updates.stream().anyMatch(u -> u.type() == type && u.action() == action);
            if (matches && failed.compareAndSet(false, true)) {
                updatesBeforeFailure.set(getOperationUpdates().size());
                throw SdkClientException.create(
                        "Received an UnknownHostException when attempting to interact with a service.",
                        new UnknownHostException("lambda.us-east-1.amazonaws.com"));
            }
            return super.checkpoint(arn, token, updates);
        }
    }
}
