// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

import java.util.Map;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;

/**
 * Generates optional inline preview metadata for an externally stored serialized payload.
 *
 * <p>Preview values must be JSON-compatible maps, collections, arrays, or scalar values. The SDK snapshots containers
 * and normalizes arrays to immutable lists before encoding the payload envelope.
 */
@FunctionalInterface
public interface PayloadPreviewGenerator {
    Map<String, Object> generate(String serializedPayload, PayloadOffloadContext context);
}
