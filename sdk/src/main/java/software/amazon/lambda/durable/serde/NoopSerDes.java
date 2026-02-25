// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.lambda.durable.TypeToken;

/** a placeholder for operations that don't have data to serialize or deserialize */
public class NoopSerDes implements SerDes {
    @Override
    public String serialize(Object value) {
        return "";
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        return null;
    }
}
