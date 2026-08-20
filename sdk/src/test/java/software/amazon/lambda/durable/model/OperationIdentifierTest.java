// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.model.OperationSubType.WAIT_FOR_CONDITION;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;

class OperationIdentifierTest {

    @Test
    void preservesOriginalRecordApi() throws ReflectiveOperationException {
        var constructor =
                OperationIdentifier.class.getDeclaredConstructor(String.class, String.class, OperationSubType.class);
        var subTypeAccessor = OperationIdentifier.class.getDeclaredMethod("subType");

        assertTrue(OperationIdentifier.class.isRecord());
        assertEquals(OperationIdentifier.class, constructor.getDeclaringClass());
        assertEquals(OperationSubType.class, subTypeAccessor.getReturnType());
    }

    @Test
    void standardSubtypeFactoryRetainsEnumValue() {
        var identifier = OperationIdentifier.of("operation-1", "condition", WAIT_FOR_CONDITION);

        assertEquals(OperationType.STEP, identifier.operationType());
        assertEquals(WAIT_FOR_CONDITION, identifier.subType());
    }
}
