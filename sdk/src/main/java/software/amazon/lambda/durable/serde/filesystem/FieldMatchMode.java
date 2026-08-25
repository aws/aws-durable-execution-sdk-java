// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

/** Controls how a {@link PreviewField} matches a field in a structured value. */
public enum FieldMatchMode {
    /** Matches the field name at any depth in the object tree. */
    ANYWHERE,

    /** Matches the exact dot-separated path from the root object. */
    PATH
}
