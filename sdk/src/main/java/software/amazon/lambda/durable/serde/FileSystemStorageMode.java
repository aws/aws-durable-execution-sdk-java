// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/** Controls when serialized operation payloads are written to the filesystem. */
public enum FileSystemStorageMode {
    /** Store every non-null serialized payload in a file. */
    ALWAYS,
    /** Keep payloads inline unless their UTF-8 checkpoint envelope exceeds 255 KiB. */
    OVERFLOW
}
