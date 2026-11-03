// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;

class MapOperationTypeTest {

    @Test
    void mapResultTypeHasValueEquality() {
        var first = MapOperation.mapResultType(TypeToken.get(String.class));
        var second = MapOperation.mapResultType(TypeToken.get(String.class));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.getType(), second.getType());
        assertEquals(first.getType().hashCode(), second.getType().hashCode());
        assertNotEquals(
                first.getType(),
                MapOperation.mapResultType(TypeToken.get(Integer.class)).getType());
    }
}
