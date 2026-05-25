// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestContext;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;

class DurableLoggerTest {
    private static final String EXECUTION_NAME = "exec-123";
    private static final String EXECUTION_OP_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;
    private static final String REQUEST_ID = "req-456";

    private enum Mode {
        REPLAYING,
        EXECUTING
    }

    private enum Suppression {
        ENABLED,
        DISABLED
    }

    private Logger mockLogger;
    private ExecutionManager mockExecutionManager;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        mockExecutionManager = mock(ExecutionManager.class);
        when(mockExecutionManager.getDurableExecutionArn()).thenReturn(EXECUTION_ARN);
    }

    private DurableLogger createLogger(Mode mode, Suppression suppression) {
        when(mockExecutionManager.hasOperation(anyString())).thenReturn(mode == Mode.REPLAYING);
        return new DurableLogger(mockLogger, createDurableContext(REQUEST_ID, suppression));
    }

    private DurableContextImpl createDurableContext(String requestId, Suppression suppression) {
        return DurableContextImpl.createRootContext(
                mockExecutionManager,
                DurableConfig.builder()
                        .withLoggerConfig(new LoggerConfig(suppression == Suppression.ENABLED))
                        .build(),
                new TestContext(requestId));
    }

    @Test
    void logsWhenNotReplaying() {
        var logger = createLogger(Mode.EXECUTING, Suppression.ENABLED);

        logger.info("test message");

        verify(mockLogger).info(eq("test message"), any(Object[].class));
    }

    @Test
    void suppressesLogsWhenReplayingAndSuppressionEnabled() {
        var logger = createLogger(Mode.REPLAYING, Suppression.ENABLED);

        logger.trace("suppressed");
        logger.info("should be suppressed");
        logger.debug("also suppressed");
        logger.warn("suppressed too");
        logger.error("even errors suppressed");

        verify(mockLogger, never()).trace(anyString(), any(Object[].class));
        verify(mockLogger, never()).info(anyString(), any(Object[].class));
        verify(mockLogger, never()).debug(anyString(), any(Object[].class));
        verify(mockLogger, never()).warn(anyString(), any(Object[].class));
        verify(mockLogger, never()).error(anyString(), any(Object[].class));
    }

    @Test
    void logsWhenReplayingButSuppressionDisabled() {
        var logger = createLogger(Mode.REPLAYING, Suppression.DISABLED);

        logger.info("should log during replay");

        verify(mockLogger).info(eq("should log during replay"), any(Object[].class));
    }

    @Test
    void setsExecutionMdcInConstructor() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            createLogger(Mode.EXECUTING, Suppression.ENABLED);

            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_EXECUTION_ARN, EXECUTION_ARN));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_REQUEST_ID, REQUEST_ID));
        }
    }

    @Test
    void setStepThreadPropertiesSetsMdc() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            mdcMock.clearInvocations();
            when(mockExecutionManager.hasOperation(anyString())).thenReturn(false);
            var logger = new DurableLogger(
                    mockLogger,
                    createDurableContext(REQUEST_ID, Suppression.ENABLED)
                            .createStepContext("op-1", "validateOrder", 2));

            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_OPERATION_ID, "op-1"));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_OPERATION_NAME, "validateOrder"));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_ATTEMPT, "2"));
        }
    }

    @Test
    void clearThreadPropertiesRemovesMdc() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            var logger = createLogger(Mode.EXECUTING, Suppression.ENABLED);
            mdcMock.clearInvocations();

            logger.close();

            mdcMock.verify(() -> MDC.clear());
        }
    }

    @Test
    void replayModeTransitionAllowsSubsequentLogs() {
        when(mockExecutionManager.hasOperation(anyString())).thenReturn(true);
        var durableContext = createDurableContext(REQUEST_ID, Suppression.ENABLED);
        var logger = new DurableLogger(mockLogger, durableContext);

        // During replay - suppressed
        logger.info("suppressed");
        verify(mockLogger, never()).info(anyString(), any(Object[].class));

        // Simulate next operation not existing in storage — triggers transition out of replay
        when(mockExecutionManager.hasOperation(anyString())).thenReturn(false);
        durableContext.updateReplayStatus();

        // After transition to execution mode - logged
        logger.info("logged after transition");
        verify(mockLogger).info(eq("logged after transition"), any(Object[].class));
    }

    @Test
    void allLogLevelsDelegateCorrectly() {
        var logger = createLogger(Mode.EXECUTING, Suppression.ENABLED);

        logger.trace("trace msg");
        logger.debug("debug msg");
        logger.info("info msg");
        logger.warn("warn msg");
        logger.error("error msg");

        var exception = new RuntimeException("test");
        logger.error("error with exception", exception);

        verify(mockLogger).trace(eq("trace msg"), any(Object[].class));
        verify(mockLogger).debug(eq("debug msg"), any(Object[].class));
        verify(mockLogger).info(eq("info msg"), any(Object[].class));
        verify(mockLogger).warn(eq("warn msg"), any(Object[].class));
        verify(mockLogger).error(eq("error msg"), any(Object[].class));
        verify(mockLogger).error("error with exception", exception);
    }

    @Test
    void concurrentContextsHaveIndependentReplayState() {
        var rootNextOp = OperationIdGenerator.hashOperationId("1");
        var childANextOp = OperationIdGenerator.hashOperationId("child-a-1");
        var childBNextOp = OperationIdGenerator.hashOperationId("child-b-1");

        when(mockExecutionManager.hasOperation(rootNextOp)).thenReturn(true);
        when(mockExecutionManager.hasOperation(childANextOp)).thenReturn(false);
        when(mockExecutionManager.hasOperation(childBNextOp)).thenReturn(true);

        var rootContext = createDurableContext(REQUEST_ID, Suppression.ENABLED);
        var childA = rootContext.createChildContext("child-a", "branch-a", false);
        var childB = rootContext.createChildContext("child-b", "branch-b", false);

        var loggerForA = mock(Logger.class);
        var loggerForB = mock(Logger.class);
        var durableLoggerA = new DurableLogger(loggerForA, childA);
        var durableLoggerB = new DurableLogger(loggerForB, childB);

        // Child A is in execution mode — logs should pass through
        durableLoggerA.info("from branch A");
        verify(loggerForA).info(eq("from branch A"), any(Object[].class));

        // Child B is still replaying — logs should be suppressed
        durableLoggerB.info("from branch B");
        verify(loggerForB, never()).info(anyString(), any(Object[].class));

        // After child B transitions, its logs should pass through
        when(mockExecutionManager.hasOperation(childBNextOp)).thenReturn(false);
        childB.updateReplayStatus();
        durableLoggerB.info("branch B after transition");
        verify(loggerForB).info(eq("branch B after transition"), any(Object[].class));
    }

    @Test
    void handlesNullRequestId() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            when(mockExecutionManager.hasOperation(anyString())).thenReturn(false);
            new DurableLogger(mockLogger, createDurableContext(null, Suppression.DISABLED));

            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_EXECUTION_ARN, EXECUTION_ARN));
            mdcMock.verify(() -> MDC.put(eq(DurableLogger.MDC_REQUEST_ID), anyString()), never());
        }
    }
}
