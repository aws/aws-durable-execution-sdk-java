// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.net.UnknownHostException;
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
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** A checkpoint call that gets no response from the service ends the invocation as retryable. */
class CheckpointClientErrorIntegrationTest {
    private static final String EXECUTION_OP_ID = "01234567-0123-0123-0123-012345678901";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/" + EXECUTION_OP_ID;

    @Test
    void dnsFailureOnStepSucceedCheckpointRetriesTheInvocation() {
        var failNextSucceed = new AtomicBoolean(true);
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                var isStepSucceed = updates.stream()
                        .anyMatch(u -> u.type() == OperationType.STEP && u.action() == OperationAction.SUCCEED);
                if (isStepSucceed && failNextSucceed.getAndSet(false)) {
                    throw SdkClientException.create(
                            "Received an UnknownHostException when attempting to interact with a service.",
                            new UnknownHostException("lambda.us-east-1.amazonaws.com"));
                }
                return super.checkpoint(arn, token, updates);
            }
        };
        var retryDecisions = new AtomicInteger();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo end) {
                        endInfo.set(end);
                    }
                })
                .build();
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> ctx.step(
                "work",
                String.class,
                stepCtx -> "done",
                StepConfig.builder()
                        .retryStrategy((error, attempt) -> {
                            retryDecisions.incrementAndGet();
                            return RetryDecision.fail();
                        })
                        .build());

        var failure = assertThrows(
                UnrecoverableDurableExecutionException.class,
                () -> DurableExecutor.execute(input(List.of()), null, TypeToken.get(String.class), handler, config));

        assertTrue(failure.isRetryable());
        assertInstanceOf(SdkClientException.class, failure.getCause());
        assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
        assertEquals(0, retryDecisions.get());
        assertTrue(client.getOperationUpdates().stream()
                .noneMatch(u -> u.action() == OperationAction.RETRY || u.action() == OperationAction.FAIL));

        var output = DurableExecutor.execute(
                input(client.getAllOperations()), null, TypeToken.get(String.class), handler, config);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(InvocationStatus.SUCCEEDED, endInfo.get().invocationStatus());
        assertEquals(0, retryDecisions.get());
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
}
