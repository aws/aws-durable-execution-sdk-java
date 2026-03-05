// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.logging.DurableLogger;

public abstract class BaseContext implements AutoCloseable {
    private final ExecutionManager executionManager;
    private final DurableConfig durableConfig;
    private final Context lambdaContext;
    private final ExecutionContext executionContext;
    private final String contextId;
    private final String contextName;
    private final ThreadType threadType;

    private boolean isReplaying;

    /** Creates a new BaseContext instance. */
    protected BaseContext(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName,
            ThreadType threadType) {
        this.executionManager = executionManager;
        this.durableConfig = durableConfig;
        this.lambdaContext = lambdaContext;
        this.contextId = contextId;
        this.contextName = contextName;
        this.executionContext = new ExecutionContext(executionManager.getDurableExecutionArn());
        this.isReplaying = executionManager.hasOperationsForContext(contextId);
        this.threadType = threadType;

        // write the thread id and type to thread local
        executionManager.setCurrentThreadContext(new ThreadContext(contextId, threadType));
    }

    // =============== accessors ================
    /**
     * Gets a logger with additional information of the current execution context.
     *
     * @return a DurableLogger instance
     */
    public abstract DurableLogger getLogger();

    /**
     * Returns the AWS Lambda runtime context.
     *
     * @return the Lambda context
     */
    public Context getLambdaContext() {
        return lambdaContext;
    }

    /**
     * Returns metadata about the current durable execution.
     *
     * <p>The execution context provides information that remains constant throughout the execution lifecycle, such as
     * the durable execution ARN. This is useful for tracking execution progress, correlating logs, and referencing this
     * execution in external systems.
     *
     * @return the execution context
     */
    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    /**
     * Returns the configuration for durable execution behavior.
     *
     * @return the durable configuration
     */
    public DurableConfig getDurableConfig() {
        return durableConfig;
    }

    // ============= internal utilities ===============

    /** Gets the context ID for this context. Null for root context, set for child contexts. */
    public String getContextId() {
        return contextId;
    }

    public String getContextName() {
        return contextName;
    }

    public ExecutionManager getExecutionManager() {
        return executionManager;
    }

    /** Returns whether this context is currently in replay mode. */
    boolean isReplaying() {
        return isReplaying;
    }

    /**
     * Transitions this context from replay to execution mode. Called when the first un-cached operation is encountered.
     */
    void setExecutionMode() {
        this.isReplaying = false;
    }

    public void close() {
        // this is called in the user thread, after the context's user code has completed
        if (getContextId() != null) {
            // if this is a child context or a step context, we need to
            // deregister the context's thread from the execution manager
            try {
                executionManager.deregisterActiveThread(getContextId());
            } catch (SuspendExecutionException e) {
                // Expected when this is the last active thread. Must catch here because:
                // 1/ This runs in a worker thread detached from handlerFuture
                // 2/ Uncaught exception would prevent stepAsync().get() from resume
                // Suspension/Termination is already signaled via
                // suspendExecutionFuture/terminateExecutionFuture
                // before the throw.
            }
        }
    }
}
