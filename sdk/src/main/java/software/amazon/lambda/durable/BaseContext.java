// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.lambda.durable.logging.DurableLogger;

public interface BaseContext extends AutoCloseable {
    /**
     * Gets a logger with additional information of the current execution context.
     *
     * @return a DurableLogger instance
     */
    DurableLogger getLogger();

    /**
     * Returns the AWS Lambda runtime context.
     *
     * @return the Lambda context
     */
    Context getLambdaContext();

    /**
     * Returns the current durable execution arn
     *
     * @return the execution arn
     */
    String getExecutionArn();

    /**
     * Returns the configuration for durable execution behavior.
     *
     * @return the durable configuration
     */
    DurableConfig getDurableConfig();

    /**
     * Gets the context ID for this context. Null for root context, operationId of the context operation for child
     * contexts.
     */
    String getContextId();

    /** Gets the context name for this context. Null for root context. */
    String getContextName();

    /** Returns whether this context is currently in replay mode. */
    boolean isReplaying();

    /** Closes this context. */
    void close();
}
