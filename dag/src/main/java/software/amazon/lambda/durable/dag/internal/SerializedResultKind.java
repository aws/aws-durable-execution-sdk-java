// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Discriminator tagging how a task's result must be rehydrated on deserialization. Internal.
 *
 * <p>Serializes to the cross-language canonical {@code resultKind} vocabulary — {@code plain} | {@code batch} |
 * {@code dag}, lowercase (envelope convergence contract). The wire value, not the Java constant name, is authoritative;
 * {@link #BATCH} is Java's {@code MapResult} (a map or parallel task's batch aggregate).
 */
public enum SerializedResultKind {
    /** A plain JSON value; rehydrated to the task's declared result type when known, else a generic JSON tree. */
    PLAIN("plain"),
    /** A {@code MapResult} (from a map or parallel task) — recursively rehydrated. Wire value {@code "batch"}. */
    BATCH("batch"),
    /** A nested {@code DagResult} — recursively rehydrated. */
    DAG("dag");

    private final String value;

    SerializedResultKind(String value) {
        this.value = value;
    }

    /** The lowercase cross-language wire value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Parses a wire value back to its constant (additive-evolution tolerant callers should guard nulls). */
    @JsonCreator
    public static SerializedResultKind fromValue(String value) {
        for (var kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown resultKind: " + value);
    }
}
