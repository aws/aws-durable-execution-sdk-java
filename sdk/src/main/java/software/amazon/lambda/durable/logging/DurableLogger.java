// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.context.BaseContextImpl;

/**
 * Logger wrapper that adds durable execution context to log entries via MDC and optionally suppresses logs during
 * replay.
 */
public class DurableLogger {
    static final String MDC_EXECUTION_ARN = "durableExecutionArn";
    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_OPERATION_ID = "operationId";
    static final String MDC_CONTEXT_ID = "contextId";
    static final String MDC_OPERATION_NAME = "operationName";
    static final String MDC_CONTEXT_NAME = "contextName";
    static final String MDC_ATTEMPT = "attempt";

    private final Logger delegate;
    private final BaseContextImpl context;

    /**
     * Creates a DurableLogger wrapping the given SLF4J logger with execution context MDC entries.
     *
     * @param delegate the SLF4J logger to wrap
     * @param context the durable execution context providing MDC values
     */
    public DurableLogger(Logger delegate, BaseContextImpl context) {
        this.delegate = delegate;
        this.context = context;

        // execution arn
        MDC.put(MDC_EXECUTION_ARN, context.getExecutionArn());

        // lambda request id
        var requestId =
                context.getLambdaContext() != null ? context.getLambdaContext().getAwsRequestId() : null;
        if (requestId != null) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }

        if (context instanceof DurableContext) {
            // context thread - context id and name
            if (context.getContextId() != null) {
                MDC.put(MDC_CONTEXT_ID, context.getContextId());
            }
            if (context.getContextName() != null) {
                MDC.put(MDC_CONTEXT_NAME, context.getContextName());
            }
        } else if (context instanceof StepContext stepContext) {
            // In step context, context id is the operation id, context name is the operation name
            var operationId = context.getContextId();
            MDC.put(MDC_OPERATION_ID, operationId);
            if (context.getContextName() != null) {
                MDC.put(MDC_OPERATION_NAME, context.getContextName());
            }
            MDC.put(MDC_ATTEMPT, String.valueOf(stepContext.getAttempt()));
        }
    }

    /** Clears all MDC entries. User set MDC entries will also be removed as the thread will not be used anymore. */
    public void close() {
        MDC.clear();
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
        return context.getDurableConfig().getLoggerConfig().suppressReplayLogs()
                && context.getExecutionManager().isReplaying();
    }

    private void log(Runnable logAction) {
        if (!shouldSuppress()) {
            logAction.run();
        }
    }
}
