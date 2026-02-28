// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import software.amazon.lambda.durable.BaseContext;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;

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
    private final BaseContext context;

    public DurableLogger(Logger delegate, BaseContext context) {
        this.delegate = delegate;
        this.context = context;

        // execution arn
        MDC.put(MDC_EXECUTION_ARN, context.getExecutionContext().getDurableExecutionArn());

        // lambda request id
        var requestId =
                context.getLambdaContext() != null ? context.getLambdaContext().getAwsRequestId() : null;
        if (requestId != null) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }

        if (context instanceof DurableContext) {
            // context thread - context id
            if (context.getContextId() != null) {
                MDC.put(MDC_CONTEXT_ID, context.getContextId());
            }
            if (context.getContextName() != null) {
                MDC.put(MDC_CONTEXT_NAME, context.getContextName());
            }
        } else {
            // step context
            var operationId = context.getContextId();
            // step context - step operation id
            MDC.put(MDC_OPERATION_ID, operationId);
            // step context - step operation name
            if (context.getContextName() != null) {
                MDC.put(MDC_OPERATION_NAME, context.getContextName());
            }
            MDC.put(MDC_ATTEMPT, String.valueOf(((StepContext) context).getAttempt()));
        }
    }

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
