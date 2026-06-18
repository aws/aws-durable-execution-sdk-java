// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.model.SafeCloseable;

/**
 * Logger wrapper that adds durable execution context to log entries via MDC and optionally suppresses logs during
 * replay.
 */
public class DurableLogger {
    static final String MDC_DURABLE_EXECUTION_ARN = "durableExecutionArn";
    static final String MDC_EXECUTION_ARN = "executionArn";
    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_OPERATION_ID = "operationId";
    static final String MDC_CONTEXT_ID = "contextId";
    static final String MDC_OPERATION_NAME = "operationName";
    static final String MDC_CONTEXT_NAME = "contextName";
    static final String MDC_ATTEMPT = "attempt";

    public static final DurableLogger INSTANCE = new DurableLogger(LoggerFactory.getLogger(DurableLogger.class));
    private static final SafeCloseable AUTO_CLOSER = DurableLogger::detachContext;

    private final Logger delegate;

    /**
     * Creates a DurableLogger wrapping the given SLF4J logger with execution context MDC entries.
     *
     * @param delegate the SLF4J logger to wrap
     */
    public DurableLogger(Logger delegate) {
        this.delegate = delegate;
    }

    public static SafeCloseable attachContext() {
        var context = BaseContext.getCurrentContext();
        if (context != null) {
            injectMdcProperties(context);
        }
        return AUTO_CLOSER;
    }

    public static void detachContext() {
        var context = BaseContext.getCurrentContext();
        if (context != null) {
            MDC.clear();
        }
    }

    private static void injectMdcProperties(BaseContext context) {
        var config = context.getDurableConfig().getLoggerConfig();

        // execution arn
        if (config.oldKeyNames()) {
            MDC.put(MDC_DURABLE_EXECUTION_ARN, context.getExecutionArn());
        } else {
            MDC.put(MDC_EXECUTION_ARN, context.getExecutionArn());
        }

        // lambda request id
        var requestId =
                context.getLambdaContext() != null ? context.getLambdaContext().getAwsRequestId() : null;
        if (requestId != null) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }

        if (context instanceof DurableContext) {
            // context thread - context id and name
            if (context.getContextId() != null) {
                if (config.oldKeyNames()) {
                    MDC.put(MDC_CONTEXT_ID, context.getContextId());
                } else {
                    MDC.put(MDC_OPERATION_ID, context.getContextId());
                }
            }
            if (context.getContextName() != null) {
                if (config.oldKeyNames()) {
                    MDC.put(MDC_CONTEXT_NAME, context.getContextName());
                } else {
                    MDC.put(MDC_OPERATION_NAME, context.getContextName());
                }
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

    private boolean shouldSuppress(BaseContext context) {
        return context instanceof DurableContext durableContext
                && context.getDurableConfig().getLoggerConfig().suppressReplayLogs()
                && durableContext.isReplaying();
    }

    private void log(Runnable logAction) {
        var threadLocalContext = BaseContext.getCurrentContext();
        if (!shouldSuppress(threadLocalContext)) {
            logAction.run();
        }
    }
}
