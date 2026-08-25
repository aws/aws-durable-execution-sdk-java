// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/**
 * A reversible conversion between strings and bytes at a {@link ComposableBinarySerDesStage} boundary.
 *
 * <p>The neutral {@code toBytes}/{@code fromBytes} names allow the same contract to be used at both ends of the binary
 * processing chain.
 */
public interface StringBinaryCodec {
    /**
     * Converts a non-null string to bytes.
     *
     * @param value the string value
     * @return the non-null byte representation
     */
    byte[] toBytes(String value);

    /**
     * Converts non-null bytes to a string.
     *
     * @param data the byte representation
     * @return the non-null string value
     */
    String fromBytes(byte[] data);

    /** Returns whether this codec requires an SDK-managed durable execution context. */
    default boolean requiresDurableContext() {
        return false;
    }
}
