// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/** Immutable runtime data captured on the Lambda invocation thread before dispatch to a handler worker. */
public final class InvocationRuntimeContext {
    /** Runtime data for callers that do not supply an invocation-local carrier. */
    public static final InvocationRuntimeContext EMPTY = new InvocationRuntimeContext(null);

    private final String xRayTraceId;

    /** Creates a snapshot with the invocation-local X-Ray header, or null when the runtime supplies none. */
    public InvocationRuntimeContext(String xRayTraceId) {
        this.xRayTraceId = xRayTraceId;
    }

    /** Returns the invocation-local X-Ray header, or null when unavailable. */
    public String xRayTraceId() {
        return xRayTraceId;
    }
}
