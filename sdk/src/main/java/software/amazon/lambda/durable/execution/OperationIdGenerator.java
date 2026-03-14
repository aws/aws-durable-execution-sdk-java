// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Generates operation IDs for the durable operations. */
public class OperationIdGenerator {
    private final AtomicInteger operationCounter;
    private final String operationIdPrefix;

    public OperationIdGenerator(String contextId) {
        this.operationCounter = new AtomicInteger(0);
        this.operationIdPrefix = contextId != null ? contextId + "-" : "";
    }

    /**
     * Hashes the given string using SHA-256
     *
     * @param rawId the string to hash
     * @return the hashed string
     */
    public static String hashOperationId(String rawId) {
        try {
            var messageDigest = MessageDigest.getInstance("SHA-256");
            var hash = messageDigest.digest(rawId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("failed to get next operation id, SHA-256 not available", e);
        }
    }

    /**
     * Returns the next globally unique operation ID. Increments an internal counter, concatenates it with the context
     * ID prefix ({@code contextId + "-" + counter}), and SHA-256 hashes the result. For root contexts the prefix is the
     * EXECUTION operation ID; for child contexts it is the parent's hashed context ID. This produces IDs like
     * {@code hash("execId-1")}, {@code hash("execId-2")} at the root level, and {@code hash("<parentHash>-1")},
     * {@code hash("<parentHash>-2")} inside a child context.
     */
    public String nextOperationId() {
        var counter = String.valueOf(operationCounter.incrementAndGet());
        return hashOperationId(operationIdPrefix + counter);
    }
}
