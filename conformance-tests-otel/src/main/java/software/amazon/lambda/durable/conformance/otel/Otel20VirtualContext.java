// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.RunInChildContextConfig;

/** Virtual child-context scenario for OTel requirement otel-invocation-20. */
public final class Otel20VirtualContext extends OtelConformanceHandler<String> {

  @Override
  public String handleRequest(Map<String, Object> event, DurableContext context) {
    requireScenario(event, "virtual-context");
    return context.runInChildContext(
        "otel-virtual-context",
        String.class,
        child -> "virtual-complete",
        RunInChildContextConfig.builder().isVirtual(true).build());
  }
}
