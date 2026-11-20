// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

/**
 * The request the fan-out service sends to a reader function.
 *
 * @param distributedMapRunArn the ARN of the distributed map run this page is read for
 * @param maxItemsPerPage the most items the reader may return in this page. Boxed because the Lambda runtime turns an
 *     absent primitive into zero, which would be indistinguishable from a real value
 * @param state the opaque state from the previous call, or null on the first call
 */
public record ReaderEvent(String distributedMapRunArn, Integer maxItemsPerPage, String state) {

    /** Rejects an event that is not a reader request. Runs on runtime-deserialized instances too. */
    public ReaderEvent {
        if (maxItemsPerPage == null) {
            throw new IllegalStateException(
                    "expected a distributed map reader event carrying a numeric 'maxItemsPerPage', "
                            + "the function must be registered as a distributed map reader");
        }
        if (distributedMapRunArn == null || distributedMapRunArn.isEmpty()) {
            throw new IllegalStateException("expected a distributed map reader event to have a 'distributedMapRunArn'");
        }
    }
}
