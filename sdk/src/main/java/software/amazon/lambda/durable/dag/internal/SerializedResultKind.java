// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

/** Discriminator tagging how a task's result must be rehydrated on deserialization. Internal. */
public enum SerializedResultKind {
    /** A plain JSON value (rehydrated as a generic JSON tree). */
    PLAIN,
    /** A {@code MapResult} (from a map or parallel task) — recursively rehydrated. */
    MAP,
    /** A nested {@code DagResult} — recursively rehydrated. */
    DAG
}
