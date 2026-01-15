// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.logging;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Logger wrapper that adds durable execution context to log entries via MDC and optionally suppresses logs during
 * replay.
 */
public class DurableLogger {
    static final String MDC_EXECUTION_ARN = "durableExecutionArn";
    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_OPERATION_ID = "operationId";
    static final String MDC_OPERATION_NAME = "operationName";
    static final String MDC_ATTEMPT = "attempt";

    private final Logger delegate;
    private final ExecutionManager executionManager;
    private final String requestId;
    private final boolean suppressReplayLogs;

    public DurableLogger(
            Logger delegate, ExecutionManager executionManager, String requestId, boolean suppressReplayLogs) {
        this.delegate = delegate;
        this.executionManager = executionManager;
        this.requestId = requestId;
        this.suppressReplayLogs = suppressReplayLogs;
    }

    public void trace(String format, Object... args) {
        log(() -> delegate.trace(format, args));
    }

    public void debug(String format, Object... args) {
        log(() -> delegate.debug(format, args));
    }

    public void info(String format, Object... args) {
        log(() -> delegate.info(format, args));
    }

    public void warn(String format, Object... args) {
        log(() -> delegate.warn(format, args));
    }

    public void error(String format, Object... args) {
        log(() -> delegate.error(format, args));
    }

    public void error(String message, Throwable t) {
        log(() -> delegate.error(message, t));
    }

    private boolean shouldSuppress() {
        return suppressReplayLogs && executionManager.isReplaying();
    }

    private void log(Runnable logAction) {
        if (shouldSuppress()) {
            return;
        }

        try {
            // Set execution-level MDC
            MDC.put(MDC_EXECUTION_ARN, executionManager.getDurableExecutionArn());
            if (requestId != null) {
                MDC.put(MDC_REQUEST_ID, requestId);
            }

            // Set operation-level MDC from current context
            var opCtx = executionManager.getCurrentOperation();
            if (opCtx != null) {
                if (opCtx.operationId() != null) {
                    MDC.put(MDC_OPERATION_ID, opCtx.operationId());
                }
                if (opCtx.operationName() != null) {
                    MDC.put(MDC_OPERATION_NAME, opCtx.operationName());
                }
                if (opCtx.attempt() != null) {
                    MDC.put(MDC_ATTEMPT, String.valueOf(opCtx.attempt()));
                }
            }

            logAction.run();
        } finally {
            MDC.remove(MDC_EXECUTION_ARN);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_OPERATION_ID);
            MDC.remove(MDC_OPERATION_NAME);
            MDC.remove(MDC_ATTEMPT);
        }
    }
}
