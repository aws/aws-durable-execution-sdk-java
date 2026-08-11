// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.DurableExecutionException;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Unit tests for {@link DagPredicateException} and its cross-boundary (SerDes) reconstruction. */
class DagPredicateExceptionTest {

    @Test
    void carriesTaskNameMessageAndCause() {
        var cause = new IllegalStateException("predicate boom");
        var ex = new DagPredicateException("maybe", cause);

        assertEquals("maybe", ex.taskName());
        assertEquals(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("maybe"), "message must name the task");
        assertTrue(ex.getMessage().contains("predicate boom"), "message must identify the original error");
        assertInstanceOf(DagException.class, ex);
        assertInstanceOf(DurableExecutionException.class, ex);
    }

    @Test
    void survivesSerDesRoundTripPreservingTypeAndCause() {
        // This is the round-trip the DAG child-context boundary relies on: the typed exception is serialized on the
        // failing side and reconstructed on the caller side. The reconstructed exception must remain a
        // DagPredicateException whose message and taskName name the task and whose cause is the reconstructed original
        // error.
        var serDes = new JacksonSerDes();
        var original = new DagPredicateException("maybe", new IllegalStateException("predicate boom"));

        String json = serDes.serialize(original);
        var restored = serDes.deserialize(json, TypeToken.get(DagPredicateException.class));

        assertNotNull(restored);
        assertEquals(original.getMessage(), restored.getMessage());
        assertTrue(restored.getMessage().contains("maybe"));
        assertEquals("maybe", restored.taskName());
        // The cause is retrievable with its original message and stack trace preserved. Its concrete Java type
        // degrades to Throwable across the durable boundary: the SDK serializes a nested cause without polymorphic
        // type info, so only a top-level exception's concrete type is recoverable (via ErrorObject.errorType). This is
        // a general property of the SDK's exception serialization, not specific to runIf.
        assertNotNull(restored.getCause());
        assertEquals("predicate boom", restored.getCause().getMessage());
        assertTrue(restored.getCause().getStackTrace().length > 0, "cause stack trace must be preserved");
    }
}
