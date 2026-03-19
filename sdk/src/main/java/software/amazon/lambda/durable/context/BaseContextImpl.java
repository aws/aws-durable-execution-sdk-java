// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.lambda.durable.BaseContext;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;

public abstract class BaseContextImpl implements AutoCloseable, BaseContext {
    private final ExecutionManager executionManager;
    private final DurableConfig durableConfig;
    private final Context lambdaContext;
    private final String contextId;
    private final String contextName;
    private final ThreadType threadType;

    private boolean isReplaying;

    /**
     * Creates a new BaseContext instance.
     *
     * @param executionManager the execution manager for thread coordination and state management
     * @param durableConfig the durable execution configuration
     * @param lambdaContext the AWS Lambda runtime context
     * @param contextId the context ID, null for root context, set for child contexts
     * @param contextName the human-readable name for this context
     * @param threadType the type of thread this context runs on
     */
    protected BaseContextImpl(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName,
            ThreadType threadType) {
        this(executionManager, durableConfig, lambdaContext, contextId, contextName, threadType, true);
    }

    /**
     * Creates a new BaseContext instance.
     *
     * @param executionManager the execution manager for thread coordination and state management
     * @param durableConfig the durable execution configuration
     * @param lambdaContext the AWS Lambda runtime context
     * @param contextId the context ID, null for root context, set for child contexts
     * @param contextName the human-readable name for this context
     * @param threadType the type of thread this context runs on
     * @param setCurrentThreadContext whether to call setCurrentThreadContext on the execution manager
     */
    protected BaseContextImpl(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName,
            ThreadType threadType,
            boolean setCurrentThreadContext) {
        this.executionManager = executionManager;
        this.durableConfig = durableConfig;
        this.lambdaContext = lambdaContext;
        this.contextId = contextId;
        this.contextName = contextName;
        this.isReplaying = executionManager.hasOperationsForContext(contextId);
        this.threadType = threadType;

        if (setCurrentThreadContext) {
            // write the thread id and type to thread local
            executionManager.setCurrentThreadContext(new ThreadContext(contextId, threadType));
        }
    }

    // =============== accessors ================

    /**
     * Returns the AWS Lambda runtime context.
     *
     * @return the Lambda context
     */
    @Override
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
    @Override
    public String getExecutionArn() {
        return executionManager.getDurableExecutionArn();
    }

    /**
     * Returns the configuration for durable execution behavior.
     *
     * @return the durable configuration
     */
    @Override
    public DurableConfig getDurableConfig() {
        return durableConfig;
    }

    // ============= internal utilities ===============

    /** Gets the context ID for this context. Null for root context, set for child contexts. */
    @Override
    public String getContextId() {
        return contextId;
    }

    /** Gets the context name for this context. Null for root context. */
    @Override
    public String getContextName() {
        return contextName;
    }

    public ExecutionManager getExecutionManager() {
        return executionManager;
    }

    /** Returns whether this context is currently in replay mode. */
    @Override
    public boolean isReplaying() {
        return isReplaying;
    }

    /**
     * Transitions this context from replay to execution mode. Called when the first un-cached operation is encountered.
     */
    public void setExecutionMode() {
        this.isReplaying = false;
    }

    @Override
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
