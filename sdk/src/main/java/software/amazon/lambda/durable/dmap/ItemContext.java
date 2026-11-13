// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

/**
 * The context an item function receives with one item.
 *
 * @param distributedMapRunArn the ARN of the distributed map run the item belongs to
 * @param itemId the item's identifier, assigned by the service
 */
public record ItemContext(String distributedMapRunArn, String itemId) {}
