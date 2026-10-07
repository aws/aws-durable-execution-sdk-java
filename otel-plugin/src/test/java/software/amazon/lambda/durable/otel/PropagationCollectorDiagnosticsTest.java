// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.plugin.PropagationInput;
import software.amazon.lambda.durable.plugin.PropagationMetadata;

class PropagationCollectorDiagnosticsTest {
    @Test
    void differentValuesNameBothOwnersAndCountConflictsWithoutLoggingHeaders() {
        var logger = (Logger) LoggerFactory.getLogger(PluginRunner.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var runner = new PluginRunner(List.of(
                    plugin("private-first"),
                    plugin("private-first"),
                    plugin("private-second"),
                    plugin("private-third")));
            assertEquals(
                    "private-first",
                    runner.providePropagationMetadata(new PropagationInput("arn", "op", null, "target"))
                            .xAmznTraceId());
            var warnings = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(2, warnings.size(), "Equal values are not conflicts");
            assertTrue(warnings.get(0).contains("[2]"));
            assertTrue(warnings.get(0).contains("[0]"));
            assertTrue(warnings.get(0).contains("conflict count: 1"));
            assertTrue(warnings.get(1).contains("[3]"));
            assertTrue(warnings.get(1).contains("[0]"));
            assertTrue(warnings.get(1).contains("conflict count: 2"));
            assertTrue(warnings.stream().noneMatch(message -> message.contains("private-")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static DurableExecutionPlugin plugin(String header) {
        return new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                return new PropagationMetadata(header);
            }
        };
    }
}
