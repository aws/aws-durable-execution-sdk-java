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

    /**
     * Mints an operation ID from a caller-supplied name suffix instead of the monotonic counter. Concatenates the
     * context ID prefix ({@code contextId + "-"}) with the supplied name and SHA-256 hashes the result, reusing the
     * exact same prefixing and hashing discipline as {@link #nextOperationId()}. Unlike {@link #nextOperationId()},
     * this does NOT touch the internal counter, so name-based and counter-based IDs never interfere.
     *
     * <p>This is the seam that enables name-derived, replay-safe entity IDs (e.g. the DAG scheduler passes
     * {@code "DAG_NODE_T_" + taskName}, yielding {@code hash(contextId + "-DAG_NODE_T_" + taskName)}). Deterministic:
     * the same name always maps to the same ID within a given context; distinct names map to distinct IDs.
     *
     * @param name the caller-supplied name suffix (already namespaced by the caller if needed)
     * @return the SHA-256 hashed operation ID for {@code operationIdPrefix + name}
     */
    public String operationIdForName(String name) {
        return hashOperationId(operationIdPrefix + name);
    }
}
