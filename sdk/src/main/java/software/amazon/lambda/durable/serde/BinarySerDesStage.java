// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/**
 * A reversible binary stage used inside a {@link ComposableBinarySerDesStage}.
 *
 * <p>Implementations must include any metadata needed for deserialization, such as format versions or encryption
 * initialization vectors, in the returned bytes.
 *
 * <p>The enclosing composable stage passes the same durable payload context to each binary stage. During serialization,
 * {@link SerDesContext#originalValue()} is the object supplied to the root value codec. During deserialization it is
 * {@code null}. Stages must treat the original value as read-only. The context itself may be {@code null} only when the
 * stage is invoked outside an SDK-managed SerDes call.
 */
public interface BinarySerDesStage {
    /**
     * Applies this transformation during forward serialization.
     *
     * @param value the non-null input bytes
     * @param context the current durable payload context, or {@code null} outside SDK-managed calls
     * @return the non-null transformed bytes
     */
    byte[] serialize(byte[] value, SerDesContext context);

    /**
     * Reverses this transformation during deserialization.
     *
     * @param data the non-null bytes produced by this transformation
     * @param context the current durable payload context, or {@code null} outside SDK-managed calls
     * @return the non-null bytes expected by the preceding transformation
     */
    byte[] deserialize(byte[] data, SerDesContext context);
}
