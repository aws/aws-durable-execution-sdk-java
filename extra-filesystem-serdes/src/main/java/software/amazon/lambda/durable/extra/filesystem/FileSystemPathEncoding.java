// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extra.filesystem;

/** Controls how durable execution and entity identifiers are encoded as filesystem paths. */
public enum FileSystemPathEncoding {
    URI,
    HASH
}
