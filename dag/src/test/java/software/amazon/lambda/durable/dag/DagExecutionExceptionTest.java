// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Unit tests for {@link DagExecutionException} and its cross-boundary (SerDes) reconstruction. */
class DagExecutionExceptionTest {

    @Test
    void survivesSerDesRoundTripPreservingTypeAndCause() {
        // throwIfError() can be invoked inside a nested DAG task body, so a cause-carrying DagExecutionException can
        // cross the child-context boundary. Reconstruction only works because of the @JsonCreator that sets the cause
        // at construction (the base hierarchy pre-initializes the cause to null, so Jackson's default initCause path
        // fails) — the same idiom DagPredicateException uses. Without it the type erases to
        // ChildContextFailedException.
        var serDes = new JacksonSerDes();
        var original = new DagExecutionException("DAG had 1 failed task", new IllegalStateException("task failed"));

        String json = serDes.serialize(original);
        var restored = serDes.deserialize(json, TypeToken.get(DagExecutionException.class));

        assertNotNull(restored);
        assertEquals(original.getMessage(), restored.getMessage());
        assertNotNull(restored.getCause(), "cause must survive the round trip");
        assertEquals("task failed", restored.getCause().getMessage());
    }

    @Test
    void survivesSerDesRoundTripWithoutCause() {
        var serDes = new JacksonSerDes();
        var original = new DagExecutionException("DAG had 1 failed task");

        String json = serDes.serialize(original);
        var restored = serDes.deserialize(json, TypeToken.get(DagExecutionException.class));

        assertNotNull(restored);
        assertEquals(original.getMessage(), restored.getMessage());
        assertTrue(restored.getCause() == null, "no cause was set");
    }
}
