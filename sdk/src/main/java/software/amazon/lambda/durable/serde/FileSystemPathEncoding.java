// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/** Controls how durable execution and entity identifiers are encoded into filesystem paths. */
public enum FileSystemPathEncoding {
    /** Percent-encode identifiers to keep paths human-readable. */
    URI,

    /** Replace identifiers with fixed-length SHA-256 hashes. */
    HASH
}
