// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

/**
 * Stores and loads serialized durable execution payloads.
 *
 * <p>Implementations receive serialized text after {@code SerDes} processing. They may keep it inline or replace it
 * with a reference to external storage. A returned reference must keep the same meaning for the lifetime of every
 * checkpoint that contains it. Implementations that overwrite storage should include
 * {@link PayloadOffloadContext#attempt()} or another immutable version in the storage key when a payload can be
 * updated.
 */
public interface PayloadOffloader {

    /** Stores serialized payload data or returns it inline. */
    OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context);

    /** Restores serialized payload data from either an inline value or an external reference. */
    String load(OffloadedPayload payload, PayloadOffloadContext context);

    /** Returns a sentinel that disables a globally configured offloader for a specific operation. */
    static PayloadOffloader disabled() {
        return PayloadOffloaders.disabled();
    }
}
