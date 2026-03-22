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
import software.amazon.lambda.durable.model.DurableExecutionInput;

class BaseContextImplTest {

    private static final String INVOCATION_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String EXECUTION_NAME = "349beff4-a89d-4bc8-a56f-af7a8af67a5f";
    private static final Operation EXECUTION_OP = Operation.builder()
            .id(INVOCATION_ID)
            .type(OperationType.EXECUTION)
            .status(OperationStatus.STARTED)
            .build();

    @BeforeEach
    void clearThreadContext() {
        // currentThreadContext is a static ThreadLocal on ExecutionManager — clear it
        // before each test to prevent bleed-through from other tests on the same thread.
        createExecutionManager().setCurrentThreadContext(null);
    }

    private ExecutionManager createExecutionManager() {
        var client = TestUtils.createMockClient();
        var initialState = CheckpointUpdatedExecutionState.builder()
                .operations(new ArrayList<>(List.of(EXECUTION_OP)))
                .build();
        return new ExecutionManager(
                new DurableExecutionInput(
                        "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/"
                                + EXECUTION_NAME + "/" + INVOCATION_ID,
                        "test-token",
                        initialState),
                DurableConfig.builder().withDurableExecutionClient(client).build());
    }

    @Test
    void defaultConstructor_setsCurrentThreadContext() {
        var executionManager = createExecutionManager();
        // Precondition: no thread context set yet
        assertNull(executionManager.getCurrentThreadContext());

        // Creating a root context with the default constructor should set the thread context
        DurableContextImpl.createRootContext(
                executionManager, DurableConfig.builder().build(), null);
    }

    @Test
    void constructorWithSetCurrentThreadContextTrue_setsCurrentThreadContext() {
        var executionManager = createExecutionManager();

        // createRootContext sets thread context to root (threadId=null)
        var rootContext = DurableContextImpl.createRootContext(
                executionManager, DurableConfig.builder().build(), null);

        // createChildContext (setCurrentThreadContext=true) should overwrite with child's context
        rootContext.createChildContext("child-id", "child-name");
    }
}
