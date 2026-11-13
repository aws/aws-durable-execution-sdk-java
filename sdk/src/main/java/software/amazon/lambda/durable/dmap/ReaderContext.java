// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

/**
 * The context a reader function receives with each page request.
 *
 * @param distributedMapRunArn the ARN of the distributed map run the page is read for
 */
public record ReaderContext(String distributedMapRunArn) {}
