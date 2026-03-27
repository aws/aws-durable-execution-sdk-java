// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.serde.JacksonSerDes;

public class DeserializationFailureExample extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        try {
            context.step(
                    "fail-step",
                    String.class,
                    stepCtx -> {
                        throw new RuntimeException("this is a test");
                    },
                    StepConfig.builder().serDes(new FailedSerDes()).build());
        } catch (SuspendExecutionException e) {
            throw e;
        } catch (Exception e) {
            context.wait("suspend and replay", Duration.ofSeconds(1));
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }

        throw new IllegalStateException("should not reach here");
    }

    private static class FailedSerDes extends JacksonSerDes {

        @Override
        public <T> T deserialize(String json, TypeToken<T> typeToken) {
            T result = super.deserialize(json, typeToken);
            if (result instanceof RuntimeException ex) {
                throw new SerDesException("Deserialization failed", ex);
            }
            return result;
        }
    }
}
