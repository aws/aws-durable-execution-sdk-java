// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * Immutable SDK-owned propagation contribution. This is not a generated Lambda request model and is not currently
 * serialized on the production invoke START path. A null header means the plugin has no contribution.
 */
public final class PropagationMetadata {
    private final String xAmznTraceId;

    public PropagationMetadata(String xAmznTraceId) {
        this.xAmznTraceId = xAmznTraceId;
    }

    public String xAmznTraceId() {
        return xAmznTraceId;
    }
}
