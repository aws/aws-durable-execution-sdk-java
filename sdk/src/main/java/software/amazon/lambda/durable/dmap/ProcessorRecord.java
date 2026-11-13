// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

/**
 * One record of a processor event.
 *
 * @param itemId the item's identifier, assigned by the service
 * @param body the serialized item, as the item serdes produced it
 */
public record ProcessorRecord(String itemId, String body) {

    /**
     * Rejects a record with no item id or no body. The helpers report each outcome against the item id, so a record
     * without one would report its outcome against no item. A record without a body has no item to process. An empty
     * body is valid. This runs on instances the Lambda runtime deserializes as well.
     */
    public ProcessorRecord {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalStateException("expected each distributed map processor record to have an 'itemId'");
        }
        if (body == null) {
            throw new IllegalStateException("expected each distributed map processor record to have a 'body'");
        }
    }
}
