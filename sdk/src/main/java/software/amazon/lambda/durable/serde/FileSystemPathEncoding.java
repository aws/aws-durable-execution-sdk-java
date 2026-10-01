// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/** Controls the encoding of execution and entity identities in filesystem paths. */
public enum FileSystemPathEncoding {
    /** Readable, percent-encoded path segments. Use HASH for long identities. */
    URI,
    /** Fixed-length SHA-256 hex digests. */
    HASH
}
