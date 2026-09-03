// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

/** Controls when serialized payloads are written to the filesystem. */
public enum PayloadOffloadMode {
    /** Always write payloads to the configured filesystem. */
    ALWAYS,

    /** Keep small payloads inline and write only payloads near the checkpoint size limit. */
    OVERFLOW
}
