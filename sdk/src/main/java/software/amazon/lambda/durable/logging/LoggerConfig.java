// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

/** Configuration for DurableLogger behavior. */
public record LoggerConfig(boolean suppressReplayLogs) {

    /** Default configuration: suppress logs during replay. */
    public static LoggerConfig defaults() {
        return new LoggerConfig(true);
    }

    /** Configuration that allows logs during replay. */
    public static LoggerConfig withReplayLogging() {
        return new LoggerConfig(false);
    }
}
