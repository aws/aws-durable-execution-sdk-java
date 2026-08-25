// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.nio.charset.StandardCharsets;

/** Converts strings to and from UTF-8 bytes. */
public final class Utf8StringBinaryCodec implements StringBinaryCodec {
    public static final Utf8StringBinaryCodec INSTANCE = new Utf8StringBinaryCodec();

    private Utf8StringBinaryCodec() {}

    @Override
    public byte[] toBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String fromBytes(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}
