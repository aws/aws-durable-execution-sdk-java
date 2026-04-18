// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

public enum NestingType {
    /**
     * Create CONTEXT operations for each branch/iteration with full checkpointing. Operations within each
     * branch/iteration are wrapped in their own context. - **Observability**: High - each branch/iteration appears as
     * separate operation in execution history - **Cost**: Higher - consumes more operations due to CONTEXT creation
     * overhead - **Scale**: Lower maximum iterations due to operation limits
     */
    NESTED,

    /**
     * Skip CONTEXT operations for branches/iterations using virtual contexts. Operations execute directly without
     * individual context wrapping. - **Observability**: Lower - branches/iterations don't appear as separate operations
     * - **Cost**: ~30% lower - reduces operation consumption by skipping CONTEXT overhead - **Scale**: Higher maximum
     * iterations possible within operation limits
     */
    FLAT,
}
