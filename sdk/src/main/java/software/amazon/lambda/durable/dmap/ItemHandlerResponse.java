// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The reply an item handler returns.
 *
 * <p>A null list is omitted from the JSON rather than sent as null, because the Lambda runtime's serializer excludes
 * null fields. The service treats an absent, null, or empty list identically, so this is a shape choice rather than a
 * behavioural one.
 *
 * @param batchItemResults per-item outputs, or null to leave the key off the wire
 * @param batchItemFailures the items that failed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemHandlerResponse(List<ItemResult> batchItemResults, List<ItemFailure> batchItemFailures) {}
