// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.DistributedMapDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.DistributedMapConfig;
import software.amazon.lambda.durable.config.DistributedMapProcessor;
import software.amazon.lambda.durable.config.DistributedMapSource;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.DistributedMapResult;
import software.amazon.lambda.durable.model.DistributedMapSummary;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Exercises the ctx.distributedMap entrypoint overloads on DurableContextImpl. */
class DurableContextDistributedMapTest {

    private static final String EXECUTION_NAME = "349beff4-a89d-4bc8-a56f-af7a8af67a5f";
    private static final String EXECUTION_OP_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;

    private static final Operation EXECUTION_OP = Operation.builder()
            .id(EXECUTION_OP_ID)
            .type(OperationType.EXECUTION)
            .status(OperationStatus.STARTED)
            .build();

    // The first operation created inside a context always hashes the counter value "1".
    private static final String FIRST_OP_ID = TestUtils.hashOperationId("1");

    private DurableContext createTestContext() {
        return createTestContext(List.of());
    }

    private DurableContext createTestContext(List<Operation> initialOperations) {
        var client = TestUtils.createMockClient();
        var operations = new ArrayList<>(List.of(EXECUTION_OP));
        operations.addAll(initialOperations);
        var initialExecutionState =
                CheckpointUpdatedExecutionState.builder().operations(operations).build();
        var executionManager = new ExecutionManager(
                new DurableExecutionInput(EXECUTION_ARN, "test-token", initialExecutionState),
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);
        var root = DurableContextImpl.createRootContext(
                executionManager,
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);
        executionManager.registerActiveThread(null);
        executionManager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
        return root;
    }

    private DistributedMapSource<String> source() {
        return DistributedMapSource.inline(List.of("a"));
    }

    private DistributedMapProcessor processor() {
        return DistributedMapProcessor.reportBatchOutcome("test-processor").build();
    }

    // A completed distributed map operation so a replay resolves without suspending.
    private Operation completedDmapOp() {
        var details = DistributedMapDetails.builder()
                .sourceExhausted(true)
                .successCount(1L)
                .failureCount(0L)
                .readCount(1L)
                .totalCount(1L)
                .build();
        return Operation.builder()
                .id(FIRST_OP_ID)
                .status(OperationStatus.SUCCEEDED)
                .distributedMapDetails(details)
                .build();
    }

    @Test
    void testMaxConcurrencyZeroThrows() {
        var context = createTestContext();

        var ex = assertThrows(
                IllegalArgumentException.class, () -> context.distributedMap("m", source(), processor(), 0));
        assertTrue(ex.getMessage().contains("maxConcurrency must be greater than zero"));
    }

    @Test
    void testMaxConcurrencyNegativeThrows() {
        var context = createTestContext();

        assertThrows(IllegalArgumentException.class, () -> context.distributedMap("m", source(), processor(), -1));
    }

    @Test
    void testResultSerDesOnSummaryCallThrows() {
        var context = createTestContext();
        // resultSerDes is only valid on a result-collecting overload, a summary call must reject it.
        var config =
                DistributedMapConfig.builder().resultSerDes(new JacksonSerDes()).build();

        var ex = assertThrows(
                IllegalArgumentException.class, () -> context.distributedMap("m", source(), processor(), 2, config));
        assertTrue(ex.getMessage().contains("resultSerDes"));
    }

    @Test
    void testSummaryOverloadReturnsSummary() {
        var context = createTestContext(List.of(completedDmapOp()));

        var summary = context.distributedMap("m", source(), processor(), 2);

        assertNotNull(summary);
        assertEquals(1, summary.successCount());
    }

    @Test
    void testResultOverloadReturnsResult() {
        var context = createTestContext(List.of(completedDmapOp()));

        DistributedMapResult<String> result = context.distributedMap(
                "m",
                source(),
                processor(),
                2,
                String.class,
                DistributedMapConfig.builder().build());

        assertNotNull(result);
        assertNotNull(result.items());
        assertEquals(1, result.summary().successCount());
    }

    @Test
    void testClassOverloadDelegatesToTypeToken() {
        // The Class<O> overload forwards to the TypeToken<O> overload, so both must resolve identically.
        var classContext = createTestContext(List.of(completedDmapOp()));
        DistributedMapResult<String> fromClass = classContext.distributedMap(
                "m",
                source(),
                processor(),
                2,
                String.class,
                DistributedMapConfig.builder().build());

        var tokenContext = createTestContext(List.of(completedDmapOp()));
        DistributedMapResult<String> fromToken = tokenContext.distributedMap(
                "m",
                source(),
                processor(),
                2,
                new TypeToken<String>() {},
                DistributedMapConfig.builder().build());

        assertEquals(fromToken.summary(), fromClass.summary());
        assertEquals(fromToken.items(), fromClass.items());
    }

    @Test
    void testSummaryConfigOptional() {
        var context = createTestContext(List.of(completedDmapOp()));

        // The no-config summary overload uses an empty config internally.
        DistributedMapSummary summary = context.distributedMap("m", source(), processor(), 2);

        assertNotNull(summary);
    }

    @Test
    void testResultConfigOptional() {
        var context = createTestContext(List.of(completedDmapOp()));

        // The no-config result overload uses an empty config internally.
        DistributedMapResult<String> result = context.distributedMap("m", source(), processor(), 2, String.class);

        assertNotNull(result);
        assertNotNull(result.items());
    }
}
