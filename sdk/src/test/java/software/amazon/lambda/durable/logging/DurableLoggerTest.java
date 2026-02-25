// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.MDC;
import software.amazon.lambda.durable.execution.ExecutionManager;

class DurableLoggerTest {

    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:exec-123";
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
        when(mockExecutionManager.isReplaying()).thenReturn(mode == Mode.REPLAYING);
        return new DurableLogger(mockLogger, mockExecutionManager, REQUEST_ID, suppression == Suppression.ENABLED);
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
    void setOperationContextSetsMdc() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            var logger = createLogger(Mode.EXECUTING, Suppression.ENABLED);
            mdcMock.clearInvocations();

            logger.setOperationContext("op-1", "validateOrder", 2);

            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_OPERATION_ID, "op-1"));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_OPERATION_NAME, "validateOrder"));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_ATTEMPT, "2"));
        }
    }

    @Test
    void clearOperationContextRemovesMdc() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            var logger = createLogger(Mode.EXECUTING, Suppression.ENABLED);
            mdcMock.clearInvocations();

            logger.clearOperationContext();

            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_OPERATION_ID));
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_OPERATION_NAME));
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_ATTEMPT));
        }
    }

    @Test
    void replayModeTransitionAllowsSubsequentLogs() {
        when(mockExecutionManager.isReplaying()).thenReturn(true, false);
        var logger = new DurableLogger(mockLogger, mockExecutionManager, REQUEST_ID, true);

        // During replay - suppressed
        logger.info("suppressed");
        verify(mockLogger, never()).info(anyString(), any(Object[].class));

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
    void handlesNullRequestId() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            when(mockExecutionManager.isReplaying()).thenReturn(false);
            new DurableLogger(mockLogger, mockExecutionManager, null, true);

            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_EXECUTION_ARN, EXECUTION_ARN));
            mdcMock.verify(() -> MDC.put(eq(DurableLogger.MDC_REQUEST_ID), anyString()), never());
        }
    }
}
