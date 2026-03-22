// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Configuration options for step operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of step execution, including retry behavior
 * and delivery semantics.
 *
 * @deprecated use {@link software.amazon.lambda.durable.config.StepConfig}
 */
@Deprecated
public class StepConfig {
    /**
     * Creates a new builder for StepConfig.
     *
     * @deprecated use {@link software.amazon.lambda.durable.config.StepConfig#builder}
     */
    @Deprecated
    public static software.amazon.lambda.durable.config.StepConfig.Builder builder() {
        return new software.amazon.lambda.durable.config.StepConfig.Builder(null, null, null);
    }
}
