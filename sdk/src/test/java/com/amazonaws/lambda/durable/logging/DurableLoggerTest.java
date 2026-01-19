// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.logging;

import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.MDC;

class DurableLoggerTest {

    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:exec-123";
    private static final String REQUEST_ID = "req-456";

    private Logger mockLogger;
    private ExecutionManager mockExecutionManager;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        mockExecutionManager = mock(ExecutionManager.class);
        when(mockExecutionManager.getDurableExecutionArn()).thenReturn(EXECUTION_ARN);
    }

    private DurableLogger createLogger(boolean isReplaying, boolean suppressReplayLogs) {
        return createLogger(isReplaying, suppressReplayLogs, null);
    }

    private DurableLogger createLogger(boolean isReplaying, boolean suppressReplayLogs, OperationContext opCtx) {
        when(mockExecutionManager.isReplaying()).thenReturn(isReplaying);
        when(mockExecutionManager.getCurrentOperation()).thenReturn(opCtx);
        return new DurableLogger(mockLogger, mockExecutionManager, REQUEST_ID, suppressReplayLogs);
    }

    @Test
    void logsWhenNotReplaying() {
        var logger = createLogger(false, true);

        logger.info("test message");

        verify(mockLogger).info(eq("test message"), any(Object[].class));
    }

    @Test
    void suppressesLogsWhenReplayingAndSuppressionEnabled() {
        var logger = createLogger(true, true);

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
        var logger = createLogger(true, false);

        logger.info("should log during replay");

        verify(mockLogger).info(eq("should log during replay"), any(Object[].class));
    }

    @Test
    void setsMdcDuringLogAndClearsAfter() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            var opCtx = new OperationContext("1", "validateOrder", 2);
            var logger = createLogger(false, true, opCtx);

            logger.info("operation log");

            // Verify all MDC was set
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_EXECUTION_ARN, EXECUTION_ARN));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_REQUEST_ID, REQUEST_ID));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_OPERATION_ID, "1"));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_OPERATION_NAME, "validateOrder"));
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_ATTEMPT, "2"));

            // Verify all MDC was cleared after logging
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_EXECUTION_ARN));
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_REQUEST_ID));
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_OPERATION_ID));
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_OPERATION_NAME));
            mdcMock.verify(() -> MDC.remove(DurableLogger.MDC_ATTEMPT));
        }
    }

    @Test
    void replayModeTransitionAllowsSubsequentLogs() {
        var replaying = new boolean[] {true};
        when(mockExecutionManager.isReplaying()).thenAnswer(inv -> replaying[0]);
        when(mockExecutionManager.getCurrentOperation()).thenReturn(null);
        var logger = new DurableLogger(mockLogger, mockExecutionManager, REQUEST_ID, true);

        // During replay - suppressed
        logger.info("suppressed");
        verify(mockLogger, never()).info(anyString(), any(Object[].class));

        // Transition to execution mode
        replaying[0] = false;

        // After transition - logged
        logger.info("logged after transition");
        verify(mockLogger).info(eq("logged after transition"), any(Object[].class));
    }

    @Test
    void allLogLevelsDelegateCorrectly() {
        var logger = createLogger(false, true);

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
            when(mockExecutionManager.getCurrentOperation()).thenReturn(null);
            var logger = new DurableLogger(mockLogger, mockExecutionManager, null, true);

            logger.info("test");

            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_EXECUTION_ARN, EXECUTION_ARN));
            mdcMock.verify(() -> MDC.put(eq(DurableLogger.MDC_REQUEST_ID), anyString()), never());
        }
    }

    @Test
    void handlesNullOperationContext() {
        try (MockedStatic<MDC> mdcMock = mockStatic(MDC.class)) {
            var logger = createLogger(false, true, null);

            logger.info("test");

            // Execution MDC should be set
            mdcMock.verify(() -> MDC.put(DurableLogger.MDC_EXECUTION_ARN, EXECUTION_ARN));
            // Operation MDC should not be set
            mdcMock.verify(() -> MDC.put(eq(DurableLogger.MDC_OPERATION_ID), anyString()), never());
            mdcMock.verify(() -> MDC.put(eq(DurableLogger.MDC_OPERATION_NAME), anyString()), never());
            mdcMock.verify(() -> MDC.put(eq(DurableLogger.MDC_ATTEMPT), anyString()), never());
        }
    }
}
