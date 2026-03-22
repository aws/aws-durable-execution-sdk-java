// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/** @deprecated use {@link software.amazon.lambda.durable.config.CallbackConfig} instead. */
public class CallbackConfig {
    /** @deprecated use {@link software.amazon.lambda.durable.config.CallbackConfig#builder()} instead. */
    public static software.amazon.lambda.durable.config.CallbackConfig.Builder builder() {
        return new software.amazon.lambda.durable.config.CallbackConfig.Builder(null, null, null);
    }
}
