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
     * Shared initialization — sets all fields but performs no thread registration.
     *
     * @param executionManager Manages durable execution state and operations
     * @param durableConfig Configuration for durable execution behavior
     * @param lambdaContext AWS Lambda runtime context
     * @param contextId Unique identifier for this context instance
     */
    protected StepContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext, String contextId) {
        super(executionManager, durableConfig, lambdaContext, contextId);

        var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
        this.logger = new DurableLogger(
                LoggerFactory.getLogger(DurableContext.class),
                executionManager,
                requestId,
                durableConfig.getLoggerConfig().suppressReplayLogs());
    }

    @Override
    public DurableLogger getLogger() {
        return logger;
    }
}
