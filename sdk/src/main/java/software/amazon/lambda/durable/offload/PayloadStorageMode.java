// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

/** Identifies whether a serialized payload is stored inline or in external storage. */
public enum PayloadStorageMode {
    INLINE,
    REFERENCE
}
