// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import software.amazon.lambda.durable.model.DistributedMapError;

/**
 * A failed item in a processor response.
 *
 * @param itemIdentifier the item's identifier, copied from the input record
 * @param error why the item failed, or null to report a failure with no detail
 */
public record ItemFailure(String itemIdentifier, DistributedMapError error) {}
