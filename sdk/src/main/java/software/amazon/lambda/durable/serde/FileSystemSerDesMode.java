// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/** Controls when {@link FileSystemSerDes} stores serialized data on the filesystem. */
public enum FileSystemSerDesMode {
    /** Store every SDK-managed payload on the filesystem. */
    ALWAYS,

    /** Keep small payloads inline and store only payloads that exceed the checkpoint threshold. */
    OVERFLOW
}
