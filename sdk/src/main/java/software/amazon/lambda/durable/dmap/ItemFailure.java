// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import com.fasterxml.jackson.annotation.JsonInclude;
import software.amazon.lambda.durable.model.DistributedMapError;

/**
 * A failed item in a processor response.
 *
 * @param itemIdentifier the item's identifier, copied from the input record
 * @param error why the item failed, or null to report a failure with no detail
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemFailure(String itemIdentifier, DistributedMapError error) {}
