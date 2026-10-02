// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/**
 * Stable identity supplied to operation serializers on initial execution and replay.
 *
 * <p>Entity IDs include the operation ID and payload role ({@code result}, {@code exception}, or
 * {@code invoke-payload}). Polling state uses the result identity. Multiple values can be serialized for the same
 * identity during retries or polling, so storage implementations must preserve previously returned references.
 *
 * @param durableExecutionArn the durable execution ARN, stable across Lambda invocations
 * @param entityId the payload identity, unique within the durable execution
 */
public record SerDesContext(String durableExecutionArn, String entityId) {}
