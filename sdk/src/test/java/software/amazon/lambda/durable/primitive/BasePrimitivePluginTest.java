// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.OperationInfo;

/**
 * Unit tests verifying that BasePrimitive.execute() fires onOperationStart with isReplay=true for all non-terminal
 * operations during replay, regardless of operation type.
 *
 * <p>This mirrors the Python SDK's TestPluginExecutorOnOperationReplay tests.
 */
class BasePrimitivePluginTest {

    private static final String EXECUTION_OP_ID = "exec-123";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/exec-name/" + EXECUTION_OP_ID;
    private static final String OPERATION_ID = TestUtils.hashOperationId("1");
    private static final String OPERATION_NAME = "test-op";

    @Test
    void execute_firesOnOperationStart_withIsReplayTrue_forNonTerminalWait() {
        var plugin = new RecordingPlugin();
        var waitOp = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.WAIT)
                .subType("Wait")
                .status(OperationStatus.STARTED)
                .build();

        var executionManager = createExecutionManager(List.of(waitOp), plugin);
        var durableContext = mockDurableContext(executionManager, plugin);

        var operation = new WaitPrimitive(
                PrimitiveOperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.WAIT),
                Duration.ofMinutes(5),
                durableContext);

        operation.execute();

        // onOperationStart should fire with isReplay=true for the non-terminal WAIT
        assertEquals(1, plugin.operationStarts.size(), "Should fire exactly one onOperationStart");
        var info = plugin.operationStarts.get(0);
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("WAIT", info.type());
        assertTrue(info.isReplay(), "isReplay should be true for a replayed non-terminal wait");
    }

    @ParameterizedTest
    @EnumSource(
            value = OperationStatus.class,
            names = {"SUCCEEDED", "FAILED", "TIMED_OUT", "STOPPED"})
    void execute_doesNotFireOnOperationStart_forTerminalOperation(OperationStatus terminalStatus) {
        var plugin = new RecordingPlugin();
        var waitOp = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.WAIT)
                .subType("Wait")
                .status(terminalStatus)
                .build();

        var executionManager = createExecutionManager(List.of(waitOp), plugin);
        var durableContext = mockDurableContext(executionManager, plugin);

        var operation = new WaitPrimitive(
                PrimitiveOperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.WAIT),
                Duration.ofMinutes(5),
                durableContext);

        operation.execute();

        // onOperationStart should NOT fire for terminal operations during replay
        assertTrue(
                plugin.operationStarts.isEmpty(),
                "Should not fire onOperationStart for terminal " + terminalStatus + " operation");
    }

    @Test
    void execute_firesOnOperationStart_withIsReplayFalse_forFirstExecution() {
        var plugin = new RecordingPlugin();
        // No existing operations — first execution
        var executionManager = createExecutionManager(List.of(), plugin);
        var durableContext = mockDurableContext(executionManager, plugin);

        var operation = new WaitPrimitive(
                PrimitiveOperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.WAIT),
                Duration.ofMinutes(5),
                durableContext);

        operation.execute();

        // onOperationStart should fire with isReplay=false on first execution
        assertEquals(1, plugin.operationStarts.size(), "Should fire exactly one onOperationStart");
        var info = plugin.operationStarts.get(0);
        assertEquals(OPERATION_NAME, info.name());
        assertFalse(info.isReplay(), "isReplay should be false on first execution");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private ExecutionManager createExecutionManager(List<Operation> additionalOps, RecordingPlugin plugin) {
        var client = TestUtils.createMockClient();
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build());
        operations.addAll(additionalOps);
        var initialState =
                CheckpointUpdatedExecutionState.builder().operations(operations).build();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(plugin)
                .build();
        var executionManager = new ExecutionManager(
                new DurableExecutionInput(EXECUTION_ARN, "test-token", initialState), config, null);
        executionManager.setCurrentThreadContext(new ThreadContext("Root", ThreadType.CONTEXT));
        return executionManager;
    }

    private DurableContextImpl mockDurableContext(ExecutionManager executionManager, RecordingPlugin plugin) {
        var durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder().withPlugins(plugin).build());
        return durableContext;
    }

    /** Plugin that records onOperationStart calls. */
    private static class RecordingPlugin implements DurableExecutionPlugin {
        final List<OperationInfo> operationStarts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onOperationStart(OperationInfo info) {
            operationStarts.add(info);
        }
    }
}
