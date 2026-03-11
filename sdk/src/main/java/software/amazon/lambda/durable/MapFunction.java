// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

@FunctionalInterface
public interface MapFunction<I, O> {
    O apply(DurableContext context, I item, int index);
}
