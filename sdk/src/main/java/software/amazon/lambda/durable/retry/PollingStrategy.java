// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import java.time.Duration;

/** Functional interface for computing polling delays between attempts. */
@FunctionalInterface
public interface PollingStrategy {

    /**
     * Computes the delay before the next polling attempt.
     *
     * @param attempt The current attempt number (1-based)
     * @return Duration to wait before the next poll
     */
    Duration computeDelay(int attempt);
}
