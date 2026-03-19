// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.DurableExecutionInput;

class DurableContextImplTest {

    private static final String INVOCATION_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String EXECUTION_NAME = "349beff4-a89d-4bc8-a56f-af7a8af67a5f";

    private ExecutionManager executionManager;
    private DurableContextImpl rootContext;

    @BeforeEach
    void setUp() {
        var executionOp = Operation.builder()
                .id(INVOCATION_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        var client = TestUtils.createMockClient();
        var initialState = CheckpointUpdatedExecutionState.builder()
                .operations(new ArrayList<>(List.of(executionOp)))
                .build();
        executionManager = new ExecutionManager(
                new DurableExecutionInput(
                        "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/"
                                + EXECUTION_NAME + "/" + INVOCATION_ID,
                        "test-token",
                        initialState),
                DurableConfig.builder().withDurableExecutionClient(client).build());
        // Simulate the root thread context as the executor would set it
        executionManager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
        rootContext = DurableContextImpl.createRootContext(
                executionManager, DurableConfig.builder().build(), null);
    }

    @Test
    void createChildContext_setsThreadContextToChild() {
        rootContext.createChildContext("child-1", "my-child");

        var threadContext = executionManager.getCurrentThreadContext();
        assertNotNull(threadContext);
        assertEquals("child-1", threadContext.threadId());
        assertEquals(ThreadType.CONTEXT, threadContext.threadType());
    }

    @Test
    void createChildContextWithoutSettingThreadContext_preservesCallerThreadContext() {
        var callerContext = new ThreadContext("caller-thread", ThreadType.CONTEXT);
        executionManager.setCurrentThreadContext(callerContext);

        rootContext.createChildContextWithoutSettingThreadContext("child-1", "my-child");

        // Thread context must remain unchanged
        var threadContext = executionManager.getCurrentThreadContext();
        assertEquals("caller-thread", threadContext.threadId());
    }

    @Test
    void createChildContextWithoutSettingThreadContext_returnsCorrectChildMetadata() {
        var child = rootContext.createChildContextWithoutSettingThreadContext("child-42", "child-name");

        assertEquals("child-42", child.getContextId());
        assertEquals("child-name", child.getContextName());
    }

    @Test
    void createChildContextWithoutSettingThreadContext_whenNoThreadContextSet_leavesItNull() {
        // Clear any existing thread context
        executionManager.setCurrentThreadContext(null);

        rootContext.createChildContextWithoutSettingThreadContext("child-1", "my-child");

        assertNull(executionManager.getCurrentThreadContext());
    }
}
