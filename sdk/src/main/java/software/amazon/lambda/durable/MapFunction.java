// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Function applied to each item in a map operation.
 *
 * <p>Each invocation receives its own {@link DurableContext}, allowing the use of durable operations like
 * {@code step()} and {@code wait()} within the function body. The index parameter indicates the item's position in the
 * input collection.
 *
 * @param <I> the input item type
 * @param <O> the output result type
 */
@FunctionalInterface
public interface MapFunction<I, O> {

    /**
     * Applies this function to the given item.
     *
     * @param item the input item to process
     * @param index the zero-based index of the item in the input collection
     * @param context the durable context for this item's execution
     * @return the result of processing the item
     * @throws Exception if the function fails
     */
    O apply(I item, int index, DurableContext context) throws Exception;
}
