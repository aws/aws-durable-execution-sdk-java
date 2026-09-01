// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

/** Controls how durable execution ownership is represented in payload file names. */
public enum FileSystemPathEncoding {
    /** Include a bounded, escaped entity prefix followed by a SHA-256 owner digest. */
    URI,

    /** Use only the fixed-length SHA-256 owner digest. */
    HASH
}
