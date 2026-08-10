// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

/** The kind of durable operation a DAG task delegates to. Internal. */
public enum TaskKind {
    STEP,
    INVOKE,
    CALLBACK,
    WAIT,
    WAIT_FOR_CONDITION,
    CHILD,
    MAP,
    PARALLEL,
    DAG
}
