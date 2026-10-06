// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyClientTest {

    @Test
    void returnsTheInjectedInstanceWithoutCallingTheFactory() {
        Object injected = new Object();
        LazyClient<Object> client = new LazyClient<>(injected, "sqs", () -> {
            throw new AssertionError("factory must not run");
        });
        assertSame(injected, client.get());
    }

    @Test
    void createsOnceOnFirstUse() {
        AtomicInteger calls = new AtomicInteger();
        LazyClient<Object> client = new LazyClient<>(null, "sqs", () -> {
            calls.incrementAndGet();
            return new Object();
        });
        assertEquals(0, calls.get(), "nothing created at build time");
        assertSame(client.get(), client.get());
        assertEquals(1, calls.get());
    }

    @Test
    void missingArtifactFailsWithAMessageNamingIt() {
        LazyClient<Object> client = new LazyClient<>(null, "redshiftdata", () -> {
            throw new NoClassDefFoundError("software/amazon/awssdk/services/redshiftdata/RedshiftDataClient");
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, client::get);
        assertTrue(e.getMessage().contains("software.amazon.awssdk:redshiftdata"), e.getMessage());
        assertInstanceOf(NoClassDefFoundError.class, e.getCause());
    }
}
