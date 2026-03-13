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
    private final String contextId;

    public OperationIdGenerator(String contextId) {
        this.operationCounter = new AtomicInteger(0);
        this.contextId = contextId;
    }

    /**
     * Get the next operationId. Returns a globally unique operation ID by hashing a sequential operation counter. For
     * root contexts, the counter value is hashed directly (e.g. "1", "2", "3"). For child contexts, the values are
     * prefixed with the parent hashed contextId (e.g. "<hash>-1", "<hash>-2" inside parent context <hash>). This
     * matches the Python SDK's stepPrefix convention and prevents ID collisions in checkpoint batches.
     */
    public String nextOperationId() {
        var counter = String.valueOf(operationCounter.incrementAndGet());
        var rawId = contextId != null ? contextId + "-" + counter : counter;
        try {
            var messageDigest = MessageDigest.getInstance("SHA-256");
            var hash = messageDigest.digest(rawId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("failed to get next operation id, SHA-256 not available", e);
        }
    }
}
