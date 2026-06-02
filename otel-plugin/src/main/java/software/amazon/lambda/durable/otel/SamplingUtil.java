// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic sampling utility for durable executions.
 *
 * <p>Uses FNV-1a hash of the execution ARN to decide whether to sample. This ensures consistent sampling across all
 * invocations of the same execution — if you sample the first invocation, you sample all subsequent ones.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class SamplingUtil {

    private SamplingUtil() {}

    // FNV-1a 32-bit constants
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    /**
     * Determines whether an execution should be sampled based on its ARN.
     *
     * <p>Uses FNV-1a hash to distribute executions uniformly. The same ARN always produces the same result for a given
     * sampling rate.
     *
     * @param executionArn the durable execution ARN
     * @param samplingRate value between 0.0 (sample nothing) and 1.0 (sample everything)
     * @return true if this execution should be sampled
     */
    public static boolean shouldSampleExecution(String executionArn, double samplingRate) {
        if (samplingRate >= 1.0) return true;
        if (samplingRate <= 0.0) return false;
        if (executionArn == null || executionArn.isEmpty()) return false;

        var hash = fnv1a32(executionArn);
        // Convert to a value between 0.0 and 1.0
        var normalized = (hash & 0xFFFFFFFFL) / (double) 0x100000000L;
        return normalized < samplingRate;
    }

    /** FNV-1a 32-bit hash function. */
    static int fnv1a32(String input) {
        var bytes = input.getBytes(StandardCharsets.UTF_8);
        int hash = FNV_OFFSET_BASIS;
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
