// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * 10-6: Plugin sees is-first-invocation true once, then false on replay.
 *
 * <p>A single 2-second wait configured with {@link ConformanceLoggingPlugin}. The first invocation reports
 * {@code first=true} (then suspends), and the replay invocation reports {@code first=false} and finalizes with
 * {@code status=SUCCEEDED}.
 */
@SuppressWarnings("deprecation")
public class PluginFirstInvocationFlag extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(new ConformanceLoggingPlugin("CONFPLUGIN"))
                .build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(2));
        return "Wait completed";
    }
}
