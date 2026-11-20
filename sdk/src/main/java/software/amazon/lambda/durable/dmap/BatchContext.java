// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import java.util.List;

/**
 * The context a batch function receives with its items.
 *
 * @param distributedMapRunArn the ARN of the distributed map run the batch belongs to
 * @param itemIds the items' identifiers, assigned by the service. itemIds.get(i) is the id of items.get(i), so
 *     filtering or reordering the items breaks the pairing
 */
public record BatchContext(String distributedMapRunArn, List<String> itemIds) {

    /** Applies a defensive copy. */
    public BatchContext {
        itemIds = List.copyOf(itemIds);
    }
}
