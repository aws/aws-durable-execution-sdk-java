// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.logging.DurableLogger;

public class StepContext extends BaseContext {
    private final DurableLogger logger;

    /**
     * Creates a new StepContext instance for use in step operations.
     *
     * @param executionManager Manages durable execution state and operations
     * @param durableConfig Configuration for durable execution behavior
     * @param lambdaContext AWS Lambda runtime context
     * @param stepOperationId Unique identifier for this context instance that equals to step operation id
     */
    protected StepContext(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String stepOperationId) {
        super(executionManager, durableConfig, lambdaContext, stepOperationId);

        var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
        this.logger = new DurableLogger(
                LoggerFactory.getLogger(StepContext.class),
                executionManager,
                requestId,
                durableConfig.getLoggerConfig().suppressReplayLogs());
    }

    @Override
    public DurableLogger getLogger() {
        return logger;
    }
}
