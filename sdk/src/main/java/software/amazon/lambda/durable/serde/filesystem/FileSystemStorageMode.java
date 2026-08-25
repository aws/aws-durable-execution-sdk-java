// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

/** Controls when serialized payloads are written to the filesystem. */
public enum FileSystemStorageMode {
    ALWAYS,
    OVERFLOW
}
