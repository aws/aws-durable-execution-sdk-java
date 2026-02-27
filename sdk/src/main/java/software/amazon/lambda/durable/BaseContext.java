// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.logging.DurableLogger;

public abstract class BaseContext {
    protected final ExecutionManager executionManager;
    private final DurableConfig durableConfig;
    private final Context lambdaContext;
    private final ExecutionContext executionContext;
    private final String contextId;
    private boolean isReplaying;

    /** Creates a new BaseContext instance. */
    protected BaseContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext, String contextId) {
        this.executionManager = executionManager;
        this.durableConfig = durableConfig;
        this.lambdaContext = lambdaContext;
        this.contextId = contextId;
        this.executionContext = new ExecutionContext(executionManager.getDurableExecutionArn());
        this.isReplaying = executionManager.hasOperationsForContext(contextId);
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
}
