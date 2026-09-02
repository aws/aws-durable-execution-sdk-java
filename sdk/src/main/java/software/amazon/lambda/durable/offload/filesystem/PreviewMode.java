// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

/** Controls which fields are visible by default in a structured payload preview. */
public enum PreviewMode {
    /** Includes every field unless an exclude rule removes it. */
    INCLUDE_ALL,

    /** Excludes every field unless an include or mask rule selects it. */
    EXCLUDE_ALL
}
