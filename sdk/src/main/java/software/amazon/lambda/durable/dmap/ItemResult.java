// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A succeeded item in a processor response.
 *
 * @param itemIdentifier the item's identifier, copied from the input record
 * @param output the item's output as the result serdes produced it, or null when the handler returned nothing. A null
 *     output means the item succeeded without an output.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemResult(String itemIdentifier, String output) {}
