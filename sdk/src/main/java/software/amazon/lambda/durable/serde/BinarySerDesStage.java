// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/**
 * A reversible binary stage used inside a {@link ComposableBinarySerDesStage}.
 *
 * <p>Implementations must include any metadata needed for deserialization, such as format versions or encryption
 * initialization vectors, in the returned bytes.
 */
public interface BinarySerDesStage {
    /**
     * Applies this transformation during forward serialization.
     *
     * @param value the non-null input bytes
     * @return the non-null transformed bytes
     */
    byte[] serialize(byte[] value);

    /**
     * Reverses this transformation during deserialization.
     *
     * @param data the non-null bytes produced by this transformation
     * @return the non-null bytes expected by the preceding transformation
     */
    byte[] deserialize(byte[] data);
}
