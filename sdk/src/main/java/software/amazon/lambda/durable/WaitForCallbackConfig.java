// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/** @deprecated use {@link software.amazon.lambda.durable.config.WaitForCallbackConfig} instead. */
@Deprecated
public class WaitForCallbackConfig {
    /** @deprecated use {@link software.amazon.lambda.durable.config.WaitForCallbackConfig#builder()} instead. */
    @Deprecated
    public static software.amazon.lambda.durable.config.WaitForCallbackConfig.Builder builder() {
        return new software.amazon.lambda.durable.config.WaitForCallbackConfig.Builder();
    }
}
