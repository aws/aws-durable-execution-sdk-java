// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * Immutable SDK-owned propagation contribution, separate from generated Lambda request models. The core maps its
 * optional header to the invoke START checkpoint. A null header means the plugin has no contribution.
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
