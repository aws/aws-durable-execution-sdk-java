// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.OperationIdentifier;

class MapOperationCompatibilityTest {

    @Test
    void retainsLegacyPublicConstructor() {
        assertDoesNotThrow(() -> MapOperation.class.getConstructor(
                OperationIdentifier.class,
                List.class,
                DurableContext.MapFunction.class,
                TypeToken.class,
                MapConfig.class,
                DurableContextImpl.class));
    }
}
