// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Base64;

/** Converts bytes to and from standard Base64 strings. */
public final class Base64StringBinaryCodec implements StringBinaryCodec {
    public static final Base64StringBinaryCodec INSTANCE = new Base64StringBinaryCodec();

    private Base64StringBinaryCodec() {}

    @Override
    public byte[] toBytes(String value) {
        return Base64.getDecoder().decode(value);
    }

    @Override
    public String fromBytes(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
