// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.InvokeConfig;

/** 5-8: Invoke with tenantId (tenant-isolated invocation) */
public class InvokeWithTenantId extends DurableHandler<Map<String, String>, String> {

    @Override
    public String handleRequest(Map<String, String> input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        String tenantId = input.get("tenantId");
        String payload = input.get("payload");
        return context.invoke(
                "invoke-tenant",
                functionName,
                payload,
                String.class,
                InvokeConfig.builder().tenantId(tenantId).build());
    }
}
