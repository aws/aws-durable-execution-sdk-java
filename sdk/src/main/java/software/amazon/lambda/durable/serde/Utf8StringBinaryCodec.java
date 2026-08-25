// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import software.amazon.lambda.durable.exception.SerDesException;

/** Converts strings to and from UTF-8 bytes. */
public final class Utf8StringBinaryCodec implements StringBinaryCodec {
    public static final Utf8StringBinaryCodec INSTANCE = new Utf8StringBinaryCodec();

    private Utf8StringBinaryCodec() {}

    @Override
    public byte[] toBytes(String value) {
        var encoder = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            var encoded = encoder.encode(CharBuffer.wrap(value));
            var result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new SerDesException("Failed to encode string as UTF-8", e);
        }
    }

    @Override
    public String fromBytes(byte[] data) {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new SerDesException("Failed to decode UTF-8 bytes", e);
        }
    }
}
